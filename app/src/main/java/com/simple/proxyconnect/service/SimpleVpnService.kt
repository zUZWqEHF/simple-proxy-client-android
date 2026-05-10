package com.simple.proxyconnect.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.simple.proxyconnect.MainActivity
import com.simple.proxyconnect.model.ProxyNode
import com.simple.proxyconnect.model.RoutingMode
import com.simple.proxyconnect.service.singbox.SingBoxRuntime
import com.simple.proxyconnect.service.singbox.SimpleProtocolSocksBridge
import com.simple.proxyconnect.service.vpn.*
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android VpnService that captures IP packets from a TUN device,
 * implements userspace TCP session tracking, and relays data through
 * Shadowsocks / SimpleProtocol proxy tunnels.
 *
 * Architecture:
 *   TUN fd  ←read──  [Packet Reader Thread]
 *                          │
 *              ┌───────────┼───────────┐
 *            TCP SYN     TCP DATA    UDP :53
 *              │           │           │
 *         create session  forward   DNS proxy
 *         SYN-ACK ──→TUN  to proxy  forward & reply
 *              │
 *      [Proxy Reader Thread per session]
 *         read proxy response
 *         build TCP packet ──→ TUN
 */
class SimpleVpnService : VpnService() {

    companion object {
        private const val TAG = "SimpleVpnService"
        private const val CHANNEL_ID = "simple_vpn_channel"
        private const val NOTIFICATION_ID = 1
        private const val FORCE_FULL_BRIDGE_MODE = true
        private const val VERBOSE_CLIENT_LOGS = false
        private const val MAX_PACKET = 32767
        private const val TCP_MSS = 1400
        private const val MIN_TCP_MSS = 536
        private const val DEFAULT_PEER_MSS = 1200
        private const val MAX_ACTIVE_SESSIONS = 1024
        private const val PROXY_WORKER_THREADS = 64
        private const val BUILD_ID = "1.2.4-b20-pgsz16k"

        const val ACTION_START = "com.simple.proxyconnect.START"
        const val ACTION_STOP = "com.simple.proxyconnect.STOP"
        const val EXTRA_NODE_JSON = "node_json"
        const val EXTRA_ROUTING_MODE = "routing_mode"

        var isRunning = AtomicBoolean(false)
        var statusCallback: ((Boolean) -> Unit)? = null
    }

    // ── Service state ──
    private var vpnFd: ParcelFileDescriptor? = null
    private var tunInput: FileInputStream? = null
    private var tunOutput: FileOutputStream? = null
    private val tunWriteLock = Any()
    private val running = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    @Volatile private var suppressStatusCallback = false
    private val json = Json { ignoreUnknownKeys = true }
    private val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var proxyNode: ProxyNode? = null
    private var routingMode: RoutingMode = RoutingMode.Global

    // Resolved proxy server address (as Int for fast comparison in packet loop)
    private var proxyServerIpInt: Int = 0
    private var proxyResolvedAddress: InetAddress? = null

    // Thread pool for proxy I/O (one reader per TCP session + DNS)
    private var executor: ExecutorService? = null
    private var singBoxRuntime: SingBoxRuntime? = null
    private var simpleProtocolBridge: SimpleProtocolSocksBridge? = null

    // ── TCP session table ──
    // key = "srcIp:srcPort>dstIp:dstPort"
    private val sessions = ConcurrentHashMap<String, TcpSession>()

    // ── DNS acceleration / stability ──
    private data class DnsCacheEntry(val expiresAtMs: Long, val response: ByteArray)
    private data class DnsIpHint(val domain: String, val expiresAtMs: Long)
    private val dnsCache = ConcurrentHashMap<String, DnsCacheEntry>()
    private val dnsSemaphore = Semaphore(8, true)
    private val dnsInflight = ConcurrentHashMap<String, CompletableFuture<ByteArray?>>()
    private val dnsIpHints = ConcurrentHashMap<Int, DnsIpHint>()

    private fun logDebug(message: String) {
        if (VERBOSE_CLIENT_LOGS) {
            Log.d(TAG, message)
        }
    }

    /** Represents one tracked TCP connection. */
    private class TcpSession(
        val srcIp: Int,
        val srcPort: Int,
        val dstIp: Int,
        val dstPort: Int,
        @Volatile var remoteSeq: Long,   // next expected seq from the app
        @Volatile var localSeq: Long,    // our next seq number towards the app
        @Volatile var state: State = State.SYN_RECEIVED,
        @Volatile var tunnel: ProxyTunnel? = null,
        @Volatile var upstreamSocket: Socket? = null,
        @Volatile var proxyReady: Boolean = false,
        @Volatile var appFinReceived: Boolean = false,
        @Volatile var finSentToApp: Boolean = false,
        @Volatile var appToProxyBytes: Long = 0,
        @Volatile var appToProxyFrames: Int = 0,
        @Volatile var proxyToAppBytes: Long = 0,
        @Volatile var proxyToAppFrames: Int = 0,
        @Volatile var closeReason: String = "unknown",
        val createdAtMs: Long = System.currentTimeMillis(),
        @Volatile var lastAppDataAtMs: Long = 0,
        @Volatile var lastProxyDataAtMs: Long = 0,
        val targetDomainHint: String?,
        @Volatile var sniffedDomain: String? = null,
        val peerMss: Int,
        val pendingData: LinkedBlockingQueue<ByteArray> = LinkedBlockingQueue(),
        val outOfOrderData: MutableMap<Long, ByteArray> = HashMap(),
        val seqLock: Any = Any(),
        val tunnelWriteLock: Any = Any()  // protects tunnel writes + proxyReady flag
    ) {
        enum class State { SYN_RECEIVED, ESTABLISHED, CLOSING, CLOSED }
    }

    // ────────────────────────────────────────────────────────
    //  Service lifecycle
    // ────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val nodeJson = intent.getStringExtra(EXTRA_NODE_JSON)
                val modeStr = intent.getStringExtra(EXTRA_ROUTING_MODE)
                if (nodeJson != null && modeStr != null) {
                    proxyNode = try {
                        json.decodeFromString<ProxyNode>(nodeJson)
                    } catch (e: Exception) {
                        Log.e(TAG, "Bad node json", e)
                        stopSelf(); return START_NOT_STICKY
                    }
                    val requestedMode = try { RoutingMode.valueOf(modeStr) } catch (_: Exception) { RoutingMode.Global }
                    routingMode = requestedMode
                    Log.i(TAG, "Routing mode selected: $routingMode  build=$BUILD_ID")
                    controlExecutor.submit {
                        if (running.get()) {
                            Log.i(TAG, "ACTION_START while running: restarting VPN with new config")
                            suppressStatusCallback = true
                            cleanupVpnResources()
                            suppressStatusCallback = false
                        }
                        startVpn()
                    }
                }
            }
            ACTION_STOP -> {
                controlExecutor.submit {
                    stopVpn()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        controlExecutor.shutdownNow()
        super.onDestroy()
    }

    // ────────────────────────────────────────────────────────
    //  VPN setup / teardown
    // ────────────────────────────────────────────────────────

    private fun startVpn() {
        if (!starting.compareAndSet(false, true)) return
        try {
        if (running.get()) return
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        // ── Resolve proxy server IP BEFORE establishing TUN ──
        // (So DNS resolution goes through the real network, not our VPN)
        val node = proxyNode ?: run {
            Log.e(TAG, "No proxy node configured")
            statusCallback?.invoke(false)
            stopSelf(); return
        }
        try {
            proxyResolvedAddress = InetAddress.getByName(node.host)
            val addrBytes = proxyResolvedAddress!!.address
            proxyServerIpInt = ((addrBytes[0].toInt() and 0xFF) shl 24) or
                    ((addrBytes[1].toInt() and 0xFF) shl 16) or
                    ((addrBytes[2].toInt() and 0xFF) shl 8) or
                    (addrBytes[3].toInt() and 0xFF)
            Log.i(TAG, "Proxy server resolved: ${proxyResolvedAddress!!.hostAddress} (int=$proxyServerIpInt)")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot resolve proxy host: ${node.host}", e)
            statusCallback?.invoke(false)
            stopSelf(); return
        }

        // ── Fail-fast probe: verify protocol path by resolving DNS through proxy ──
        // Run probe off main thread to avoid NetworkOnMainThreadException.
        if (!probeProxyPathOnWorker()) {
            Log.e(TAG, "Proxy startup probe failed, aborting VPN start")
            stopForeground(STOP_FOREGROUND_REMOVE)
            statusCallback?.invoke(false)
            stopSelf()
            return
        }

        running.set(true)
        isRunning.set(true)
        statusCallback?.invoke(true)

        // Phase-1 sing-box migration: start core sidecar (if provided) for config/log validation.
        // Data plane still uses legacy stack in this phase.
        singBoxRuntime = SingBoxRuntime(this)
        val singBoxStarted = runCatching { singBoxRuntime?.start(node, routingMode) ?: false }
            .getOrElse {
                Log.w(TAG, "sing-box warmup failed: ${it.message}")
                false
            }
        Log.i(TAG, "sing-box warmup=${if (singBoxStarted) "started" else "skipped"}")

        if (FORCE_FULL_BRIDGE_MODE && !singBoxStarted) {
            Log.e(TAG, "FORCE_FULL_BRIDGE_MODE enabled but sing-box failed to start; abort VPN startup")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            running.set(false)
            isRunning.set(false)
            statusCallback?.invoke(false)
            return
        }

        if (singBoxStarted) {
            simpleProtocolBridge = SimpleProtocolSocksBridge(node) { socket -> protect(socket) }
            val bridgeStarted = runCatching { simpleProtocolBridge?.start(16080) ?: false }
                .getOrElse {
                    Log.e(TAG, "SimpleProtocol bridge start failed: ${it.message}")
                    false
                }
            Log.i(TAG, "simpleprotocol bridge=${if (bridgeStarted) "started" else "failed"}")

            if (FORCE_FULL_BRIDGE_MODE && !bridgeStarted) {
                Log.e(TAG, "FORCE_FULL_BRIDGE_MODE enabled but bridge failed to start; abort VPN startup")
                runCatching { simpleProtocolBridge?.stop() }
                simpleProtocolBridge = null
                runCatching { singBoxRuntime?.stop() }
                singBoxRuntime = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                running.set(false)
                isRunning.set(false)
                statusCallback?.invoke(false)
                return
            }
        }

        // ── Warm up bridge: establish mux connection to remote server ──
        // Send a DNS query through the SOCKS bridge to pre-create the mux
        // connection, so the first real request doesn't suffer cold-start latency.
        if (simpleProtocolBridge != null) {
            warmUpBridge()
        }

        executor = Executors.newFixedThreadPool(PROXY_WORKER_THREADS)

        // Build and establish the TUN interface
        val builder = Builder()
            .setSession("Simple VPN")
            .addAddress("10.0.0.2", 32)
            .setMtu(1500)

        // VPN DNS servers must be reachable from the proxy path used:
        //   Global: all traffic → proxy → US server, so use global DNS (8.8.8.8/1.1.1.1)
        //           which can accept DoT on port 853 from the US proxy.
        //   BypassChina: Chinese DNS traffic routes direct (through phone's network),
        //           so use Chinese DNS (223.5.5.5/119.29.29.29) for low-latency DoT.
        if (routingMode == RoutingMode.BypassChina) {
            builder.addDnsServer("223.5.5.5")
            builder.addDnsServer("119.29.29.29")
        } else {
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("1.1.1.1")
        }

        // Route all traffic through VPN
        builder.addRoute("0.0.0.0", 0)

        // ── Exclude our own app from VPN routes ──
        // sing-box runs as a subprocess (same UID). By excluding our app,
        // sing-box's direct outbound connections go through the physical
        // network, not back through TUN. This eliminates the TUN loop
        // without needing protect() on every socket.
        try {
            builder.addDisallowedApplication(packageName)
            Log.i(TAG, "Excluded self ($packageName) from VPN routes")
        } catch (e: Exception) {
            Log.w(TAG, "addDisallowedApplication failed: ${e.message}")
        }

        vpnFd = builder.establish()
        if (vpnFd == null) {
            Log.e(TAG, "TUN establish() returned null")
            stopVpn(); return
        }

        tunInput = FileInputStream(vpnFd!!.fileDescriptor)
        tunOutput = FileOutputStream(vpnFd!!.fileDescriptor)
        updateNotification("Connected to ${proxyNode?.name ?: "proxy"}")

        // Start the main packet reader loop
        executor?.submit { packetReaderLoop() }
        } finally {
            starting.set(false)
        }
    }

    /**
     * Probe proxy data path by sending a DNS-over-TCP query to 8.8.8.8:53 through
     * the same encrypted tunnel used by normal traffic.
     */
    private fun probeProxyPathOnWorker(): Boolean {
        val probeExecutor = Executors.newSingleThreadExecutor()
        return try {
            val future = probeExecutor.submit<Boolean> { probeProxyPath() }
            future.get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Probe worker failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            probeExecutor.shutdownNow()
        }
    }

    private fun probeProxyPath(): Boolean {
        val txId = ((System.currentTimeMillis() ushr 8) and 0xFFFF).toInt()
        val query = buildDnsQueryA("google.com", txId)
        val response = tryDnsThroughProxy("8.8.8.8", query, requireProtect = false)
        if (response == null || response.size < 12) {
            Log.e(TAG, "Probe failed: empty DNS response through proxy")
            return false
        }
        val flags = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        val rcode = flags and 0x000F
        if (rcode != 0) {
            Log.e(TAG, "Probe failed: DNS rcode=$rcode")
            return false
        }
        Log.i(TAG, "Proxy startup probe OK")
        return true
    }

    /**
     * Warm up the SOCKS bridge by sending a DNS query through it.
     * This pre-establishes the mux connection to the remote server,
     * eliminating cold-start latency for the first real request.
     */
    private fun warmUpBridge() {
        try {
            val sock = java.net.Socket()
            sock.connect(java.net.InetSocketAddress("127.0.0.1", 16080), 8000)
            sock.soTimeout = 8000
            val os = sock.getOutputStream()
            val ins = sock.getInputStream()

            // SOCKS5 handshake: no-auth
            os.write(byteArrayOf(0x05, 0x01, 0x00))
            os.flush()
            val authResp = ByteArray(2)
            readFully(ins, authResp)
            if (authResp[0] != 0x05.toByte() || authResp[1] != 0x00.toByte()) {
                Log.w(TAG, "Bridge warm-up: SOCKS5 auth failed")
                sock.close(); return
            }

            // SOCKS5 CONNECT to 8.8.8.8:53
            os.write(byteArrayOf(
                0x05, 0x01, 0x00, 0x01,                  // VER CMD RSV ATYP=IPv4
                0x08, 0x08, 0x08, 0x08,                  // 8.8.8.8
                0x00, 0x35                                 // port 53
            ))
            os.flush()
            val connResp = ByteArray(10)
            readFully(ins, connResp)
            if (connResp[1] != 0x00.toByte()) {
                Log.w(TAG, "Bridge warm-up: SOCKS5 connect failed, rep=${connResp[1]}")
                sock.close(); return
            }

            // Send a DNS A query for google.com and read response
            val txId = ((System.nanoTime() ushr 8) and 0xFFFF).toInt()
            val dnsQuery = buildDnsQueryA("google.com", txId)
            // DNS over TCP: 2-byte length prefix
            os.write((dnsQuery.size ushr 8) and 0xFF)
            os.write(dnsQuery.size and 0xFF)
            os.write(dnsQuery)
            os.flush()

            val lenBuf = ByteArray(2)
            readFully(ins, lenBuf)
            val respLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
            if (respLen in 12..4096) {
                val respBuf = ByteArray(respLen)
                readFully(ins, respBuf)
                Log.i(TAG, "Bridge warm-up OK: DNS response ${respBuf.size} bytes")
            } else {
                Log.w(TAG, "Bridge warm-up: unexpected DNS response length=$respLen")
            }
            sock.close()
        } catch (e: Exception) {
            Log.w(TAG, "Bridge warm-up failed (non-fatal): ${e.message}")
        }
    }

    private fun buildDnsQueryA(domain: String, txId: Int): ByteArray {
        val out = ByteArrayOutputStream(128)
        out.write((txId ushr 8) and 0xFF)
        out.write(txId and 0xFF)
        out.write(0x01) // RD=1
        out.write(0x00)
        out.write(0x00); out.write(0x01) // QDCOUNT=1
        out.write(0x00); out.write(0x00) // ANCOUNT=0
        out.write(0x00); out.write(0x00) // NSCOUNT=0
        out.write(0x00); out.write(0x00) // ARCOUNT=0
        for (label in domain.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size and 0xFF)
            out.write(bytes)
        }
        out.write(0x00) // end qname
        out.write(0x00); out.write(0x01) // QTYPE=A
        out.write(0x00); out.write(0x01) // QCLASS=IN
        return out.toByteArray()
    }

    /**
     * Internal cleanup: tear down TUN, sessions, bridge, sing-box.
     * Does NOT call stopSelf()/stopForeground() so the service stays alive
     * during a restart (mode switch).
     */
    private fun cleanupVpnResources() {
        if (!running.compareAndSet(true, false)) return
        isRunning.set(false)
        if (!suppressStatusCallback) {
            statusCallback?.invoke(false)
        }

        // Close all sessions
        for ((key, session) in sessions) {
            session.tunnel?.close()
            runCatching { session.upstreamSocket?.close() }
            sessions.remove(key)
        }

        runCatching { tunInput?.close() }
        runCatching { tunOutput?.close() }
        runCatching { vpnFd?.close() }
        vpnFd = null; tunInput = null; tunOutput = null

        executor?.shutdownNow()
        executor = null

        simpleProtocolBridge?.stop()
        simpleProtocolBridge = null

        singBoxRuntime?.stop()
        singBoxRuntime = null
    }

    /**
     * Full stop: cleanup + stop the service.
     */
    private fun stopVpn() {
        cleanupVpnResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ────────────────────────────────────────────────────────
    //  Main packet reader
    // ────────────────────────────────────────────────────────

    private fun packetReaderLoop() {
        Log.i(TAG, "Packet reader loop started, proxy=${proxyResolvedAddress?.hostAddress}:${proxyNode?.port}")
        val buf = ByteArray(MAX_PACKET)
        val input = tunInput ?: return
        try {
            while (running.get()) {
                val len = input.read(buf)
                if (len <= 0) { Thread.sleep(1); continue }
                val pkt = buf.copyOf(len)
                processPacket(pkt, len)
            }
        } catch (e: IOException) {
            if (running.get()) Log.e(TAG, "TUN read error", e)
        }
    }

    private fun processPacket(pkt: ByteArray, len: Int) {
        val ip = Packet.parseIpv4(pkt) ?: return
        when (ip.protocol) {
            PROTO_TCP -> onTcp(pkt, ip)
            PROTO_UDP -> onUdp(pkt, ip)
        }
    }

    // ────────────────────────────────────────────────────────
    //  TCP processing
    // ────────────────────────────────────────────────────────

    private fun sessionKey(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int) =
        "$srcIp:$srcPort>$dstIp:$dstPort"

    private fun onTcp(pkt: ByteArray, ip: Ipv4Header) {
        val tcp = Packet.parseTcp(pkt, ip.headerLength) ?: return
        val key = sessionKey(ip.sourceIp, tcp.sourcePort, ip.destIp, tcp.destPort)

        // ── RST from app: tear down ──
        if (tcp.isRst) {
            sessions.remove(key)?.let {
                it.tunnel?.close()
                runCatching { it.upstreamSocket?.close() }
            }
            return
        }

        // ── SYN (new connection) ──
        if (tcp.isSyn && !tcp.isAck) {
            onSyn(key, ip, tcp, pkt)
            return
        }

        val session = sessions[key] ?: return

        val payloadStart = ip.headerLength + tcp.dataOffset
        val payloadLen = ip.totalLength - payloadStart

        // FIN may carry data; always process payload first.
        if (payloadLen > 0 && tcp.isAck) {
            onData(key, session, pkt, payloadStart, payloadLen, tcp)
        }

        // ── FIN from app ──
        if (tcp.isFin) {
            onFin(key, session, tcp)
            return
        }

        // ── ACK with or without data ──
        // Pure ACK: nothing to do.
    }

    /**
     * Incoming SYN — create a session, reply SYN-ACK, start proxy in background.
     */
    /**
     * Check if an IP address is non-routable / bogus and should NOT be proxied.
     */
    private fun isNonRoutable(ip: Int): Boolean {
        val b0 = (ip ushr 24) and 0xFF
        val b1 = (ip ushr 16) and 0xFF
        return when {
            b0 == 0 -> true            // 0.0.0.0/8  "This network"
            b0 == 10 -> true           // 10.0.0.0/8  Private
            b0 == 100 && (b1 and 0xC0) == 64 -> true  // 100.64.0.0/10 CGNAT (RFC 6598)
            b0 == 127 -> true          // 127.0.0.0/8 Loopback
            b0 == 169 && b1 == 254 -> true  // 169.254.0.0/16 Link-local
            b0 == 172 && b1 in 16..31 -> true  // 172.16.0.0/12 Private
            b0 == 192 && b1 == 168 -> true     // 192.168.0.0/16 Private
            b0 in 224..255 -> true     // 224.0.0.0/4 Multicast + 240+ Reserved
            else -> false
        }
    }

    private fun parsePeerMssFromSyn(pkt: ByteArray, ip: Ipv4Header, tcp: TcpHeader): Int {
        val optStart = ip.headerLength + 20
        val optEnd = ip.headerLength + tcp.dataOffset
        if (optEnd <= optStart || optEnd > pkt.size) return DEFAULT_PEER_MSS

        var off = optStart
        while (off < optEnd) {
            val kind = pkt[off].toInt() and 0xFF
            when (kind) {
                0 -> break
                1 -> off += 1
                else -> {
                    if (off + 1 >= optEnd) break
                    val len = pkt[off + 1].toInt() and 0xFF
                    if (len < 2 || off + len > optEnd) break
                    if (kind == 2 && len == 4) {
                        val mss = ((pkt[off + 2].toInt() and 0xFF) shl 8) or (pkt[off + 3].toInt() and 0xFF)
                        return mss.coerceIn(MIN_TCP_MSS, TCP_MSS)
                    }
                    off += len
                }
            }
        }
        return DEFAULT_PEER_MSS
    }

    private fun onSyn(key: String, ip: Ipv4Header, tcp: TcpHeader, pkt: ByteArray) {
        val domainHint = lookupDnsHint(ip.destIp)

        if (isNonRoutable(ip.destIp)) {
            logDebug("Skipping SYN to non-routable IP: ${Packet.ipToString(ip.destIp)}:${tcp.destPort} domain=$domainHint")
            sendRstForSyn(ip, tcp)
            return
        }

        // If a session already exists (SYN retransmit), re-send SYN-ACK
        sessions[key]?.let { existing ->
            sendSynAck(existing)
            return
        }

        if (sessions.size >= MAX_ACTIVE_SESSIONS) {
            Log.w(TAG, "Too many active sessions (${sessions.size}), rejecting SYN: $key domain=$domainHint")
            sendRstForSyn(ip, tcp)
            return
        }

        val initialSeq = System.nanoTime() and 0xFFFFFFFFL
        val peerMss = parsePeerMssFromSyn(pkt, ip, tcp)
        val session = TcpSession(
            srcIp = ip.sourceIp,
            srcPort = tcp.sourcePort,
            dstIp = ip.destIp,
            dstPort = tcp.destPort,
            remoteSeq = (tcp.seqNumber + 1) and 0xFFFFFFFFL, // SYN consumes 1
            localSeq = initialSeq,
            targetDomainHint = domainHint,
            peerMss = peerMss
        )
        sessions[key] = session

        Log.i(TAG, "TCP SYN ($key) domain=$domainHint dst=${Packet.ipToString(ip.destIp)}:${tcp.destPort}")

        sendSynAck(session)
        synchronized(session.seqLock) {
            session.localSeq = (session.localSeq + 1) and 0xFFFFFFFFL // SYN-ACK consumes 1
        }

        // Connect to proxy in background
        executor?.submit { setupProxy(key, session) }
    }

    private fun sendSynAck(s: TcpSession) {
        val pkt = Packet.buildTcpPacket(
            s.dstIp, s.srcIp,
            s.dstPort, s.srcPort,
            s.localSeq, s.remoteSeq,
            TCP_SYN or TCP_ACK,
            mss = s.peerMss
        )
        writeTun(pkt)
    }

    /**
     * Incoming data — ACK it and forward payload to the proxy tunnel.
     */
    private fun onData(
        key: String, session: TcpSession,
        pkt: ByteArray, payloadStart: Int, payloadLen: Int,
        tcp: TcpHeader
    ) {
        val expectedSeq = synchronized(session.seqLock) { session.remoteSeq }
        val seqCmp = seqCompare(tcp.seqNumber, expectedSeq)

        val acceptedPayloads = ArrayList<ByteArray>(2)
        var nextRemoteSeq = expectedSeq

        when {
            seqCmp == 0 -> {
                val payload = pkt.copyOfRange(payloadStart, payloadStart + payloadLen)
                acceptedPayloads.add(payload)
                nextRemoteSeq = (nextRemoteSeq + payloadLen) and 0xFFFFFFFFL
            }
            seqCmp < 0 -> {
                val overlap = seqDistance(expectedSeq, tcp.seqNumber)
                if (overlap >= payloadLen) {
                    logDebug("Retransmit duplicate ($key): seq=${tcp.seqNumber}, expected=$expectedSeq, len=$payloadLen")
                    sendAck(session)
                    return
                }
                val adjustedStart = payloadStart + overlap
                val adjustedLen = payloadLen - overlap
                val payload = pkt.copyOfRange(adjustedStart, adjustedStart + adjustedLen)
                acceptedPayloads.add(payload)
                nextRemoteSeq = (nextRemoteSeq + adjustedLen) and 0xFFFFFFFFL
                logDebug("Retransmit overlap ($key): seq=${tcp.seqNumber}, expected=$expectedSeq, drop=$overlap keep=$adjustedLen")
            }
            else -> {
                // Future segment — buffer and wait for missing bytes.
                if (session.outOfOrderData.size > 64) {
                    session.outOfOrderData.clear()
                }
                if (!session.outOfOrderData.containsKey(tcp.seqNumber)) {
                    val payload = pkt.copyOfRange(payloadStart, payloadStart + payloadLen)
                    session.outOfOrderData[tcp.seqNumber] = payload
                }
                logDebug("Out-of-order buffered ($key): seq=${tcp.seqNumber}, expected=$expectedSeq, len=$payloadLen")
                sendAck(session)
                return
            }
        }

        // Drain buffered contiguous segments if present.
        while (true) {
            val next = session.outOfOrderData.remove(nextRemoteSeq) ?: break
            acceptedPayloads.add(next)
            nextRemoteSeq = (nextRemoteSeq + next.size) and 0xFFFFFFFFL
        }

        synchronized(session.seqLock) {
            session.remoteSeq = nextRemoteSeq
        }

        // ACK after advancing expected seq.
        sendAck(session)

        // Forward accepted payloads in order.
        for (payload in acceptedPayloads) {
            if (!forwardPayloadToProxy(key, session, payload)) {
                return
            }
        }
    }

    private fun forwardPayloadToProxy(key: String, session: TcpSession, payload: ByteArray): Boolean {
        synchronized(session.tunnelWriteLock) {
            if (session.appFinReceived) {
                return true
            }
            if (session.proxyReady) {
                try {
                    val upstream = session.upstreamSocket
                    if (upstream != null) {
                        upstream.getOutputStream().write(payload)
                        upstream.getOutputStream().flush()
                    } else {
                        session.tunnel?.writeFrame(payload)
                    }
                    session.appToProxyBytes += payload.size
                    session.appToProxyFrames += 1
                    session.lastAppDataAtMs = System.currentTimeMillis()
                    // Diagnostic: log app-to-proxy data forwarding
                    if (session.appToProxyFrames <= 3) {
                        val preview = if (payload.size > 0 && payload[0] in 0x20..0x7E) {
                            String(payload, 0, minOf(payload.size, 80), Charsets.ISO_8859_1).substringBefore("\r\n")
                        } else ""
                        Log.i(TAG, "App→Proxy ($key): frame=${session.appToProxyFrames} len=${payload.size} preview=\"$preview\" domain=${session.targetDomainHint ?: session.sniffedDomain}")
                    }
                } catch (e: Exception) {
                    session.closeReason = "write_to_proxy_failed:${e.javaClass.simpleName}"
                    logDebug("Write to proxy failed ($key): ${e.message}")
                    sendRst(session)
                    sessions.remove(key)
                    session.tunnel?.close()
                    runCatching { session.upstreamSocket?.close() }
                    return false
                }
            } else {
                session.pendingData.offer(payload)
            }
        }
        return true
    }

    /** Compare TCP sequence numbers (unsigned 32-bit), handling wrap-around. */
    private fun seqCompare(a: Long, b: Long): Int {
        val diff = (a - b) and 0xFFFFFFFFL
        if (diff == 0L) return 0
        return if (diff < 0x80000000L) 1 else -1
    }

    /** Distance from older sequence to newer sequence in bytes. */
    private fun seqDistance(newer: Long, older: Long): Int {
        return ((newer - older) and 0xFFFFFFFFL).toInt()
    }

    /** Send immediate RST+ACK for blocked SYN so app can fail fast. */
    private fun sendRstForSyn(ip: Ipv4Header, tcp: TcpHeader) {
        val rst = Packet.buildTcpPacket(
            ip.destIp, ip.sourceIp,
            tcp.destPort, tcp.sourcePort,
            seqNum = 0,
            ackNum = (tcp.seqNumber + 1) and 0xFFFFFFFFL,
            flags = TCP_RST or TCP_ACK
        )
        writeTun(rst)
    }

    /**
     * Incoming FIN — close the session.
     */
    private fun onFin(key: String, session: TcpSession, tcp: TcpHeader) {
        // FIN consumes one sequence number beyond already-processed payload.
        synchronized(session.seqLock) {
            session.remoteSeq = (session.remoteSeq + 1) and 0xFFFFFFFFL
        }
        session.appFinReceived = true
        session.state = TcpSession.State.CLOSING

        // ACK app FIN first (half-close semantics)
        sendAck(session)

        // Stop sending request body to upstream, but keep reading response.
        synchronized(session.tunnelWriteLock) {
            runCatching { session.tunnel?.shutdownOutput() }
            runCatching { session.upstreamSocket?.shutdownOutput() }
        }

        session.closeReason = "app_fin"
        logDebug("App FIN received ($key), switched to half-close")
    }

    // ────────────────────────────────────────────────────────
    //  Proxy connection (runs in executor thread)
    // ────────────────────────────────────────────────────────

    private fun setupProxy(key: String, session: TcpSession) {
        try {
            val node = proxyNode ?: throw IOException("No proxy node")
            val resolvedAddr = proxyResolvedAddress ?: throw IOException("Proxy IP not resolved")

            val useSingBoxSocks = (singBoxRuntime?.isActive() == true) &&
                (simpleProtocolBridge?.isActive() == true)

            if (FORCE_FULL_BRIDGE_MODE && !useSingBoxSocks) {
                throw IOException(
                    "Forced bridge mode unavailable: routingMode=$routingMode, " +
                            "singBoxActive=${singBoxRuntime?.isActive()}, bridgeActive=${simpleProtocolBridge?.isActive()}"
                )
            }

            if (useSingBoxSocks) {
                // Provide domain hint to sing-box so it can apply domain-based routing.
                // sing-box handles direct/proxy split internally via its route rules.
                val domain = session.targetDomainHint

                val upstream = connectViaLocalSocks(session.dstIp, session.dstPort, domain)
                session.upstreamSocket = upstream
                session.state = TcpSession.State.ESTABLISHED

                synchronized(session.tunnelWriteLock) {
                    val out = upstream.getOutputStream()
                    while (true) {
                        val pending = session.pendingData.poll() ?: break
                        out.write(pending)
                        session.appToProxyBytes += pending.size
                        session.appToProxyFrames += 1
                        session.lastAppDataAtMs = System.currentTimeMillis()
                    }
                    out.flush()
                    if (session.appFinReceived) {
                        runCatching { upstream.shutdownOutput() }
                        logDebug("Applied deferred half-close after sing-box SOCKS connect ($key)")
                    }
                    session.proxyReady = true
                }

                logDebug("Proxy ready via sing-box SOCKS: $key domain=$domain")
                launchSessionWatchdog(key, session)
                proxyReaderLoopRaw(key, session, upstream)
                return
            }

            // Use SocketChannel.open() which creates a real OS socket with valid FD immediately.
            // This ensures protect() can bind the socket to the real network interface
            // BEFORE connect(), preventing the TUN from capturing proxy traffic.
            val channel = SocketChannel.open()
            val socket = channel.socket()

            val isProtected = protect(socket)
            logDebug("protect() returned $isProtected for $key")
            if (!isProtected) {
                channel.close()
                throw IOException("protect() returned false — proxy socket would loop through TUN")
            }

            // Connect using the pre-resolved IP to avoid DNS going through TUN
            socket.connect(InetSocketAddress(resolvedAddr, node.port), 10_000)
            socket.soTimeout = 0  // blocking reads
            socket.tcpNoDelay = true
            logDebug("Proxy connected: $key → ${resolvedAddr.hostAddress}:${node.port}")

            val tunnel = ProxyTunnel(socket, node)
            tunnel.connect(session.dstIp, session.dstPort)

            session.tunnel = tunnel
            session.state = TcpSession.State.ESTABLISHED

            // Flush pending data and set proxyReady atomically under the write lock.
            // This prevents the race where onData checks proxyReady=false, queues data,
            // but the flush loop already finished — leaving data stuck in the queue.
            synchronized(session.tunnelWriteLock) {
                while (true) {
                    val pending = session.pendingData.poll() ?: break
                    tunnel.writeFrame(pending)
                    session.appToProxyBytes += pending.size
                    session.appToProxyFrames += 1
                    session.lastAppDataAtMs = System.currentTimeMillis()
                }
                if (session.appFinReceived) {
                    runCatching { tunnel.shutdownOutput() }
                    logDebug("Applied deferred half-close after proxy connect ($key)")
                }
                session.proxyReady = true
            }
            logDebug("Proxy ready: $key")

            launchSessionWatchdog(key, session)

            // Read proxy responses → build TCP packets → write to TUN
            proxyReaderLoop(key, session, tunnel)

        } catch (e: Exception) {
            Log.e(TAG, "Proxy setup failed ($key): ${e.message}")
            sendRst(session)
            sessions.remove(key)
        }
    }

        /**
         * Continuously read frames from the proxy, wrap them in TCP packets,
         * and write back to the TUN device.
         */
    private fun proxyReaderLoop(key: String, session: TcpSession, tunnel: ProxyTunnel) {
        var frameCount = 0
        var totalBytes = 0L
        try {
            while (running.get() && session.state == TcpSession.State.ESTABLISHED) {
                val data = tunnel.readFrame() ?: break
                frameCount++
                totalBytes += data.size
                session.proxyToAppFrames += 1
                session.proxyToAppBytes += data.size
                session.lastProxyDataAtMs = System.currentTimeMillis()

                // Chunk into MSS-sized segments to stay within MTU
                var offset = 0
                while (offset < data.size) {
                    val chunkSize = minOf(data.size - offset, session.peerMss)
                    val chunk = if (offset == 0 && chunkSize == data.size) data
                                else data.copyOfRange(offset, offset + chunkSize)

                    val pkt = synchronized(session.seqLock) {
                        val built = Packet.buildTcpPacket(
                            session.dstIp, session.srcIp,
                            session.dstPort, session.srcPort,
                            session.localSeq, session.remoteSeq,
                            TCP_ACK or TCP_PSH,
                            payload = chunk
                        )
                        session.localSeq = (session.localSeq + chunkSize) and 0xFFFFFFFFL
                        built
                    }
                    writeTun(pkt)
                    offset += chunkSize
                }
            }
        } catch (e: Exception) {
            session.closeReason = "proxy_reader_exception:${e.javaClass.simpleName}"
            if (running.get()) logDebug("Proxy reader ended ($key): ${e.message}")
        } finally {
            Log.i(TAG, "Proxy reader done ($key): frames=$frameCount, bytes=$totalBytes")
            // Send FIN to the app when upstream read side closes.
            if (!session.finSentToApp && session.state != TcpSession.State.CLOSED) {
                session.state = TcpSession.State.CLOSING
                val fin = synchronized(session.seqLock) {
                    val built = Packet.buildTcpPacket(
                        session.dstIp, session.srcIp,
                        session.dstPort, session.srcPort,
                        session.localSeq, session.remoteSeq,
                        TCP_FIN or TCP_ACK
                    )
                    session.localSeq = (session.localSeq + 1) and 0xFFFFFFFFL
                    built
                }
                writeTun(fin)
                session.finSentToApp = true
            }
            if (session.closeReason == "unknown") {
                session.closeReason = when {
                    session.appToProxyFrames == 0 && session.proxyToAppFrames == 0 -> "proxy_reader_eof_idle"
                    session.appToProxyFrames > 0 && session.proxyToAppFrames == 0 -> "proxy_reader_eof_no_upstream_data"
                    else -> "proxy_reader_eof"
                }
            }
            Log.i(
                TAG,
                "Session summary ($key): appToProxy=${session.appToProxyBytes}B/${session.appToProxyFrames}f, " +
                        "proxyToApp=${session.proxyToAppBytes}B/${session.proxyToAppFrames}f, reason=${session.closeReason}, domain=${session.targetDomainHint ?: session.sniffedDomain}"
            )
            session.state = TcpSession.State.CLOSED
            tunnel.close()
            runCatching { session.upstreamSocket?.close() }
            sessions.remove(key)
        }
    }

    private fun proxyReaderLoopRaw(key: String, session: TcpSession, upstream: Socket) {
        var frameCount = 0
        var totalBytes = 0L
        try {
            val input = upstream.getInputStream()
            val buf = ByteArray(16 * 1024)
            while (running.get() && session.state == TcpSession.State.ESTABLISHED) {
                val n = input.read(buf)
                if (n <= 0) break
                frameCount++
                totalBytes += n
                session.proxyToAppFrames += 1
                session.proxyToAppBytes += n
                session.lastProxyDataAtMs = System.currentTimeMillis()

                // ── Diagnostic: log first HTTP response line for first frame ──
                if (frameCount == 1) {
                    val preview = String(buf, 0, minOf(n, 300), Charsets.ISO_8859_1)
                    val firstLine = preview.substringBefore("\r\n")
                    Log.i(TAG, "First response ($key): \"$firstLine\" totalLen=$n domain=${session.targetDomainHint ?: session.sniffedDomain}")
                }

                var offset = 0
                var segInFrame = 0
                while (offset < n) {
                    val chunkSize = minOf(n - offset, session.peerMss)
                    val chunk = buf.copyOfRange(offset, offset + chunkSize)
                    val pkt = synchronized(session.seqLock) {
                        val built = Packet.buildTcpPacket(
                            session.dstIp, session.srcIp,
                            session.dstPort, session.srcPort,
                            session.localSeq, session.remoteSeq,
                            TCP_ACK or TCP_PSH,
                            payload = chunk
                        )
                        session.localSeq = (session.localSeq + chunkSize) and 0xFFFFFFFFL
                        built
                    }
                    writeTun(pkt)
                    segInFrame++
                    offset += chunkSize
                }

                // ── Diagnostic: log segments written for first 3 frames ──
                if (frameCount <= 3) {
                    Log.i(TAG, "TUN-write ($key): frame=$frameCount segs=$segInFrame bytes=$n totalBytes=$totalBytes localSeq=${session.localSeq}")
                }
            }
        } catch (e: Exception) {
            session.closeReason = "proxy_reader_exception:${e.javaClass.simpleName}"
            if (running.get()) logDebug("Proxy reader(raw) ended ($key): ${e.message}")
        } finally {
            Log.i(TAG, "Proxy reader done ($key): frames=$frameCount, bytes=$totalBytes")
            if (!session.finSentToApp && session.state != TcpSession.State.CLOSED) {
                session.state = TcpSession.State.CLOSING
                val fin = synchronized(session.seqLock) {
                    val built = Packet.buildTcpPacket(
                        session.dstIp, session.srcIp,
                        session.dstPort, session.srcPort,
                        session.localSeq, session.remoteSeq,
                        TCP_FIN or TCP_ACK
                    )
                    session.localSeq = (session.localSeq + 1) and 0xFFFFFFFFL
                    built
                }
                writeTun(fin)
                session.finSentToApp = true
            }
            if (session.closeReason == "unknown") {
                session.closeReason = when {
                    session.appToProxyFrames == 0 && session.proxyToAppFrames == 0 -> "proxy_reader_eof_idle"
                    session.appToProxyFrames > 0 && session.proxyToAppFrames == 0 -> "proxy_reader_eof_no_upstream_data"
                    else -> "proxy_reader_eof"
                }
            }
            Log.i(
                TAG,
                "Session summary ($key): appToProxy=${session.appToProxyBytes}B/${session.appToProxyFrames}f, " +
                        "proxyToApp=${session.proxyToAppBytes}B/${session.proxyToAppFrames}f, reason=${session.closeReason}, domain=${session.targetDomainHint ?: session.sniffedDomain}"
            )
            session.state = TcpSession.State.CLOSED
            runCatching { upstream.close() }
            sessions.remove(key)
        }
    }

    private fun connectViaLocalSocks(targetIp: Int, targetPort: Int, domainHint: String? = null): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", 2080), 5_000)
        socket.soTimeout = 10_000
        socket.tcpNoDelay = true

        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val authResp = ByteArray(2)
        readFully(input, authResp)
        if (authResp[0].toInt() != 0x05 || authResp[1].toInt() != 0x00) {
            throw IOException("SOCKS auth failed: ${authResp[0].toInt() and 0xFF}, ${authResp[1].toInt() and 0xFF}")
        }

        // Use SOCKS5 domain addressing (atyp=0x03) when domain hint is available;
        // this allows sing-box to see the domain for proper routing/sniffing.
        val req: ByteArray
        if (domainHint != null) {
            val domainBytes = domainHint.toByteArray(Charsets.US_ASCII)
            req = ByteArray(4 + 1 + domainBytes.size + 2)
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
            req[4] = domainBytes.size.toByte()
            System.arraycopy(domainBytes, 0, req, 5, domainBytes.size)
            req[5 + domainBytes.size] = ((targetPort ushr 8) and 0xFF).toByte()
            req[6 + domainBytes.size] = (targetPort and 0xFF).toByte()
        } else {
            val ipBytes = byteArrayOf(
                ((targetIp ushr 24) and 0xFF).toByte(),
                ((targetIp ushr 16) and 0xFF).toByte(),
                ((targetIp ushr 8) and 0xFF).toByte(),
                (targetIp and 0xFF).toByte()
            )
            req = byteArrayOf(
                0x05, 0x01, 0x00, 0x01,
                ipBytes[0], ipBytes[1], ipBytes[2], ipBytes[3],
                ((targetPort ushr 8) and 0xFF).toByte(),
                (targetPort and 0xFF).toByte()
            )
        }
        out.write(req)
        out.flush()

        val head = ByteArray(4)
        readFully(input, head)
        if (head[0].toInt() != 0x05 || head[1].toInt() != 0x00) {
            throw IOException("SOCKS connect failed: rep=${head[1].toInt() and 0xFF}")
        }

        when (head[3].toInt() and 0xFF) {
            0x01 -> readFully(input, ByteArray(4 + 2))
            0x03 -> {
                val len = ByteArray(1)
                readFully(input, len)
                readFully(input, ByteArray((len[0].toInt() and 0xFF) + 2))
            }
            0x04 -> readFully(input, ByteArray(16 + 2))
            else -> throw IOException("SOCKS atyp unsupported: ${head[3].toInt() and 0xFF}")
        }

        socket.soTimeout = 0
        return socket
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) throw IOException("Unexpected EOF")
            offset += n
        }
    }

    private fun launchSessionWatchdog(key: String, session: TcpSession) {
        executor?.submit {
            try {
                Thread.sleep(12_000)
                val current = sessions[key]
                if (current !== session) return@submit
                if (session.state != TcpSession.State.ESTABLISHED) return@submit

                val now = System.currentTimeMillis()
                val appIdleMs = if (session.lastAppDataAtMs == 0L) now - session.createdAtMs else now - session.lastAppDataAtMs
                val proxyIdleMs = if (session.lastProxyDataAtMs == 0L) now - session.createdAtMs else now - session.lastProxyDataAtMs
                Log.w(
                    TAG,
                    "Session stall ($key): appToProxy=${session.appToProxyBytes}B/${session.appToProxyFrames}f, " +
                            "proxyToApp=${session.proxyToAppBytes}B/${session.proxyToAppFrames}f, " +
                            "appIdleMs=$appIdleMs, proxyIdleMs=$proxyIdleMs, domain=${session.targetDomainHint ?: session.sniffedDomain}"
                )
            } catch (_: InterruptedException) {
            }
        }
    }

    // ────────────────────────────────────────────────────────
    //  UDP/DNS handling
    // ────────────────────────────────────────────────────────

    private fun onUdp(pkt: ByteArray, ip: Ipv4Header) {
        val udp = Packet.parseUdp(pkt, ip.headerLength) ?: return

        // For non-DNS UDP, send ICMP Port Unreachable so apps fall back to TCP.
        if (udp.destPort != 53) {
            sendIcmpPortUnreachable(ip, pkt)
            return
        }

        val payloadStart = ip.headerLength + 8
        val payloadLen = ip.totalLength - payloadStart
        if (payloadLen <= 0) return
        val dnsQuery = pkt.copyOfRange(payloadStart, payloadStart + payloadLen)

        executor?.submit { handleDns(ip, udp, dnsQuery) }
    }

    private fun sendIcmpPortUnreachable(ip: Ipv4Header, originalPacket: ByteArray) {
        try {
            val reply = Packet.buildIcmpPortUnreachable(
                srcIp = ip.destIp,
                dstIp = ip.sourceIp,
                originalPacket = originalPacket
            )
            writeTun(reply)
        } catch (_: Exception) {
        }
    }

    /**
     * Forward DNS query through a protected UDP socket and write the response
     * back to the TUN as a UDP packet.
     */
    private fun handleDns(ip: Ipv4Header, udp: UdpHeader, query: ByteArray) {
        try {
            val txIdHi = query.getOrNull(0)
            val txIdLo = query.getOrNull(1)
            val cacheKey = buildDnsCacheKey(query)
            if (cacheKey != null && txIdHi != null && txIdLo != null) {
                val now = System.currentTimeMillis()
                val cached = dnsCache[cacheKey]
                if (cached != null && cached.expiresAtMs > now && cached.response.size >= 2) {
                    val hit = cached.response.copyOf()
                    hit[0] = txIdHi
                    hit[1] = txIdLo
                    val replyPkt = Packet.buildUdpPacket(
                        ip.destIp, ip.sourceIp,
                        udp.destPort, udp.sourcePort,
                        hit
                    )
                    writeTun(replyPkt)
                    return
                }
            }

            // Singleflight: if same DNS question is already resolving, wait for it.
            if (cacheKey != null) {
                val existing = dnsInflight[cacheKey]
                if (existing != null) {
                    val shared = runCatching { existing.get(3, TimeUnit.SECONDS) }.getOrNull()
                    if (shared != null && txIdHi != null && txIdLo != null && shared.size >= 2) {
                        val hit = shared.copyOf()
                        hit[0] = txIdHi
                        hit[1] = txIdLo
                        val replyPkt = Packet.buildUdpPacket(
                            ip.destIp, ip.sourceIp,
                            udp.destPort, udp.sourcePort,
                            hit
                        )
                        writeTun(replyPkt)
                        return
                    }
                }
            }

            var inflightFuture: CompletableFuture<ByteArray?>? = null
            var isInflightOwner = false
            if (cacheKey != null) {
                val created = CompletableFuture<ByteArray?>()
                val prev = dnsInflight.putIfAbsent(cacheKey, created)
                if (prev == null) {
                    inflightFuture = created
                    isInflightOwner = true
                } else {
                    inflightFuture = prev
                }
            }

            // If we are not owner, wait for winner result and return.
            if (!isInflightOwner && inflightFuture != null) {
                val shared = runCatching { inflightFuture.get(3, TimeUnit.SECONDS) }.getOrNull()
                if (shared != null && txIdHi != null && txIdLo != null && shared.size >= 2) {
                    val hit = shared.copyOf()
                    hit[0] = txIdHi
                    hit[1] = txIdLo
                    val replyPkt = Packet.buildUdpPacket(
                        ip.destIp, ip.sourceIp,
                        udp.destPort, udp.sourcePort,
                        hit
                    )
                    writeTun(replyPkt)
                    return
                }
            }

            // Backpressure instead of drop: wait for a DNS slot.
            dnsSemaphore.acquireUninterruptibly()

            val domain = parseDnsQuestionDomain(query)
            val useRemoteDns = shouldUseRemoteDns(domain)

            val response: ByteArray? = try {
                val remoteDnsServers = listOf("8.8.8.8", "8.8.4.4", "1.1.1.1")
                val directDnsServers = listOf("223.5.5.5", "114.114.114.114", "8.8.8.8", "1.1.1.1")

                var resp: ByteArray? = null
                var resolvedVia = "none"

                if (useRemoteDns) {
                    for (serverIp in remoteDnsServers) {
                        resp = tryDnsThroughProxy(serverIp, query)
                        if (resp != null) {
                            resolvedVia = "remote:$serverIp"
                            break
                        }
                    }
                } else {
                    for (serverIp in directDnsServers) {
                        resp = tryDnsUdp(serverIp, query)
                        if (resp != null) {
                            resolvedVia = "direct-udp:$serverIp"
                            break
                        }
                    }
                    if (resp == null) {
                        for (serverIp in directDnsServers) {
                            resp = tryDnsTcp(serverIp, query)
                            if (resp != null) {
                                resolvedVia = "direct-tcp:$serverIp"
                                break
                            }
                        }
                    }

                    if (resp != null && hasPoisonedARecord(resp)) {
                        Log.w(TAG, "Direct DNS may be poisoned domain=$domain via=$resolvedVia firstA=${extractFirstARecord(resp)}")
                    }
                }

                if (resp == null) {
                    Log.w(TAG, "DNS failed domain=$domain mode=${if (useRemoteDns) "remote-only" else "direct-only"}")
                } else {
                    if (!domain.isNullOrBlank()) {
                        rememberDnsHints(domain, resp)
                    }
                    Log.i(TAG, "DNS ok domain=$domain via=$resolvedVia firstA=${extractFirstARecord(resp)}")
                }

                resp
            } finally {
                dnsSemaphore.release()
            }

            if (response == null) {
                if (isInflightOwner && cacheKey != null) {
                    dnsInflight.remove(cacheKey)?.complete(null)
                }
                Log.w(TAG, "DNS resolution failed on all upstreams for domain=$domain")
                return
            }

            if (cacheKey != null && response.size >= 2) {
                val normalized = response.copyOf()
                normalized[0] = 0
                normalized[1] = 0
                dnsCache[cacheKey] = DnsCacheEntry(
                    expiresAtMs = System.currentTimeMillis() + 10_000,
                    response = normalized
                )
                if (isInflightOwner) {
                    dnsInflight.remove(cacheKey)?.complete(normalized)
                }
            }

            // Build reply: dst→src, flipped ports
            val replyPkt = Packet.buildUdpPacket(
                ip.destIp, ip.sourceIp,
                udp.destPort, udp.sourcePort,
                response
            )
            writeTun(replyPkt)
        } catch (e: Exception) {
            val cacheKey = buildDnsCacheKey(query)
            if (cacheKey != null) {
                dnsInflight.remove(cacheKey)?.complete(null)
            }
            logDebug("DNS error: ${e.message}")
        }
    }

    private fun buildDnsCacheKey(query: ByteArray): String? {
        if (query.size < 12) return null
        val copy = query.copyOf()
        // Normalize TXID so retries with different IDs can hit cache.
        copy[0] = 0
        copy[1] = 0
        return copy.joinToString(separator = "") { b -> "%02x".format(b) }
    }

    private fun shouldUseRemoteDns(domain: String?): Boolean {
        if (routingMode == RoutingMode.Global) return true
        if (domain.isNullOrBlank()) return false
        val remote = GFWListRules.shouldRemoteResolveInBypass(domain)
        Log.i(TAG, "DNS routing: domain=$domain remoteDns=$remote mode=$routingMode")
        return remote
    }

    /**
     * Extract the Host header from an HTTP/1.x request.
     * Returns the hostname (without port) or null.
     */
    private fun parseHttpHost(data: ByteArray): String? {
        try {
            // Quick check: HTTP methods start with uppercase ASCII
            if (data.isEmpty() || data[0].toInt() and 0xFF < 0x41) return null
            val header = String(data, 0, minOf(data.size, 4096), Charsets.US_ASCII)
            // Find "Host:" header (case-insensitive)
            val idx = header.indexOf("\r\nHost:", ignoreCase = true)
            if (idx < 0) return null
            val afterColon = idx + 7 // length of "\r\nHost:"
            val endOfLine = header.indexOf("\r\n", afterColon)
            val hostValue = if (endOfLine > 0) {
                header.substring(afterColon, endOfLine).trim()
            } else {
                header.substring(afterColon).trim()
            }
            if (hostValue.isEmpty()) return null
            // Strip port if present (e.g., "example.com:8080")
            val colonIdx = hostValue.lastIndexOf(':')
            val host = if (colonIdx > 0 && hostValue.substring(colonIdx + 1).all { it.isDigit() }) {
                hostValue.substring(0, colonIdx)
            } else {
                hostValue
            }
            return host.lowercase().takeIf { '.' in it }
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Extract the Server Name Indication (SNI) from a TLS ClientHello.
     * Returns the hostname or null if not found / not TLS.
     */
    /**
     * Peek all available pending data segments and concatenate them into one
     * buffer for protocol sniffing. Does NOT remove data from the queue.
     * Returns null if no data is available.
     */
    private fun collectPendingForSniff(session: TcpSession): ByteArray? {
        val segments = session.pendingData.toArray()
        if (segments.isEmpty()) return null
        if (segments.size == 1) return segments[0] as ByteArray
        var total = 0
        for (s in segments) total += (s as ByteArray).size
        val buf = ByteArray(total)
        var off = 0
        for (s in segments) {
            val seg = s as ByteArray
            System.arraycopy(seg, 0, buf, off, seg.size)
            off += seg.size
        }
        return buf
    }

    /**
     * Extract the Server Name Indication (SNI) from a TLS ClientHello.
     * Works with partial TCP segments — does NOT require the full TLS record.
     * Returns the hostname or null if not found / not TLS.
     */
    private fun parseTlsSni(data: ByteArray): String? {
        try {
            val sz = data.size
            // TLS record: type=0x16 (Handshake), version (2 bytes), length (2 bytes)
            if (sz < 5) { Log.d(TAG, "SNI-dbg: sz<5 sz=$sz"); return null }
            if (data[0].toInt() and 0xFF != 0x16) { Log.d(TAG, "SNI-dbg: not-handshake byte0=${data[0].toInt() and 0xFF}"); return null }

            var off = 5
            // Handshake header: type=0x01 (ClientHello), 3 bytes length
            if (off >= sz) { Log.d(TAG, "SNI-dbg: truncated-before-hs off=$off sz=$sz"); return null }
            val hsType = data[off].toInt() and 0xFF
            if (hsType != 0x01) { Log.d(TAG, "SNI-dbg: hs-type=$hsType (not ClientHello)"); return null }
            off += 4  // skip type (1) + length (3)

            // ClientHello: version (2) + random (32) = 34 bytes
            off += 34
            if (off >= sz) { Log.d(TAG, "SNI-dbg: truncated-after-random off=$off sz=$sz"); return null }

            // Session ID length (1 byte) + session ID
            val sessionIdLen = data[off].toInt() and 0xFF
            off += 1 + sessionIdLen
            if (off + 2 > sz) { Log.d(TAG, "SNI-dbg: truncated-after-sessid off=$off sessionIdLen=$sessionIdLen sz=$sz"); return null }

            // Cipher suites length (2 bytes) + cipher suites
            val cipherLen = ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
            off += 2 + cipherLen
            if (off + 1 > sz) { Log.d(TAG, "SNI-dbg: truncated-after-ciphers off=$off cipherLen=$cipherLen sz=$sz"); return null }

            // Compression methods length (1 byte) + methods
            val compLen = data[off].toInt() and 0xFF
            off += 1 + compLen
            if (off + 2 > sz) { Log.d(TAG, "SNI-dbg: truncated-after-comp off=$off compLen=$compLen sz=$sz"); return null }

            // Extensions length (2 bytes)
            val extTotalLen = ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
            off += 2
            val extEnd = (off + extTotalLen).coerceAtMost(sz)  // clamp to available data
            Log.d(TAG, "SNI-dbg: extStart=$off extTotalLen=$extTotalLen extEnd=$extEnd sz=$sz sessId=$sessionIdLen cipher=$cipherLen comp=$compLen")

            // Walk extensions looking for SNI (type 0x0000)
            var extCount = 0
            while (off + 4 <= extEnd) {
                val extType = ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
                val extLen = ((data[off + 2].toInt() and 0xFF) shl 8) or (data[off + 3].toInt() and 0xFF)
                extCount++
                off += 4
                if (extType == 0x0000 && extLen > 0) { // SNI extension
                    // SNI list length (2 bytes)
                    if (off + 2 > sz) { Log.d(TAG, "SNI-dbg: SNI-ext truncated-listlen off=$off sz=$sz"); return null }
                    off += 2  // skip list length
                    // SNI entry: type (1 byte, 0=hostname) + name length (2 bytes) + name
                    if (off + 3 > sz) { Log.d(TAG, "SNI-dbg: SNI-ext truncated-entry off=$off sz=$sz"); return null }
                    val nameType = data[off].toInt() and 0xFF
                    val nameLen = ((data[off + 1].toInt() and 0xFF) shl 8) or (data[off + 2].toInt() and 0xFF)
                    off += 3
                    if (nameType == 0 && nameLen > 0 && off + nameLen <= sz) {
                        val name = String(data, off, nameLen, Charsets.US_ASCII).lowercase()
                        Log.d(TAG, "SNI-dbg: FOUND sni=$name after $extCount extensions")
                        return name
                    }
                    Log.d(TAG, "SNI-dbg: SNI-ext bad nameType=$nameType nameLen=$nameLen off=$off sz=$sz")
                    return null
                }
                off += extLen
            }
            Log.d(TAG, "SNI-dbg: no-SNI-ext after $extCount extensions off=$off extEnd=$extEnd")
        } catch (e: Exception) {
            Log.d(TAG, "SNI-dbg: exception ${e.message}")
        }
        return null
    }

    private fun parseDnsQuestionDomain(query: ByteArray): String? {
        if (query.size < 12) return null
        val qdCount = ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF)
        if (qdCount <= 0) return null

        var offset = 12
        val labels = mutableListOf<String>()
        while (offset < query.size) {
            val len = query[offset].toInt() and 0xFF
            offset += 1
            if (len == 0) break
            if (len and 0xC0 != 0 || len > 63) return null
            if (offset + len > query.size) return null
            val label = String(query, offset, len, Charsets.US_ASCII)
            labels.add(label)
            offset += len
        }
        if (labels.isEmpty()) return null
        return labels.joinToString(".").lowercase()
    }

    private fun hasPoisonedARecord(response: ByteArray): Boolean {
        if (response.size < 12) return false
        val qdCount = ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)
        val anCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        if (anCount <= 0) return false

        var off = 12
        repeat(qdCount) {
            off = skipDnsName(response, off)
            if (off < 0 || off + 4 > response.size) return false
            off += 4 // qtype + qclass
        }

        repeat(anCount) {
            off = skipDnsName(response, off)
            if (off < 0 || off + 10 > response.size) return false

            val type = ((response[off].toInt() and 0xFF) shl 8) or (response[off + 1].toInt() and 0xFF)
            val rdLen = ((response[off + 8].toInt() and 0xFF) shl 8) or (response[off + 9].toInt() and 0xFF)
            off += 10
            if (off + rdLen > response.size) return false

            if (type == 1 && rdLen == 4) { // A record
                val ip = ((response[off].toInt() and 0xFF) shl 24) or
                        ((response[off + 1].toInt() and 0xFF) shl 16) or
                        ((response[off + 2].toInt() and 0xFF) shl 8) or
                        (response[off + 3].toInt() and 0xFF)
                if (isNonRoutable(ip)) return true
            }
            off += rdLen
        }
        return false
    }

    private fun extractFirstARecord(response: ByteArray): String? {
        if (response.size < 12) return null
        val qdCount = ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)
        val anCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        if (anCount <= 0) return null

        var off = 12
        repeat(qdCount) {
            off = skipDnsName(response, off)
            if (off < 0 || off + 4 > response.size) return null
            off += 4
        }

        repeat(anCount) {
            off = skipDnsName(response, off)
            if (off < 0 || off + 10 > response.size) return null
            val type = ((response[off].toInt() and 0xFF) shl 8) or (response[off + 1].toInt() and 0xFF)
            val rdLen = ((response[off + 8].toInt() and 0xFF) shl 8) or (response[off + 9].toInt() and 0xFF)
            off += 10
            if (off + rdLen > response.size) return null
            if (type == 1 && rdLen == 4) {
                val ip = ((response[off].toInt() and 0xFF) shl 24) or
                        ((response[off + 1].toInt() and 0xFF) shl 16) or
                        ((response[off + 2].toInt() and 0xFF) shl 8) or
                        (response[off + 3].toInt() and 0xFF)
                return Packet.ipToString(ip)
            }
            off += rdLen
        }
        return null
    }

    private fun extractARecords(response: ByteArray): List<Int> {
        if (response.size < 12) return emptyList()
        val qdCount = ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)
        val anCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        if (anCount <= 0) return emptyList()

        var off = 12
        repeat(qdCount) {
            off = skipDnsName(response, off)
            if (off < 0 || off + 4 > response.size) return emptyList()
            off += 4
        }

        val out = ArrayList<Int>(4)
        repeat(anCount) {
            off = skipDnsName(response, off)
            if (off < 0 || off + 10 > response.size) return out
            val type = ((response[off].toInt() and 0xFF) shl 8) or (response[off + 1].toInt() and 0xFF)
            val rdLen = ((response[off + 8].toInt() and 0xFF) shl 8) or (response[off + 9].toInt() and 0xFF)
            off += 10
            if (off + rdLen > response.size) return out
            if (type == 1 && rdLen == 4) {
                val ip = ((response[off].toInt() and 0xFF) shl 24) or
                        ((response[off + 1].toInt() and 0xFF) shl 16) or
                        ((response[off + 2].toInt() and 0xFF) shl 8) or
                        (response[off + 3].toInt() and 0xFF)
                out.add(ip)
            }
            off += rdLen
        }
        return out
    }

    private fun rememberDnsHints(domain: String, response: ByteArray) {
        val now = System.currentTimeMillis()
        val expiresAt = now + 120_000
        val ips = extractARecords(response)
        for (ip in ips) {
            dnsIpHints[ip] = DnsIpHint(domain, expiresAt)
            Log.i(TAG, "DNS hint stored: ${Packet.ipToString(ip)} → $domain")
        }
        if (dnsIpHints.size > 4096) {
            val it = dnsIpHints.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (entry.value.expiresAtMs <= now) it.remove()
            }
        }
    }

    private fun lookupDnsHint(ip: Int): String? {
        val hint = dnsIpHints[ip] ?: return null
        val now = System.currentTimeMillis()
        if (hint.expiresAtMs <= now) {
            dnsIpHints.remove(ip)
            return null
        }
        return hint.domain
    }

    private fun skipDnsName(data: ByteArray, start: Int): Int {
        var off = start
        var jumps = 0
        while (off < data.size) {
            val len = data[off].toInt() and 0xFF
            if (len == 0) return off + 1
            if ((len and 0xC0) == 0xC0) {
                if (off + 1 >= data.size) return -1
                return off + 2
            }
            if ((len and 0xC0) != 0 || len > 63) return -1
            off += 1 + len
            jumps++
            if (jumps > 128) return -1
        }
        return -1
    }

    private fun tryDnsThroughProxy(dnsServerIp: String, query: ByteArray, requireProtect: Boolean = true): ByteArray? {
        val node = proxyNode ?: return null
        val resolvedAddr = proxyResolvedAddress ?: return null
        var channel: SocketChannel? = null
        var tunnel: ProxyTunnel? = null

        var result: ByteArray? = null
        try {
            channel = SocketChannel.open()
            val socket = channel.socket()
            if (requireProtect) {
                if (!protect(socket)) {
                    Log.w(TAG, "DNS-proxy protect() failed")
                    return null
                }
            }

            socket.connect(InetSocketAddress(resolvedAddr, node.port), 6_000)
            socket.soTimeout = 8_000
            socket.tcpNoDelay = true

            tunnel = ProxyTunnel(socket, node)
            val dnsIp = InetAddress.getByName(dnsServerIp).address
            if (dnsIp.size != 4) return null
            val dnsIpInt = ((dnsIp[0].toInt() and 0xFF) shl 24) or
                    ((dnsIp[1].toInt() and 0xFF) shl 16) or
                    ((dnsIp[2].toInt() and 0xFF) shl 8) or
                    (dnsIp[3].toInt() and 0xFF)
            tunnel.connect(dnsIpInt, 53)

            val tcpQuery = ByteArray(2 + query.size)
            tcpQuery[0] = ((query.size ushr 8) and 0xFF).toByte()
            tcpQuery[1] = (query.size and 0xFF).toByte()
            System.arraycopy(query, 0, tcpQuery, 2, query.size)
            tunnel.writeFrame(tcpQuery)

            val streamBuf = ByteArrayOutputStream(4096)
            while (true) {
                val frame = tunnel.readFrame() ?: break
                streamBuf.write(frame)
                val bytes = streamBuf.toByteArray()
                if (bytes.size >= 2) {
                    val respLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                    if (respLen > 0 && bytes.size >= 2 + respLen) {
                        result = bytes.copyOfRange(2, 2 + respLen)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            logDebug("tryDnsThroughProxy failed ($dnsServerIp): ${e.javaClass.simpleName}: ${e.message}")
            result = null
        } finally {
            runCatching { tunnel?.close() }
            runCatching { channel?.close() }
        }
        return result
    }

    private fun tryDnsUdp(serverIp: String, query: ByteArray): ByteArray? {
        var sock: DatagramSocket? = null
        return try {
            sock = DatagramSocket()
            if (!protect(sock)) {
                Log.w(TAG, "DNS UDP protect() failed for $serverIp")
                return null
            }
            sock.soTimeout = 1_500
            val server = InetAddress.getByName(serverIp)
            sock.send(DatagramPacket(query, query.size, server, 53))

            val recvBuf = ByteArray(2048)
            val resp = DatagramPacket(recvBuf, recvBuf.size)
            sock.receive(resp)
            recvBuf.copyOf(resp.length)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { sock?.close() }
        }
    }

    private fun tryDnsTcp(serverIp: String, query: ByteArray): ByteArray? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            if (!protect(socket)) {
                Log.w(TAG, "DNS TCP protect() failed for $serverIp")
                return null
            }
            socket.connect(InetSocketAddress(serverIp, 53), 2_000)
            socket.soTimeout = 2_000

            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            // DNS over TCP framing: 2-byte length prefix + payload
            val lenHi = ((query.size ushr 8) and 0xFF).toByte()
            val lenLo = (query.size and 0xFF).toByte()
            out.write(byteArrayOf(lenHi, lenLo))
            out.write(query)
            out.flush()

            val lenBuf = ByteArray(2)
            var lenOff = 0
            while (lenOff < 2) {
                val n = input.read(lenBuf, lenOff, 2 - lenOff)
                if (n < 0) return null
                lenOff += n
            }
            val respLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
            if (respLen <= 0 || respLen > 4096) return null

            val response = ByteArray(respLen)
            var off = 0
            while (off < respLen) {
                val n = input.read(response, off, respLen - off)
                if (n < 0) return null
                off += n
            }
            response
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    // ────────────────────────────────────────────────────────
    //  TUN write (synchronised, multiple threads write here)
    // ────────────────────────────────────────────────────────

    private fun writeTun(pkt: ByteArray) {
        synchronized(tunWriteLock) {
            try {
                tunOutput?.write(pkt)
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "TUN write: ${e.message}")
            }
        }
    }

    private fun sendAck(s: TcpSession) {
        val pkt = synchronized(s.seqLock) {
            Packet.buildTcpPacket(
                s.dstIp, s.srcIp,
                s.dstPort, s.srcPort,
                s.localSeq, s.remoteSeq,
                TCP_ACK
            )
        }
        writeTun(pkt)
    }

    private fun sendRst(s: TcpSession) {
        val pkt = synchronized(s.seqLock) {
            Packet.buildTcpPacket(
                s.dstIp, s.srcIp,
                s.dstPort, s.srcPort,
                s.localSeq, s.remoteSeq,
                TCP_RST or TCP_ACK
            )
        }
        writeTun(pkt)
    }

    // ────────────────────────────────────────────────────────
    //  Notification helpers
    // ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Simple VPN", NotificationManager.IMPORTANCE_LOW)
            ch.description = "VPN connection status"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Simple VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
