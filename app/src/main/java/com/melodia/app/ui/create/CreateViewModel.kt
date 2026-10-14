package com.melodia.app.ui.create

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.melodia.app.appContainer
import com.melodia.app.engine.EngineException
import com.melodia.app.engine.EngineKind
import com.melodia.app.engine.GenerationService
import com.melodia.app.engine.PromptBuilder
import com.melodia.app.engine.Songwriter
import com.melodia.app.model.Catalog
import com.melodia.app.model.EngineMode
import com.melodia.app.model.Song
import com.melodia.app.model.SongRequest
import com.melodia.app.model.SongStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class CreateViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.appContainer

    private val _draft = c.settings.draft
    val draft: StateFlow<SongRequest> = c.settings.draft

    val voices = c.voices.voices
    val engine = c.router.status
    val settings = c.settings.settings

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        if (_draft.value.voiceProfileId.isEmpty()) {
            update { it.copy(voiceProfileId = c.voices.voices.value.firstOrNull()?.id.orEmpty()) }
        }
        refreshEngines()
    }

    fun update(transform: (SongRequest) -> SongRequest) {
        c.settings.updateDraft(transform)
    }

    fun refreshEngines() {
        viewModelScope.launch { c.router.refresh() }
    }

    fun setEngineMode(mode: EngineMode) {
        c.settings.update { it.copy(engineMode = mode) }
        refreshEngines()
    }

    fun applyPreset(p: Catalog.Preset) = update {
        it.copy(
            genre = p.genre,
            customStyle = "",
            moods = p.moods,
            instruments = p.instruments,
            leadInstrument = p.lead,
            bpm = p.bpm,
            language = p.language ?: it.language,
            voiceProfileId = p.voiceId?.takeIf { id -> c.voices.get(id) != null } ?: it.voiceProfileId,
        )
    }

    fun clearLyrics() = update { it.copy(lyrics = "", title = "", fixes = emptyList()) }

    fun addStructure() = update { it.copy(lyrics = PromptBuilder.autoTag(it.lyrics)) }

    /** Warnings worth confirming before a long on-phone generation. */
    fun preflightWarning(): String? {
        val status = engine.value
        val mode = settings.value.engineMode
        val onPhone = status.phoneReady && mode != EngineMode.PC
        if (!onPhone) return null
        val (level, charging) = c.device.battery()
        return when {
            c.device.thermalSevere() ->
                "Your phone is very warm. Making a song works the processor hard for several minutes. Let it cool down first, or continue anyway."
            level < 30 && !charging ->
                "Battery is at $level%. Making a song on the phone uses a lot of power, so plug in if you can."
            else -> null
        }
    }

    /** Validates the draft and queues it. Returns an error message, or null when queued. */
    fun create(preview: Boolean, onQueued: () -> Unit): String? {
        val d = _draft.value
        if (d.simpleMode && d.description.isBlank()) return "Describe the song you want first."
        if (!d.simpleMode && !d.instrumental && d.lyrics.length > 4000) return "Lyrics are too long (max 4000 characters)."
        val request = if (preview) d.copy(durationSec = 30, versions = 1) else d
        val voice = if (request.instrumental) null else c.voices.get(request.voiceProfileId)
        val title = request.title.trim().ifEmpty { autoTitle(request) } + if (preview) " (preview)" else ""
        val song = Song(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = System.currentTimeMillis(),
            request = request,
            voice = voice,
            status = SongStatus.QUEUED,
            stage = "Waiting",
        )
        viewModelScope.launch {
            c.songs.add(song)
            GenerationService.enqueue(getApplication(), song.id)
            onQueued()
        }
        return null
    }

    /** Asks the AI songwriter for lyrics based on the theme, genre and mood. */
    fun writeLyrics() {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = "Starting the songwriter"
            var heldPhone = false
            try {
                val engine = c.router.resolve { stage -> _busy.value = stage }
                if (engine.kind == EngineKind.PHONE) {
                    c.embedded.hold()
                    heldPhone = true
                }
                val props = engine.client.props()
                if (props.lm.isEmpty()) {
                    throw EngineException(
                        "The Songwriter brain isn't installed on ${engine.label}. " +
                            if (engine.kind == EngineKind.PC) "Download an LM model on your PC." else "Get it in Settings, Phone AI."
                    )
                }
                val s = settings.value
                val chosen = if (engine.kind == EngineKind.PC) s.pcLmModel else ""
                val lmModel = Songwriter.preferredModel(props.lm, chosen)
                val draft = _draft.value
                val voice = c.voices.get(draft.voiceProfileId)
                val written = Songwriter.write(engine.client, draft, voice, lmModel) { attempt, of ->
                    _busy.value = if (attempt == 1) "Writing lyrics" else "Writing lyrics (try $attempt of $of)"
                }
                val language = PromptBuilder.lyricsLanguage(draft)
                update {
                    it.copy(
                        lyrics = written.lyrics,
                        simpleMode = false,
                        instrumental = false,
                        // Romanized lyrics (Bangla in Latin letters) can't be recognized from the script later.
                        language = if (it.language == "auto" && PromptBuilder.detectLanguage(written.lyrics) != language) {
                            language
                        } else {
                            it.language
                        },
                    )
                }
                _messages.tryEmit("Lyrics written. Edit them however you like.")
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Couldn't write lyrics.")
            } finally {
                if (heldPhone) c.embedded.release()
                _busy.value = null
            }
        }
    }

    private fun autoTitle(r: SongRequest): String {
        val firstLine = r.lyrics.lines().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("[") }
        return when {
            !r.simpleMode && !firstLine.isNullOrEmpty() -> firstLine.take(40)
            r.theme.isNotBlank() -> r.theme.trim().take(40).replaceFirstChar { it.uppercase() }
            r.simpleMode && r.description.isNotBlank() -> r.description.trim().take(40).replaceFirstChar { it.uppercase() }
            else -> "${r.customStyle.ifBlank { r.genre }} song"
        }
    }
}
