package com.melodia.app.model

import kotlinx.serialization.Serializable

/** Which engine the user wants to make songs with. */
@Serializable
enum class EngineMode { AUTO, PHONE, PC }

/** Model download packs for Phone AI. */
@Serializable
enum class ModelPack { LITE, STANDARD }

@Serializable
enum class SongStatus { QUEUED, GENERATING, READY, FAILED, CANCELLED }

@Serializable
data class PronunciationFix(
    val word: String = "",
    val soundsLike: String = "",
)

/** Everything the user chose on the Create screen. */
@Serializable
data class SongRequest(
    val simpleMode: Boolean = false,
    val description: String = "",
    val title: String = "",
    val lyrics: String = "",
    val instrumental: Boolean = false,
    val genre: String = "Pop",
    val customStyle: String = "",
    val moods: List<String> = emptyList(),
    val theme: String = "",
    val instruments: List<String> = emptyList(),
    val leadInstrument: String = "",
    /** ISO code (en, bn, hi…) or "auto" to detect from the lyrics' script. */
    val language: String = "auto",
    val durationSec: Int = 120,
    /** 0 = let the AI choose. */
    val bpm: Int = 0,
    /** "" = let the AI choose, e.g. "C major", "A minor". */
    val keyscale: String = "",
    /** "" = auto, otherwise "4", "3" or "6". */
    val timeSignature: String = "",
    val versions: Int = 1,
    /** -1 = random. */
    val seed: Long = -1,
    val voiceProfileId: String = "",
    val fixes: List<PronunciationFix> = emptyList(),
)

/** A reusable description of how the singer should sound. */
@Serializable
data class VoiceProfile(
    val id: String,
    val name: String,
    /** Female, Male, Duet, Choir */
    val voice: String = "Female",
    val range: String = "",
    val timbre: List<String> = emptyList(),
    val delivery: List<String> = emptyList(),
    val emotion: String = "",
    /** None, Light, Strong */
    val vibrato: String = "Light",
    val techniques: List<String> = emptyList(),
    val style: String = "",
    val mic: String = "",
    val description: String = "",
    /** Section name (Verse, Pre-Chorus, Chorus, Bridge, Intro, Outro) to a short delivery note. */
    val sectionDelivery: Map<String, String> = emptyMap(),
    /** Path of a short recording of the user's own voice, used as a tone reference. */
    val refAudioPath: String = "",
    val refConsent: Boolean = false,
    /** File name of a LoRA voice adapter in the adapters folder. */
    val adapter: String = "",
    val adapterScale: Float = 0.8f,
)

@Serializable
data class Song(
    val id: String,
    val title: String,
    val createdAt: Long,
    val request: SongRequest,
    val voice: VoiceProfile? = null,
    val status: SongStatus = SongStatus.QUEUED,
    val stage: String = "",
    val error: String = "",
    /** "Phone AI", "PC AI" or "Termux". */
    val engine: String = "",
    val files: List<String> = emptyList(),
    val latentFiles: List<String> = emptyList(),
    val durationSec: Float = 0f,
    val bpm: Int = 0,
    val keyscale: String = "",
    val sentCaption: String = "",
    val sentLyrics: String = "",
    val language: String = "",
    val seed: Long = -1,
    val favorite: Boolean = false,
    val startedAt: Long = 0,
    val elapsedMs: Long = 0,
    /** Expected generation time in ms, from earlier runs on the same engine (0 = unknown). */
    val estimateMs: Long = 0,
)

@Serializable
data class AppSettings(
    val engineMode: EngineMode = EngineMode.AUTO,
    val pcUrl: String = "",
    val termuxPort: Int = 8085,
    /** The songwriter plans the song before singing; tests showed the singer then follows the lyrics far better. */
    val phoneSongwriter: Boolean = true,
    val pcSongwriter: Boolean = true,
    val refineStyle: Boolean = false,
    val mp3Bitrate: Int = 192,
    val wifiOnlyDownloads: Boolean = true,
    val modelPack: ModelPack = ModelPack.LITE,
    val keepScreenOn: Boolean = true,
    val allowLowRam: Boolean = false,
    /** Measured seconds of work per second of audio, per engine label. */
    val speed: Map<String, Float> = emptyMap(),
    val pcDitModel: String = "",
    val pcLmModel: String = "",
    val welcomeSeen: Boolean = false,
    /** Bumped when a new default must replace a stored value; see SettingsRepository.migrate. */
    val settingsVersion: Int = 0,
)
