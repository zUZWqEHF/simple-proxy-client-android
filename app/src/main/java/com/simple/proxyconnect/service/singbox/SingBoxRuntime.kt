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
        if (line.isBlank()) return
        val normalized = line.uppercase()
        when {
            normalized.contains("FATAL") || normalized.contains("ERROR") -> Log.e(TAG, "[core] $line")
            normalized.contains("WARN") -> Log.w(TAG, "[core] $line")
            normalized.contains("SING-BOX STARTED") || normalized.contains("LISTENING AT") ->
                Log.i(TAG, "[core] $line")
            // INFO/DEBUG ignored — sing-box is verbose and the
            // SOCKS-listener-bound timing is tracked separately.
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

    // sing-box startup on cold-cache emulators (after SELinux denials, /cgroup
    // probes etc.) easily exceeds the original 5 s deadline; bump to 20 s.
    // The check polls every 120 ms so the happy path still resolves in well
    // under a second once the SOCKS listener binds.
    private fun waitForSocksReady(process: Process, timeoutMs: Long = 20_000): Boolean {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        var nextLog = start + 1_000
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                Log.e(TAG, "sing-box exited before SOCKS ready")
                return false
            }
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", LOCAL_SOCKS_PORT), 300)
                }
                Log.i(TAG, "SOCKS listener bound after ${System.currentTimeMillis() - start} ms")
                return true
            } catch (_: Exception) {
            }
            val now = System.currentTimeMillis()
            if (now >= nextLog) {
                Log.i(TAG, "waiting for sing-box SOCKS listener… elapsed=${now - start} ms")
                nextLog = now + 2_000
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

            // The first ProcessBuilder fork on a freshly-installed APK can
            // hit a kernel/SELinux state where sing-box's mixed listener
            // never binds within the readiness window. Killing that
            // subprocess and retrying always recovers within a second on
            // the second attempt, so we wrap the spawn in a small retry
            // loop instead of giving up immediately.
            val maxAttempts = 3
            var ready = false
            var lastProcess: Process? = null
            for (attempt in 1..maxAttempts) {
                val attemptProcess = builder.start()
                lastProcess = attemptProcess
                process = attemptProcess

                logThread = Thread {
                    runCatching {
                        attemptProcess.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                logCoreLine(line)
                            }
                        }
                    }
                }.apply {
                    name = "singbox-log-$attempt"
                    isDaemon = true
                    start()
                }

                Log.i(TAG, "sing-box process started (attempt $attempt/$maxAttempts)")
                ready = waitForSocksReady(attemptProcess, timeoutMs = 8_000)
                if (ready) break

                Log.w(TAG, "sing-box not ready after attempt $attempt; killing and retrying")
                runCatching { attemptProcess.destroy() }
                runCatching { attemptProcess.destroyForcibly() }
                runCatching { logThread?.interrupt() }
                logThread = null
                Thread.sleep(500)
            }

            if (!ready) {
                Log.e(TAG, "sing-box failed to become ready on 127.0.0.1:$LOCAL_SOCKS_PORT after $maxAttempts attempts")
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
