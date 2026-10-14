package com.melodia.app.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled acestep.cpp server (lib/arm64-v8a/libaceserver.so, an executable)
 * as a child process that only listens on 127.0.0.1. The app talks to it over HTTP,
 * exactly like it talks to a PC or to Termux.
 */
class EmbeddedServer(
    private val context: Context,
    private val models: ModelManager,
    private val http: OkHttpClient,
) {
    val binary: File = File(context.applicationInfo.nativeLibraryDir, "libaceserver.so")
    val logFile: File = File(File(context.filesDir, "logs").apply { mkdirs() }, "engine.log")

    private val lock = Mutex()
    private var process: Process? = null
    private var port: Int = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idleJob: Job? = null
    private val users = java.util.concurrent.atomic.AtomicInteger(0)

    /** Marks the engine as in use so the idle timer does not stop it mid-song. */
    fun hold() {
        users.incrementAndGet()
        idleJob?.cancel()
    }

    /** Gives up a [hold]; the engine stops itself after a quiet period once nobody uses it. */
    fun release() {
        if (users.decrementAndGet() <= 0) {
            users.set(0)
            scheduleIdleStop()
        }
    }

    fun isBundled(): Boolean = binary.isFile

    fun isRunning(): Boolean = process?.isAlive == true

    /** Starts the server if needed and returns its base URL. */
    suspend fun ensureRunning(onStage: (String) -> Unit = {}): String = lock.withLock {
        idleJob?.cancel()
        val running = process
        if (running != null && running.isAlive) {
            val url = "http://127.0.0.1:$port"
            if (AceClient(url, http).health()) return@withLock url
        }
        stopLocked()
        if (!isBundled()) throw EngineException("This build has no on-phone engine. Use the Termux or PC option.")
        if (!models.phoneModelsReady()) throw EngineException("Download the Phone AI models first (Settings, Phone AI).")

        onStage("Starting the singer")
        port = freePort()
        withContext(Dispatchers.IO) {
            if (logFile.length() > 2_000_000) logFile.delete()
            logFile.appendText("\n==== engine start ${java.util.Date()} ====\n")
            val tmp = File(context.cacheDir, "engine").apply { mkdirs() }
            val command = listOf(
                binary.absolutePath,
                "--models", models.modelsDir.absolutePath,
                "--adapters", models.adaptersDir.absolutePath,
                "--host", "127.0.0.1",
                "--port", port.toString(),
            )
            val builder = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            builder.environment()["HOME"] = context.filesDir.absolutePath
            builder.environment()["TMPDIR"] = tmp.absolutePath
            process = builder.start()
        }

        val url = "http://127.0.0.1:$port"
        val client = AceClient(url, http)
        // Scanning the model files takes a few seconds; loading happens per request.
        repeat(240) {
            delay(500)
            val p = process
            if (p == null || !p.isAlive) {
                throw EngineException("The on-phone engine stopped while starting.\n" + tail(12))
            }
            if (client.health()) return@withLock url
        }
        stopLocked()
        throw EngineException("The on-phone engine did not start in time.\n" + tail(12))
    }

    /** Stops the engine after a quiet period so its memory is given back. */
    fun scheduleIdleStop(afterMs: Long = 120_000) {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(afterMs)
            lock.withLock { if (users.get() == 0) stopLocked() }
        }
    }

    suspend fun stop() = lock.withLock { stopLocked() }

    private fun stopLocked() {
        val p = process ?: return
        process = null
        runCatching {
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    fun tail(lines: Int = 200): String = runCatching {
        if (!logFile.exists()) "" else logFile.readLines().takeLast(lines).joinToString("\n")
    }.getOrDefault("")

    private fun freePort(): Int = runCatching { ServerSocket(0).use { it.localPort } }.getOrDefault(18085)
}

class EngineException(message: String) : Exception(message)
