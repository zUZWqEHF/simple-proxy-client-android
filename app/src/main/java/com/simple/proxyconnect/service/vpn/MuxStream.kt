package com.simple.proxyconnect.service.vpn

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A single multiplexed stream inside a [MuxTunnel].
 *
 * Lifecycle: created by [MuxTunnel.openStream] → [waitForConnect] → [read]/[write] → [close]
 */
class MuxStream(
    val id: Int,
    private val tunnel: MuxTunnel
) {
    private val connectLatch = CountDownLatch(1)
    @Volatile
    private var connectError: String? = null
    private val dataQueue = LinkedBlockingQueue<ByteArray>()
    private val finSent = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /**
     * Block until the server confirms (CONNECT_OK) or rejects (CONNECT_FAIL) the stream.
     */
    @Throws(IOException::class)
    fun waitForConnect() {
        if (!connectLatch.await(15, TimeUnit.SECONDS)) {
            throw IOException("Mux stream connect timed out")
        }
        connectError?.let { throw IOException("Mux connect failed: $it") }
    }

    internal fun onConnectOk() {
        connectLatch.countDown()
    }

    internal fun onConnectFail(error: String) {
        connectError = error
        connectLatch.countDown()
    }

    internal fun onData(data: ByteArray) {
        if (!closed.get()) {
            dataQueue.offer(data)
        }
    }

    internal fun onFin() {
        dataQueue.offer(ByteArray(0)) // sentinel to unblock readers
    }

    internal fun onConnectionLost() {
        closed.set(true)
        if (connectError == null) connectError = "Connection lost"
        connectLatch.countDown()
        dataQueue.offer(ByteArray(0)) // unblock readers
    }

    /**
     * Read next data chunk. Blocks until data is available.
     * Returns null when the remote side has sent FIN or the connection dropped.
     */
    fun read(): ByteArray? {
        if (closed.get() && dataQueue.isEmpty()) return null
        val data = try {
            dataQueue.take()
        } catch (_: InterruptedException) {
            return null
        }
        if (data.isEmpty()) return null
        return data
    }

    /**
     * Write data to the remote side through the mux tunnel.
     */
    @Throws(IOException::class)
    fun write(data: ByteArray) {
        if (closed.get()) throw IOException("Stream closed")
        tunnel.sendMuxFrame(MuxTunnel.CMD_DATA, id, data)
    }

    /**
     * Signal that this side is done writing (half-close).
     */
    fun closeSend() {
        if (!finSent.getAndSet(true)) {
            runCatching { tunnel.sendMuxFrame(MuxTunnel.CMD_FIN, id) }
        }
    }

    /**
     * Fully close this stream and remove it from the tunnel.
     */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            closeSend()
            tunnel.removeStream(id)
        }
    }
}
