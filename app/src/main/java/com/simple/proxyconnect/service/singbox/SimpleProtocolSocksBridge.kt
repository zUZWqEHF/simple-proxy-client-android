package com.simple.proxyconnect.service.singbox

import android.util.Log
import com.simple.proxyconnect.model.ProxyNode
import com.simple.proxyconnect.service.vpn.MuxStream
import com.simple.proxyconnect.service.vpn.MuxTunnel
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SimpleProtocolSocksBridge(
    private val node: ProxyNode,
    private val protectSocket: (Socket) -> Boolean,
) {
    companion object {
        private const val TAG = "SimpleProtocolBridge"
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workerPool = Executors.newCachedThreadPool()

    @Volatile
    private var muxTunnel: MuxTunnel? = null
    private val muxLock = Any()

    fun start(port: Int = 16080): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("127.0.0.1", port))
            serverSocket = server

            acceptThread = Thread {
                while (running.get()) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    workerPool.submit { handleClient(client) }
                }
            }.apply {
                name = "simpleprotocol-socks-accept"
                isDaemon = true
                start()
            }

            Log.i(TAG, "SimpleProtocol SOCKS bridge (mux) started at 127.0.0.1:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Start bridge failed: ${e.javaClass.simpleName}: ${e.message}")
            stop()
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { acceptThread?.interrupt() }
        acceptThread = null
        workerPool.shutdownNow()
        synchronized(muxLock) {
            muxTunnel?.close()
            muxTunnel = null
        }
    }

    fun isActive(): Boolean = running.get()

    private fun getOrCreateMuxTunnel(): MuxTunnel {
        muxTunnel?.let { if (!it.isClosed()) return it }
        synchronized(muxLock) {
            muxTunnel?.let { if (!it.isClosed()) return it }

            val socket = Socket()
            socket.bind(InetSocketAddress(0))
            if (!protectSocket(socket)) {
                socket.close()
                throw IOException("protect() failed for mux tunnel socket")
            }
            socket.connect(InetSocketAddress(node.host, node.port), 10_000)
            socket.tcpNoDelay = true
            socket.soTimeout = 0

            val tunnel = MuxTunnel(socket, node)
            tunnel.connect()
            muxTunnel = tunnel
            Log.i(TAG, "Mux tunnel established to ${node.host}:${node.port}")
            return tunnel
        }
    }

    private fun handleClient(client: Socket) {
        var stream: MuxStream? = null
        var uploadThread: Thread? = null

        try {
            client.soTimeout = 15_000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // SOCKS5 greeting
            val greeting = ByteArray(2)
            readFully(input, greeting)
            val ver = greeting[0].toInt() and 0xFF
            val methodsCount = greeting[1].toInt() and 0xFF
            if (ver != 0x05 || methodsCount <= 0) throw IOException("Unsupported SOCKS greeting")
            readFully(input, ByteArray(methodsCount))
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // SOCKS5 connect request
            val reqHead = ByteArray(4)
            readFully(input, reqHead)
            if ((reqHead[0].toInt() and 0xFF) != 0x05) throw IOException("Unsupported SOCKS version")
            if ((reqHead[1].toInt() and 0xFF) != 0x01) throw IOException("Only CONNECT is supported")

            val atyp = reqHead[3].toInt() and 0xFF
            val targetHost = when (atyp) {
                0x01 -> {
                    val raw = ByteArray(4)
                    readFully(input, raw)
                    InetAddress.getByAddress(raw).hostAddress ?: throw IOException("Bad IPv4")
                }
                0x03 -> {
                    val lenBuf = ByteArray(1)
                    readFully(input, lenBuf)
                    val len = lenBuf[0].toInt() and 0xFF
                    val raw = ByteArray(len)
                    readFully(input, raw)
                    String(raw, Charsets.US_ASCII)
                }
                else -> throw IOException("SOCKS atyp unsupported: $atyp")
            }

            val portBuf = ByteArray(2)
            readFully(input, portBuf)
            val targetPort = ((portBuf[0].toInt() and 0xFF) shl 8) or (portBuf[1].toInt() and 0xFF)

            val targetAddr = InetAddress.getByName(targetHost)
            val ipBytes = targetAddr.address
            if (ipBytes.size != 4) throw IOException("IPv6 target not supported in bridge")
            val targetIp = ((ipBytes[0].toInt() and 0xFF) shl 24) or
                ((ipBytes[1].toInt() and 0xFF) shl 16) or
                ((ipBytes[2].toInt() and 0xFF) shl 8) or
                (ipBytes[3].toInt() and 0xFF)

            val tunnel = getOrCreateMuxTunnel()
            stream = tunnel.openStream(targetIp, targetPort)

            // SOCKS success reply
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            output.flush()
            client.soTimeout = 0

            val stableStream = stream
            uploadThread = Thread {
                val buf = ByteArray(16 * 1024)
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        stableStream.write(buf.copyOf(n))
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { stableStream.closeSend() }
                }
            }.apply {
                name = "mux-socks-upload"
                isDaemon = true
                start()
            }

            while (running.get()) {
                val data = stream.read() ?: break
                output.write(data)
                output.flush()
            }
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            val normalClose = message.contains("Unexpected EOF") ||
                message.contains("Socket closed") ||
                message.contains("Broken pipe") ||
                message.contains("Stream closed") ||
                message.contains("Connection lost") ||
                message.contains("Mux tunnel is closed")
            if (!normalClose) {
                Log.w(TAG, "Bridge client closed: ${e.javaClass.simpleName}: $message")
            }
        } finally {
            runCatching { uploadThread?.interrupt() }
            runCatching { client.close() }
            runCatching { stream?.close() }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) throw IOException("Unexpected EOF")
            offset += n
        }
    }
}
