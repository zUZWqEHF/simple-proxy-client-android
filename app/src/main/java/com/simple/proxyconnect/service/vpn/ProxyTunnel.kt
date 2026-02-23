package com.simple.proxyconnect.service.vpn

import com.simple.proxyconnect.model.ProxyNode
import com.simple.proxyconnect.service.CryptoService
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Manages a single encrypted proxy connection.
 * Supports SimpleProtocol.
 *
 * Lifecycle: construct → connect() → writeFrame()/readFrame() → close()
 */
class ProxyTunnel(
    private val socket: Socket,
    private val node: ProxyNode
) {
    private val output: OutputStream = socket.getOutputStream()
    private val input: InputStream = socket.getInputStream()

    // Encryption state — initialised during connect()
    private var sendKey: ByteArray? = null
    private var recvKey: ByteArray? = null
    private var sendPrefix: ByteArray? = null
    private var recvPrefix: ByteArray? = null
    private var sendCounter = 0L
    private var recvCounter = 0L

    // ────────────────────────────────────────────────────────
    //  Public API
    // ────────────────────────────────────────────────────────

    /**
     * Perform protocol-specific handshake and send target address.
     * Must be called before readFrame()/writeFrame().
     */
    fun connect(targetIp: Int, targetPort: Int) {
        connectSimpleProtocol(targetIp, targetPort)
    }

    /**
     * Write one encrypted frame to the proxy.
     * Frame format: [enc_length (2+16)] [enc_payload (N+16)]
     */
    fun writeFrame(data: ByteArray) {
        val key = sendKey ?: throw IOException("Tunnel not connected")
        val prefix = sendPrefix!!

        // Encrypt 2-byte length
        val lenBuf = ByteArray(2)
        lenBuf[0] = ((data.size ushr 8) and 0xFF).toByte()
        lenBuf[1] = (data.size and 0xFF).toByte()
        val lenNonce = CryptoService.makeNonce(sendCounter * 2, prefix)
        val encLen = CryptoService.encryptAESGCM(lenBuf, key, lenNonce)

        // Encrypt payload
        val dataNonce = CryptoService.makeNonce(sendCounter * 2 + 1, prefix)
        val encData = CryptoService.encryptAESGCM(data, key, dataNonce)

        // Write atomically
        val buf = ByteArray(encLen.size + encData.size)
        System.arraycopy(encLen, 0, buf, 0, encLen.size)
        System.arraycopy(encData, 0, buf, encLen.size, encData.size)
        output.write(buf)
        output.flush()

        sendCounter++
    }

    /**
     * Read one decrypted frame from the proxy (blocking).
     * Returns null on clean EOF / zero-length frame.
     */
    fun readFrame(): ByteArray? {
        return decryptFrame()
    }

    fun close() {
        runCatching { socket.close() }
    }

    fun shutdownOutput() {
        runCatching { socket.shutdownOutput() }
    }

    // ────────────────────────────────────────────────────────
    //  SimpleProtocol handshake
    // ────────────────────────────────────────────────────────

    private fun connectSimpleProtocol(targetIp: Int, targetPort: Int) {
        val psk = CryptoService.hexToBytes(node.spKey!!)!!

        // Build handshake: [nonce 32] [timestamp 8] [HMAC 32] [padLen 2] [padding]
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

        // Send handshake
        val handshake = ByteArray(32 + 8 + 32 + 2 + padLen)
        System.arraycopy(nonce, 0, handshake, 0, 32)
        System.arraycopy(tsBytes, 0, handshake, 32, 8)
        System.arraycopy(hmacTag, 0, handshake, 40, 32)
        System.arraycopy(padLenBytes, 0, handshake, 72, 2)
        System.arraycopy(padding, 0, handshake, 74, padLen)
        output.write(handshake)
        output.flush()

        // Derive directional keys
        val prefix = nonce.copyOfRange(0, 4)
        sendKey = CryptoService.hkdfSHA256(psk, nonce, "simple-c2s".toByteArray(), 32)
        recvKey = CryptoService.hkdfSHA256(psk, nonce, "simple-s2c".toByteArray(), 32)
        sendPrefix = prefix
        recvPrefix = prefix
        sendCounter = 0
        recvCounter = 0

        // Send target address
        val target = Packet.buildTargetAddress(targetIp, targetPort)
        writeFrame(target)
    }

    // ────────────────────────────────────────────────────────
    //  Internal helpers
    // ────────────────────────────────────────────────────────

    private fun decryptFrame(): ByteArray? {
        val key = recvKey ?: throw IOException("recv key not ready")
        val prefix = recvPrefix!!
        val tagSize = 16

        // Read encrypted length (2 + 16 = 18 bytes)
        val encLen = ByteArray(2 + tagSize)
        if (!readFullAllowEofAtStart(encLen)) return null
        val lenNonce = CryptoService.makeNonce(recvCounter * 2, prefix)
        val lenBuf = CryptoService.decryptAESGCM(encLen, key, lenNonce)
        val size = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
        if (size == 0) return null

        // Read encrypted payload (size + 16 bytes)
        val encData = ByteArray(size + tagSize)
        readFull(encData)
        val dataNonce = CryptoService.makeNonce(recvCounter * 2 + 1, prefix)
        val plaintext = CryptoService.decryptAESGCM(encData, key, dataNonce)

        recvCounter++
        return plaintext
    }

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
