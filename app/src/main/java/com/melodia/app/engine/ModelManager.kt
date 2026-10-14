package com.melodia.app.engine

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.melodia.app.model.ModelPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** The GGUF files the on-phone singer needs, hosted on Hugging Face. */
object ModelCatalog {
    const val REPO = "Serveurperso/ACE-Step-1.5-GGUF"
    const val VAE = "vae-BF16.gguf"
    const val TEXT_ENCODER = "Qwen3-Embedding-0.6B-Q8_0.gguf"
    const val DIT_LITE = "acestep-v15-turbo-Q4_K_M.gguf"
    const val DIT_STANDARD = "acestep-v15-turbo-Q8_0.gguf"
    const val LM_SMALL = "acestep-5Hz-lm-0.6B-Q8_0.gguf"

    val allDits = listOf(DIT_LITE, DIT_STANDARD)
    val allFiles = listOf(DIT_LITE, DIT_STANDARD, TEXT_ENCODER, VAE, LM_SMALL)

    /** Used until the real sizes arrive from Hugging Face. */
    val approxBytes = mapOf(
        VAE to 322_000_000L,
        TEXT_ENCODER to 748_000_000L,
        DIT_LITE to 1_400_000_000L,
        DIT_STANDARD to 2_400_000_000L,
        LM_SMALL to 677_000_000L,
    )

    fun url(name: String) = "https://huggingface.co/$REPO/resolve/main/$name"

    fun packFiles(pack: ModelPack): List<String> =
        listOf(if (pack == ModelPack.LITE) DIT_LITE else DIT_STANDARD, TEXT_ENCODER, VAE)

    fun label(name: String) = when (name) {
        VAE -> "Audio decoder"
        TEXT_ENCODER -> "Text encoder"
        DIT_LITE -> "Singer model, Lite"
        DIT_STANDARD -> "Singer model, Standard"
        LM_SMALL -> "Songwriter brain"
        else -> name
    }
}

data class ModelFileState(
    val name: String,
    val installed: Boolean,
    val installedBytes: Long,
    val downloading: Boolean,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val note: String?,
)

class ModelManager(private val context: Context) {

    val modelsDir: File = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }
    val adaptersDir: File = File(context.filesDir, "adapters").apply { mkdirs() }
    private val tmpSubdir = "models_download"

    private val prefs = context.getSharedPreferences("model_downloads", Context.MODE_PRIVATE)
    private val dm: DownloadManager = context.getSystemService(DownloadManager::class.java)
    private val failures = mutableMapOf<String, String>()

    private val _state = MutableStateFlow<Map<String, ModelFileState>>(emptyMap())
    val state: StateFlow<Map<String, ModelFileState>> = _state.asStateFlow()

    private val _remoteSizes = MutableStateFlow<Map<String, Long>>(emptyMap())
    val remoteSizes: StateFlow<Map<String, Long>> = _remoteSizes.asStateFlow()

    init {
        refresh()
    }

    fun sizeOf(name: String): Long =
        _remoteSizes.value[name] ?: ModelCatalog.approxBytes[name] ?: 0L

    fun isInstalled(name: String): Boolean = File(modelsDir, name).let { it.isFile && it.length() > 1_000_000 }

    fun installedDit(): String? = ModelCatalog.allDits.lastOrNull { isInstalled(it) }

    fun phoneModelsReady(): Boolean =
        installedDit() != null && isInstalled(ModelCatalog.TEXT_ENCODER) && isInstalled(ModelCatalog.VAE)

    fun songwriterInstalled(): Boolean = isInstalled(ModelCatalog.LM_SMALL)

    fun anyDownloading(): Boolean = _state.value.values.any { it.downloading }

    fun adapterNames(): List<String> =
        adaptersDir.listFiles()?.filter { it.isFile && it.name.endsWith(".safetensors") }?.map { it.name }?.sorted()
            ?: emptyList()

    private fun activeId(name: String): Long? = prefs.getLong(name, -1L).takeIf { it >= 0 }

    /** Starts system downloads for the given files. Returns an error message, or null when started. */
    fun download(names: List<String>, wifiOnly: Boolean): String? {
        val tmpDir = context.getExternalFilesDir(tmpSubdir) ?: return "Storage is not available right now."
        val needed = names.filter { !isInstalled(it) && activeId(it) == null }
        val bytes = needed.sumOf { sizeOf(it) }
        val free = runCatching { android.os.StatFs(tmpDir.absolutePath).availableBytes }.getOrDefault(0L)
        if (bytes + 500_000_000L > free) {
            return "Not enough free storage. Needs about ${formatBytes(bytes + 500_000_000L)}, " +
                "you have ${formatBytes(free)} free."
        }
        for (name in needed) {
            failures.remove(name)
            File(tmpDir, name).delete()
            val request = DownloadManager.Request(Uri.parse(ModelCatalog.url(name)))
                .setTitle("Melodia: ${ModelCatalog.label(name)}")
                .setDescription(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, tmpSubdir, name)
                .setAllowedOverMetered(!wifiOnly)
                .setAllowedOverRoaming(false)
            val id = dm.enqueue(request)
            prefs.edit().putLong(name, id).apply()
        }
        refresh()
        return null
    }

    fun cancel(name: String) {
        activeId(name)?.let { dm.remove(it) }
        prefs.edit().remove(name).apply()
        refresh()
    }

    fun delete(name: String) {
        cancel(name)
        File(modelsDir, name).delete()
        refresh()
    }

    /** Polls the system download manager and moves finished files into the models folder. */
    fun refresh() {
        val map = LinkedHashMap<String, ModelFileState>()
        for (name in ModelCatalog.allFiles) {
            var downloading = false
            var done = 0L
            var total = 0L
            var note: String? = failures[name]
            val id = activeId(name)
            if (id != null) {
                val cursor = dm.query(DownloadManager.Query().setFilterById(id))
                if (cursor == null) {
                    prefs.edit().remove(name).apply()
                } else {
                    cursor.use { c ->
                        if (c.moveToFirst()) {
                            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            when (status) {
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    moveIntoPlace(name)
                                    prefs.edit().remove(name).apply()
                                }
                                DownloadManager.STATUS_FAILED -> {
                                    note = "Download failed (code $reason). Tap Download to retry."
                                    failures[name] = note!!
                                    prefs.edit().remove(name).apply()
                                    dm.remove(id)
                                }
                                DownloadManager.STATUS_PAUSED -> {
                                    downloading = true
                                    note = when (reason) {
                                        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
                                        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for internet"
                                        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Retrying soon"
                                        else -> "Paused"
                                    }
                                }
                                else -> downloading = true
                            }
                        } else {
                            prefs.edit().remove(name).apply()
                        }
                    }
                }
            }
            val file = File(modelsDir, name)
            map[name] = ModelFileState(
                name = name,
                installed = isInstalled(name),
                installedBytes = if (file.isFile) file.length() else 0L,
                downloading = downloading,
                downloadedBytes = done,
                totalBytes = if (total > 0) total else sizeOf(name),
                note = note,
            )
        }
        _state.value = map
    }

    private fun moveIntoPlace(name: String) {
        val src = File(context.getExternalFilesDir(tmpSubdir), name)
        val dst = File(modelsDir, name)
        if (!src.exists()) return
        dst.delete()
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
    }

    /** Reads the exact file sizes from the Hugging Face API. Failures are ignored. */
    suspend fun fetchRemoteSizes(http: OkHttpClient, json: Json) = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://huggingface.co/api/models/${ModelCatalog.REPO}/tree/main")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val array = json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonArray ?: return@use
                val sizes = mutableMapOf<String, Long>()
                for (element in array) {
                    val obj = element as? JsonObject ?: continue
                    val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: continue
                    val lfsSize = (obj["lfs"] as? JsonObject)?.get("size")?.jsonPrimitive?.longOrNull
                    val size = lfsSize ?: obj["size"]?.jsonPrimitive?.longOrNull ?: continue
                    if (path in ModelCatalog.allFiles) sizes[path] = size
                }
                if (sizes.isNotEmpty()) _remoteSizes.value = sizes
            }
        }
        refresh()
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1.0) String.format(java.util.Locale.US, "%.1f GB", gb)
    else String.format(java.util.Locale.US, "%d MB", bytes / 1_000_000)
}
