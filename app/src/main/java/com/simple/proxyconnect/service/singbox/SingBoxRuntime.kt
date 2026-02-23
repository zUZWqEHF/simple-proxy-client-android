package com.simple.proxyconnect.service.singbox

import android.content.Context
import android.util.Log
import com.simple.proxyconnect.model.ProxyNode
import com.simple.proxyconnect.model.RoutingMode
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class SingBoxRuntime(private val context: Context) {

    companion object {
        private const val TAG = "SingBoxRuntime"
        private const val LOCAL_SOCKS_PORT = 2080
    }

    private var process: Process? = null
    private var logThread: Thread? = null
    private val started = AtomicBoolean(false)

    private fun logCoreLine(line: String) {
        val normalized = line.uppercase()
        when {
            normalized.contains("FATAL") || normalized.contains("ERROR") -> Log.e(TAG, "[core] $line")
            normalized.contains("WARN") -> Log.w(TAG, "[core] $line")
            normalized.contains("SING-BOX STARTED") -> Log.i(TAG, "[core] $line")
        }
    }

    private fun resolveBinaryFile(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir?.let { File(it) }
        if (nativeDir != null && nativeDir.exists()) {
            val candidates = listOf(
                File(nativeDir, "libsing-box.so"),
                File(nativeDir, "libsingbox.so"),
                File(nativeDir, "sing-box")
            )
            for (candidate in candidates) {
                if (candidate.exists()) return candidate
            }
            Log.w(TAG, "nativeLibraryDir=${nativeDir.absolutePath}, candidates=${candidates.joinToString { it.name }}")
            runCatching {
                val present = nativeDir.list()?.joinToString(",") ?: "<empty>"
                Log.w(TAG, "nativeLibraryDir entries=$present")
            }
        }

        return null
    }

    private fun waitForSocksReady(process: Process, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                return false
            }
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", LOCAL_SOCKS_PORT), 300)
                }
                return true
            } catch (_: Exception) {
            }
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    fun start(node: ProxyNode, routingMode: RoutingMode): Boolean {
        if (started.get()) return true

        return try {
            val workDir = File(context.filesDir, "singbox")
            if (!workDir.exists()) {
                workDir.mkdirs()
            }

            val binaryFile = resolveBinaryFile()
            if (binaryFile == null) {
                Log.w(TAG, "sing-box binary not found in nativeLibraryDir; keep legacy datapath")
                return false
            }
            binaryFile.setExecutable(true)

            val configFile = File(workDir, "config.json")
            configFile.writeText(SingBoxConfigBuilder.build(node, routingMode, workDir = workDir))
            Log.i(TAG, "sing-box config written: ${configFile.absolutePath} for protocol=${node.protocolType}")

            val builder = ProcessBuilder(binaryFile.absolutePath, "run", "-c", configFile.absolutePath)
                .directory(workDir)
                .redirectErrorStream(true)
            builder.environment()["ENABLE_DEPRECATED_SPECIAL_OUTBOUNDS"] = "true"

            val startedProcess = builder.start()
            process = startedProcess

            logThread = Thread {
                runCatching {
                    startedProcess.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            logCoreLine(line)
                        }
                    }
                }
            }.apply {
                name = "singbox-log"
                isDaemon = true
                start()
            }

            Log.i(TAG, "sing-box process started")

            val ready = waitForSocksReady(startedProcess)
            if (!ready) {
                Log.e(TAG, "sing-box failed to become ready on 127.0.0.1:$LOCAL_SOCKS_PORT")
                stop()
                return false
            }

            started.set(true)
            Log.i(TAG, "sing-box socks ready on 127.0.0.1:$LOCAL_SOCKS_PORT")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Start sing-box failed: ${e.javaClass.simpleName}: ${e.message}")
            stop()
            false
        }
    }

    fun stop() {
        started.set(false)
        runCatching { process?.destroy() }
        runCatching { process?.destroyForcibly() }
        process = null
        runCatching { logThread?.interrupt() }
        logThread = null
    }

    fun isActive(): Boolean = started.get()
}
