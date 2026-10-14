package com.melodia.app.data

import android.content.Context
import com.melodia.app.model.AppSettings
import com.melodia.app.model.Catalog
import com.melodia.app.model.Song
import com.melodia.app.model.SongRequest
import com.melodia.app.model.SongStatus
import com.melodia.app.model.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Writes a file atomically so a crash mid-write never leaves half a JSON file. */
internal fun writeAtomic(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        file.writeText(text)
        tmp.delete()
    }
}

class SettingsRepository(context: Context, private val json: Json) {
    private val prefs = context.getSharedPreferences("melodia", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings = runCatching {
        prefs.getString("settings", null)?.let { json.decodeFromString(AppSettings.serializer(), it) }
    }.getOrNull() ?: AppSettings()

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit().putString("settings", json.encodeToString(AppSettings.serializer(), next)).apply()
    }

    val current: AppSettings get() = _settings.value

    private fun loadDraft(): SongRequest = runCatching {
        prefs.getString("draft", null)?.let { json.decodeFromString(SongRequest.serializer(), it) }
    }.getOrNull() ?: SongRequest()

    private val _draft = MutableStateFlow(loadDraft())
    /** The song currently being set up on the Create screen. Survives restarts. */
    val draft: StateFlow<SongRequest> = _draft.asStateFlow()

    @Synchronized
    fun updateDraft(transform: (SongRequest) -> SongRequest) {
        replaceDraft(transform(_draft.value))
    }

    @Synchronized
    fun replaceDraft(next: SongRequest) {
        _draft.value = next
        prefs.edit().putString("draft", json.encodeToString(SongRequest.serializer(), next)).apply()
    }

    /** Records how long a generation took so the next estimate is realistic. */
    fun recordSpeed(engine: String, secondsPerAudioSecond: Float) {
        if (secondsPerAudioSecond <= 0f || secondsPerAudioSecond.isNaN()) return
        update { s ->
            val old = s.speed[engine]
            val blended = if (old == null || old <= 0f) secondsPerAudioSecond else old * 0.6f + secondsPerAudioSecond * 0.4f
            s.copy(speed = s.speed + (engine to blended))
        }
    }
}

class SongStore(context: Context, private val json: Json) {
    private val file = File(context.filesDir, "songs.json")
    val songsRoot: File = File(context.filesDir, "songs").apply { mkdirs() }
    private val mutex = Mutex()
    private val serializer = ListSerializer(Song.serializer())

    private val _songs = MutableStateFlow(load())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private fun load(): List<Song> {
        val list = runCatching {
            if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
        }.getOrDefault(emptyList())
        // Anything that was mid-generation when the app last died cannot resume.
        return list.map {
            if (it.status == SongStatus.GENERATING || it.status == SongStatus.QUEUED) {
                it.copy(status = SongStatus.FAILED, error = "Interrupted. Tap Retry to make it again.", stage = "")
            } else {
                it
            }
        }
    }

    fun get(id: String): Song? = _songs.value.firstOrNull { it.id == id }

    fun dirFor(id: String): File = File(songsRoot, id).apply { mkdirs() }

    private suspend fun persist(list: List<Song>) = withContext(Dispatchers.IO) {
        writeAtomic(file, json.encodeToString(serializer, list))
    }

    suspend fun add(song: Song) = mutex.withLock {
        val next = listOf(song) + _songs.value.filter { it.id != song.id }
        _songs.value = next
        persist(next)
    }

    suspend fun update(id: String, transform: (Song) -> Song) = mutex.withLock {
        val next = _songs.value.map { if (it.id == id) transform(it) else it }
        _songs.value = next
        persist(next)
    }

    suspend fun delete(id: String) = mutex.withLock {
        val next = _songs.value.filter { it.id != id }
        _songs.value = next
        persist(next)
        withContext(Dispatchers.IO) { File(songsRoot, id).deleteRecursively() }
    }
}

class VoiceStore(context: Context, private val json: Json) {
    private val file = File(context.filesDir, "voices.json")
    val voicesRoot: File = File(context.filesDir, "voices").apply { mkdirs() }
    private val mutex = Mutex()
    private val serializer = ListSerializer(VoiceProfile.serializer())

    private val _voices = MutableStateFlow(load())
    val voices: StateFlow<List<VoiceProfile>> = _voices.asStateFlow()

    private fun load(): List<VoiceProfile> = runCatching {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else null
    }.getOrNull() ?: Catalog.defaultVoices

    fun get(id: String): VoiceProfile? = _voices.value.firstOrNull { it.id == id }

    private suspend fun persist(list: List<VoiceProfile>) = withContext(Dispatchers.IO) {
        writeAtomic(file, json.encodeToString(serializer, list))
    }

    suspend fun save(profile: VoiceProfile) = mutex.withLock {
        val existing = _voices.value
        val next = if (existing.any { it.id == profile.id }) {
            existing.map { if (it.id == profile.id) profile else it }
        } else {
            existing + profile
        }
        _voices.value = next
        persist(next)
    }

    suspend fun delete(id: String) = mutex.withLock {
        val removed = _voices.value.firstOrNull { it.id == id }
        val next = _voices.value.filter { it.id != id }
        _voices.value = next
        persist(next)
        removed?.refAudioPath?.takeIf { it.isNotEmpty() }?.let { path ->
            withContext(Dispatchers.IO) { File(path).delete() }
        }
    }
}
