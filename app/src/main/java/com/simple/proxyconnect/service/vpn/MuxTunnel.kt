package com.simple.proxyconnect.service.vpn

import android.util.Log
import com.simple.proxyconnect.model.ProxyNode
import com.simple.proxyconnect.service.CryptoService
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Multiplexed tunnel over a single encrypted TCP connection.
 *
 * After the standard SimpleProtocol handshake, a mux-init frame `[0x00, 0x01, 0x00, 0x00]`
 * is sent to switch the server into mux mode.
 *
 * All subsequent frames carry: `[cmd 1B] [streamID 4B BE] [payload...]`
 *
 * Lifecycle: construct → [connect] → [openStream]/[close]
 */
class MuxTunnel(
    private val socket: Socket,
    private val node: ProxyNode
) {
    companion object {
        const val TAG = "MuxTunnel"
        const val CMD_CONNECT: Byte      = 0x01
        const val CMD_CONNECT_OK: Byte   = 0x02
        const val CMD_CONNECT_FAIL: Byte = 0x03
        const val CMD_DATA: Byte         = 0x04
        const val CMD_FIN: Byte          = 0x05
    }

    private val output: OutputStream = socket.getOutputStream()
    private val input: InputStream = socket.getInputStream()
    private val writeLock = ReentrantLock()

    // Encryption state
    private var sendKey: ByteArray? = null
    private var recvKey: ByteArray? = null
    private var sendPrefix: ByteArray? = null
    private var recvPrefix: ByteArray? = null
    private var sendCounter = 0L
    private var recvCounter = 0L

    // Stream management
    private val streams = ConcurrentHashMap<Int, MuxStream>()
    private val nextStreamId = AtomicInteger(1)
    private val closed = AtomicBoolean(false)
    private var readerThread: Thread? = null

    // ────────────────────────────────────────────────────────
    //  Public API
    // ────────────────────────────────────────────────────────

    /**
     * Perform handshake and enter mux mode. Call once after construction.
     */
    fun connect() {
        performHandshake()
        // Send mux init frame: [0x00=mux] [version=1] [reserved 2B]
        writeFrameInternal(byteArrayOf(0x00, 0x01, 0x00, 0x00))
        startReader()
    }

    /**
     * Open a new multiplexed stream to the given target address.
     * Blocks until the server confirms.
     */
    @Throws(IOException::class)
    fun openStream(targetIp: Int, targetPort: Int): MuxStream {
        if (closed.get()) throw IOException("Mux tunnel is closed")
        val streamId = nextStreamId.getAndIncrement()
        val stream = MuxStream(streamId, this)
        streams[streamId] = stream

        val target = Packet.buildTargetAddress(targetIp, targetPort)
        sendMuxFrame(CMD_CONNECT, streamId, target)

        stream.waitForConnect()
        return stream
    }

    /**
     * Send a mux frame. Thread-safe.
     */
    internal fun sendMuxFrame(cmd: Byte, streamId: Int, payload: ByteArray? = null) {
        val payloadSize = payload?.size ?: 0
        val data = ByteArray(5 + payloadSize)
        data[0] = cmd
        data[1] = ((streamId ushr 24) and 0xFF).toByte()
        data[2] = ((streamId ushr 16) and 0xFF).toByte()
        data[3] = ((streamId ushr 8) and 0xFF).toByte()
        data[4] = (streamId and 0xFF).toByte()
        payload?.let { System.arraycopy(it, 0, data, 5, it.size) }

        writeLock.withLock {
            writeFrameInternal(data)
        }
    }

    fun isClosed(): Boolean = closed.get()

    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
            runCatching { readerThread?.interrupt() }
            for (stream in streams.values) {
                stream.onConnectionLost()
            }
            streams.clear()
        }
    }

    internal fun removeStream(streamId: Int) {
        streams.remove(streamId)
    }

    // ────────────────────────────────────────────────────────
    //  Handshake (same as ProxyTunnel, without target frame)
    // ────────────────────────────────────────────────────────

    private fun performHandshake() {
        val psk = CryptoService.hexToBytes(node.spKey!!)!!
        val nonce = CryptoService.randomBytes(32)
        val timestamp = System.currentTimeMillis() / 1000
        val tsBytes = ByteArray(8)
        ByteBuffer.wrap(tsBytes).putLong(timestamp)
        val hmacTag = CryptoService.hmacSHA256(psk, nonce + tsBytes)

        val padLen = 32 + (CryptoService.randomBytes(1)[0].toInt() and 0xFF) % 225
        val padding = CryptoService.randomBytes(padLen)
        val padLenBytes = ByteArray(2)
        padLenBytes[0] = ((padLen ushr 8) and 0xFF).toByte()
        padLenBytes[1] = (padLen and 0xFF).toByte()

        val handshake = ByteArray(32 + 8 + 32 + 2 + padLen)
        System.arraycopy(nonce, 0, handshake, 0, 32)
        System.arraycopy(tsBytes, 0, handshake, 32, 8)
        System.arraycopy(hmacTag, 0, handshake, 40, 32)
        System.arraycopy(padLenBytes, 0, handshake, 72, 2)
        System.arraycopy(padding, 0, handshake, 74, padLen)
        output.write(handshake)
        output.flush()

        val prefix = nonce.copyOfRange(0, 4)
        sendKey = CryptoService.hkdfSHA256(psk, nonce, "simple-c2s".toByteArray(), 32)
        recvKey = CryptoService.hkdfSHA256(psk, nonce, "simple-s2c".toByteArray(), 32)
        sendPrefix = prefix
        recvPrefix = prefix
        sendCounter = 0
        recvCounter = 0
    }

    // ────────────────────────────────────────────────────────
    //  Frame encryption / decryption
    // ────────────────────────────────────────────────────────

    /** Write one encrypted frame. Caller must hold [writeLock] (or be in [connect]). */
    private fun writeFrameInternal(data: ByteArray) {
        val key = sendKey ?: throw IOException("Tunnel not connected")
        val prefix = sendPrefix!!

        val lenBuf = ByteArray(2)
        lenBuf[0] = ((data.size ushr 8) and 0xFF).toByte()
        lenBuf[1] = (data.size and 0xFF).toByte()
        val lenNonce = CryptoService.makeNonce(sendCounter * 2, prefix)
        val encLen = CryptoService.encryptAESGCM(lenBuf, key, lenNonce)

        val dataNonce = CryptoService.makeNonce(sendCounter * 2 + 1, prefix)
        val encData = CryptoService.encryptAESGCM(data, key, dataNonce)

        val buf = ByteArray(encLen.size + encData.size)
        System.arraycopy(encLen, 0, buf, 0, encLen.size)
        System.arraycopy(encData, 0, buf, encLen.size, encData.size)
        output.write(buf)
        output.flush()

        sendCounter++
    }

    /** Read one decrypted frame. Only called from the reader thread. */
    private fun readFrameInternal(): ByteArray? {
        val key = recvKey ?: throw IOException("recv key not ready")
        val prefix = recvPrefix!!
        val tagSize = 16

        val encLen = ByteArray(2 + tagSize)
        if (!readFullAllowEofAtStart(encLen)) return null
        val lenNonce = CryptoService.makeNonce(recvCounter * 2, prefix)
        val lenBuf = CryptoService.decryptAESGCM(encLen, key, lenNonce)
        val size = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
        if (size == 0) return null

        val encData = ByteArray(size + tagSize)
        readFull(encData)
        val dataNonce = CryptoService.makeNonce(recvCounter * 2 + 1, prefix)
        val plaintext = CryptoService.decryptAESGCM(encData, key, dataNonce)

        recvCounter++
        return plaintext
    }

    // ────────────────────────────────────────────────────────
    //  Background reader (demultiplexer)
    // ────────────────────────────────────────────────────────

    private fun startReader() {
        readerThread = Thread {
            try {
                while (!closed.get()) {
                    val frame = readFrameInternal() ?: break
                    if (frame.size < 5) continue

                    val cmd = frame[0]
                    val streamId = ((frame[1].toInt() and 0xFF) shl 24) or
                        ((frame[2].toInt() and 0xFF) shl 16) or
                        ((frame[3].toInt() and 0xFF) shl 8) or
                        (frame[4].toInt() and 0xFF)
                    val payload = if (frame.size > 5) frame.copyOfRange(5, frame.size) else null

                    val stream = streams[streamId] ?: continue

                    when (cmd) {
                        CMD_CONNECT_OK -> stream.onConnectOk()
                        CMD_CONNECT_FAIL -> stream.onConnectFail(
                            payload?.let { String(it, Charsets.UTF_8) } ?: "unknown error"
                        )
                        CMD_DATA -> payload?.let { stream.onData(it) }
                        CMD_FIN -> stream.onFin()
                    }
                }
            } catch (e: Exception) {
                if (!closed.get()) {
                    Log.w(TAG, "Mux reader ended: ${e.message}")
                }
            } finally {
                // Signal all streams that the connection is lost
                for (stream in streams.values) {
                    stream.onConnectionLost()
                }
                closed.set(true)
            }
        }.apply {
            name = "mux-tunnel-reader"
            isDaemon = true
            start()
        }
    }

    // ────────────────────────────────────────────────────────
    //  IO helpers
    // ────────────────────────────────────────────────────────

    private fun readFull(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("Unexpected EOF from proxy")
            off += n
        }
    }

    private fun readFullAllowEofAtStart(buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) {
                if (off == 0) return false
                throw IOException("Unexpected EOF from proxy")
            }
            off += n
        }
        return true
    }
}
