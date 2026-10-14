package com.melodia.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.MultipartReader
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException

/**
 * Client for the acestep.cpp HTTP server (same API on the phone, in Termux and on a PC).
 * Jobs are asynchronous: submit, poll /job?id=, then fetch /job?id=&result=1.
 */
class AceClient(baseUrl: String, private val http: OkHttpClient) {

    val baseUrl: String = baseUrl.trim().trimEnd('/')

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    data class Props(val dit: List<String>, val lm: List<String>, val adapters: List<String>, val version: String)

    data class SynthOutput(val audio: File, val latent: File?)

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$baseUrl/health").build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun props(): Props = withContext(Dispatchers.IO) {
        val body = call(Request.Builder().url("$baseUrl/props").build())
        val root = json.parseToJsonElement(body) as? JsonObject ?: throw EngineException("Unexpected reply from the engine.")
        val models = root["models"] as? JsonObject
        fun names(key: String): List<String> =
            (models?.get(key) as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val adapters = (root["adapters"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        Props(
            dit = names("dit"),
            lm = names("lm"),
            adapters = adapters,
            version = root["version"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    /** POST /lm or /synth with a JSON request. Returns the job id. */
    suspend fun submit(path: String, request: JsonElement): String = withContext(Dispatchers.IO) {
        val body = request.toString().toRequestBody(jsonType)
        jobId(call(Request.Builder().url("$baseUrl$path").post(body).build()))
    }

    /** POST /synth as multipart, used when a voice reference recording is attached. */
    suspend fun submitSynthWithReference(request: JsonElement, refAudio: File): String = withContext(Dispatchers.IO) {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("request", request.toString())
            .addFormDataPart("ref_audio", refAudio.name, refAudio.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        jobId(call(Request.Builder().url("$baseUrl/synth").post(form).build()))
    }

    suspend fun status(id: String): String = withContext(Dispatchers.IO) {
        val body = call(Request.Builder().url("$baseUrl/job?id=$id").build())
        val obj = json.parseToJsonElement(body) as? JsonObject
        obj?.get("status")?.jsonPrimitive?.contentOrNull ?: "unknown"
    }

    suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$baseUrl/job?id=$id&cancel=1").post(ByteArray(0).toRequestBody(null)).build()
            http.newCall(req).execute().close()
        }
    }

    /**
     * Polls until the job finishes. Returns "done"; throws on failure or cancel.
     * [onTick] receives the elapsed milliseconds.
     */
    suspend fun await(id: String, timeoutMs: Long, onTick: suspend (Long) -> Unit = {}): String {
        val start = System.currentTimeMillis()
        var misses = 0
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            if (elapsed > timeoutMs) {
                cancel(id)
                throw EngineException("The engine took too long and the job was stopped.")
            }
            val s = try {
                status(id).also { misses = 0 }
            } catch (e: IOException) {
                misses++
                if (misses > 10) throw EngineException("Lost contact with the engine: ${e.message}")
                "running"
            }
            when (s) {
                "done" -> return s
                "failed" -> throw EngineException("The engine could not make this song. Check Settings, Engine log for details.")
                "cancelled" -> throw EngineException("Cancelled.")
            }
            onTick(elapsed)
            delay(1500)
        }
    }

    /** Result of an /lm job: the enriched requests (lyrics, metadata, audio codes). */
    suspend fun lmResult(id: String): List<JsonObject> = withContext(Dispatchers.IO) {
        val body = call(Request.Builder().url("$baseUrl/job?id=$id&result=1").build())
        val element = json.parseToJsonElement(body)
        when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
    }

    /** Result of a /synth job: one MP3 (plus its latent) per generated version. */
    suspend fun synthResult(id: String, outDir: File, prefix: String): List<SynthOutput> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/job?id=$id&result=1").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw EngineException("Could not download the song (HTTP ${response.code}).")
            val body = response.body ?: throw EngineException("Empty reply from the engine.")
            val outputs = mutableListOf<SynthOutput>()
            var pendingAudio: File? = null
            var index = 0
            MultipartReader(body).use { reader ->
                while (true) {
                    val part = reader.nextPart() ?: break
                    val type = part.headers["Content-Type"].orEmpty()
                    part.use {
                        if (type.startsWith("audio/")) {
                            pendingAudio?.let { outputs += SynthOutput(it, null) }
                            index++
                            val ext = if (type.contains("wav")) "wav" else "mp3"
                            val file = File(outDir, "$prefix$index.$ext")
                            file.sink().buffer().use { sink -> sink.writeAll(part.body) }
                            pendingAudio = file
                        } else {
                            val audio = pendingAudio
                            val file = File(outDir, "$prefix$index.latent")
                            file.sink().buffer().use { sink -> sink.writeAll(part.body) }
                            if (audio != null) {
                                outputs += SynthOutput(audio, file)
                                pendingAudio = null
                            } else {
                                file.delete()
                            }
                        }
                    }
                }
            }
            pendingAudio?.let { outputs += SynthOutput(it, null) }
            if (outputs.isEmpty()) throw EngineException("The engine returned no audio.")
            outputs
        }
    }

    private fun call(request: Request): String {
        val (code, text) = http.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
        if (code !in 200..299) {
            val message = runCatching {
                (json.parseToJsonElement(text) as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            throw EngineException(
                when (code) {
                    501 -> "The engine is missing a model: ${message ?: "check the models folder"}."
                    400 -> "The engine rejected the request: ${message ?: "bad request"}."
                    else -> "Engine error $code: ${message ?: text.take(200)}"
                }
            )
        }
        return text
    }

    private fun jobId(body: String): String {
        val obj = json.parseToJsonElement(body) as? JsonObject
        return obj?.get("id")?.jsonPrimitive?.contentOrNull
            ?: throw EngineException("The engine did not return a job id.")
    }
}
