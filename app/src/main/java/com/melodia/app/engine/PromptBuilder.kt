package com.melodia.app.engine

import com.melodia.app.model.PronunciationFix
import com.melodia.app.model.SongRequest
import com.melodia.app.model.VoiceProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Turns what the user picked into the request ACE-Step understands:
 * a style caption, structured lyrics and a few metadata fields.
 * Pure Kotlin, no Android classes, so it is covered by unit tests.
 */
object PromptBuilder {

    const val MAX_CAPTION = 512

    private val TAG_LINE = Regex("""^\s*\[([^\]]+)]\s*$""")
    private val STANZA_SPLIT = Regex("""\n\s*\n""")
    private val SPACES = Regex("""\s+""")

    data class Options(
        val mp3Bitrate: Int = 192,
        val synthModel: String = "",
        val lmModel: String = "",
        val useCotCaption: Boolean = false,
        val adapter: String = "",
        val adapterScale: Float = 1f,
    )

    data class Built(
        val json: JsonObject,
        val caption: String,
        val lyrics: String,
        val language: String,
        /** True when the AI songwriter has to write the lyrics (no lyrics given, not instrumental). */
        val needsSongwriter: Boolean,
    )

    // ---------------------------------------------------------------- request

    fun build(req: SongRequest, voice: VoiceProfile?, opt: Options = Options()): Built {
        val caption = caption(req, voice)
        val lyrics = lyricsFor(req, voice)
        val language = languageFor(req, lyrics)
        val json = buildJsonObject {
            put("caption", caption)
            put("lyrics", lyrics)
            put("duration", req.durationSec.coerceIn(10, 600).toDouble())
            if (req.bpm > 0) put("bpm", req.bpm.coerceIn(30, 300))
            if (req.keyscale.isNotBlank()) put("keyscale", req.keyscale.trim())
            if (req.timeSignature.isNotBlank()) put("timesignature", req.timeSignature.trim())
            if (language.isNotEmpty()) put("vocal_language", language)
            put("seed", req.seed)
            put("synth_batch_size", req.versions.coerceIn(1, 4))
            put("lm_batch_size", 1)
            put("output_format", "mp3")
            put("mp3_bitrate", opt.mp3Bitrate)
            put("use_cot_caption", opt.useCotCaption)
            put("lm_mode", "generate")
            if (opt.synthModel.isNotEmpty()) put("synth_model", opt.synthModel)
            if (opt.lmModel.isNotEmpty()) put("lm_model", opt.lmModel)
            if (opt.adapter.isNotEmpty()) {
                put("adapter", opt.adapter)
                put("adapter_scale", opt.adapterScale.toDouble())
            }
        }
        return Built(json, caption, lyrics, language, needsSongwriter = lyrics.isEmpty())
    }

    /** Request for the songwriter to draft lyrics only ("inspire" mode, no audio). */
    fun inspireRequest(req: SongRequest, voice: VoiceProfile?, opt: Options = Options()): JsonObject {
        val base = caption(req.copy(simpleMode = false, instrumental = false), voice)
        val about = req.theme.ifBlank { req.description }.trim()
        val caption = trimCaption(if (about.isNotEmpty()) "$base, song about $about" else base)
        val language = if (req.language != "auto") req.language else ""
        return buildJsonObject {
            put("caption", caption)
            put("lyrics", "")
            put("duration", req.durationSec.coerceIn(10, 600).toDouble())
            if (language.isNotEmpty()) put("vocal_language", language)
            put("lm_mode", "inspire")
            put("use_cot_caption", false)
            if (opt.lmModel.isNotEmpty()) put("lm_model", opt.lmModel)
        }
    }

    fun languageFor(req: SongRequest, lyrics: String): String = when {
        req.instrumental -> "unknown"
        req.language != "auto" -> req.language
        lyrics.isBlank() -> ""
        else -> detectLanguage(lyrics)
    }

    fun lyricsFor(req: SongRequest, voice: VoiceProfile?): String {
        if (req.instrumental) return "[Instrumental]"
        if (req.simpleMode) return ""
        var text = req.lyrics.trim()
        if (text.isEmpty()) return ""
        text = autoTag(text)
        text = applyFixes(text, req.fixes)
        if (voice != null) text = applySectionDelivery(text, voice.sectionDelivery)
        return text
    }

    // ---------------------------------------------------------------- caption

    fun caption(req: SongRequest, voice: VoiceProfile?): String {
        val vocals = if (req.instrumental) "instrumental, no vocals" else voiceCaption(voice)
        if (req.simpleMode) {
            return trimCaption(join(listOf(req.description.trim(), vocals)))
        }
        val parts = mutableListOf<String>()
        parts += req.customStyle.trim().ifEmpty { req.genre.lowercase() }
        if (req.moods.isNotEmpty()) parts += req.moods.joinToString(", ") { it.lowercase() }
        parts += vocals
        val lead = req.leadInstrument.takeIf { it.isNotBlank() && it in req.instruments }
        val instruments = buildList {
            if (lead != null) add("${lead.lowercase()} lead")
            req.instruments.filter { it != lead }.forEach { add(it.lowercase()) }
        }
        if (instruments.isNotEmpty()) parts += instruments.joinToString(", ")
        if (req.theme.isNotBlank()) parts += "song about ${req.theme.trim()}"
        return trimCaption(join(parts))
    }

    fun voiceCaption(v: VoiceProfile?): String {
        if (v == null) return ""
        val parts = mutableListOf<String>()
        val range = v.range.lowercase().trim()
        parts += when (v.voice) {
            "Male" -> listOf("male", range, "vocal").filter { it.isNotEmpty() }.joinToString(" ")
            "Duet" -> "male and female duet vocals"
            "Choir" -> "choir vocals, rich group harmonies"
            else -> listOf("female", range, "vocal").filter { it.isNotEmpty() }.joinToString(" ")
        }
        if (v.timbre.isNotEmpty()) parts += v.timbre.joinToString(", ") { it.lowercase() } + " tone"
        v.delivery.forEach { parts += deliveryPhrase(it) }
        if (v.emotion.isNotBlank()) parts += "${v.emotion.lowercase()} emotional delivery"
        when (v.vibrato) {
            "None" -> parts += "straight tone without vibrato"
            "Strong" -> parts += "wide expressive vibrato"
            "Light" -> parts += "light vibrato"
        }
        v.techniques.forEach { parts += techniquePhrase(it) }
        if (v.style.isNotBlank()) parts += stylePhrase(v.style)
        if (v.mic.isNotBlank()) parts += micPhrase(v.mic)
        val sections = v.sectionDelivery.entries
            .filter { it.value.isNotBlank() }
            .sortedBy { sectionOrder(it.key) }
            .map { "${it.value.trim()} ${sectionPlural(it.key)}" }
        if (sections.isNotEmpty()) parts += sections.joinToString(", ")
        val description = v.description.trim().take(200)
        if (description.isNotEmpty()) parts += description
        return join(parts)
    }

    private fun deliveryPhrase(d: String) = when (d) {
        "Soft & intimate" -> "soft intimate delivery"
        "Powerful" -> "powerful vocals"
        "Belting" -> "belting high notes"
        "Falsetto" -> "airy falsetto"
        "Crooning" -> "crooning"
        "Rap flow" -> "rap flow"
        "Spoken word" -> "spoken word passages"
        "Whisper" -> "whispered vocals"
        "Playful" -> "playful delivery"
        else -> d.lowercase()
    }

    private fun techniquePhrase(t: String) = when (t) {
        "Melisma runs" -> "melismatic runs"
        "Ad-libs" -> "vocal ad-libs"
        "Backing harmonies" -> "backing vocal harmonies"
        "Auto-tune" -> "auto-tuned vocals"
        "Vocal fry" -> "vocal fry"
        "Doubled vocals" -> "double-tracked vocals"
        else -> t.lowercase()
    }

    private fun stylePhrase(s: String) = when (s) {
        "Bangla folk / Baul" -> "bangla folk baul singing style"
        "Soul / Gospel" -> "soul gospel singing style"
        "Opera / classical" -> "operatic classical singing style"
        else -> "${s.lowercase()} singing style"
    }

    private fun micPhrase(m: String) = when (m) {
        "Close-mic & dry" -> "close-mic dry vocal"
        "Polished studio" -> "polished studio vocal production"
        "Hall reverb" -> "vocals with hall reverb"
        "Lo-fi" -> "lo-fi vocal texture"
        "Live performance" -> "live performance feel"
        else -> m.lowercase()
    }

    private fun sectionOrder(kind: String) = listOf("Intro", "Verse", "Pre-Chorus", "Chorus", "Bridge", "Outro")
        .indexOf(kind).let { if (it < 0) 99 else it }

    private fun sectionPlural(kind: String) = when (kind) {
        "Verse" -> "verses"
        "Chorus" -> "chorus"
        "Pre-Chorus" -> "pre-chorus"
        "Bridge" -> "bridge"
        "Intro" -> "intro"
        "Outro" -> "outro"
        else -> kind.lowercase()
    }

    private fun join(parts: List<String>) = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

    fun trimCaption(caption: String): String {
        if (caption.length <= MAX_CAPTION) return caption
        val cut = caption.take(MAX_CAPTION)
        val lastComma = cut.lastIndexOf(',')
        return if (lastComma > MAX_CAPTION / 2) cut.substring(0, lastComma) else cut
    }

    // ---------------------------------------------------------------- lyrics

    fun hasTags(lyrics: String): Boolean = lyrics.lines().any { TAG_LINE.matches(it) }

    /**
     * Adds section tags to plain lyrics: a blank line starts a new stanza,
     * a stanza that appears more than once becomes the chorus, the rest are verses.
     */
    fun autoTag(lyrics: String): String {
        val text = lyrics.trim()
        if (text.isEmpty() || hasTags(text)) return lyrics.trim()
        val stanzas = text.split(STANZA_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        val normalized = stanzas.map { it.lowercase().replace(SPACES, " ") }
        val counts = normalized.groupingBy { it }.eachCount()
        var verse = 0
        val out = StringBuilder()
        stanzas.forEachIndexed { i, stanza ->
            val tag = if ((counts[normalized[i]] ?: 0) >= 2) {
                "Chorus"
            } else {
                verse++
                "Verse $verse"
            }
            if (out.isNotEmpty()) out.append("\n\n")
            out.append('[').append(tag).append("]\n").append(stanza)
        }
        return out.toString()
    }

    /** "Verse 2 - soft" -> "Verse", "Pre chorus" -> "Pre-Chorus". */
    fun sectionKind(tagContent: String): String {
        val base = tagContent.substringBefore(" - ").substringBefore(":").trim()
        return when {
            base.startsWith("pre", ignoreCase = true) -> "Pre-Chorus"
            base.startsWith("verse", ignoreCase = true) -> "Verse"
            base.startsWith("chorus", ignoreCase = true) || base.startsWith("hook", ignoreCase = true) -> "Chorus"
            base.startsWith("bridge", ignoreCase = true) -> "Bridge"
            base.startsWith("intro", ignoreCase = true) -> "Intro"
            base.startsWith("outro", ignoreCase = true) -> "Outro"
            else -> base
        }
    }

    /** Writes per-section delivery notes into tags: [Chorus] -> [Chorus - powerful]. */
    fun applySectionDelivery(lyrics: String, notes: Map<String, String>): String {
        if (notes.isEmpty()) return lyrics
        return lyrics.lines().joinToString("\n") { line ->
            val match = TAG_LINE.matchEntire(line)
            if (match == null) {
                line
            } else {
                val content = match.groupValues[1].trim()
                val note = notes[sectionKind(content)]?.trim().orEmpty()
                if (note.isEmpty() || content.contains(" - ")) line else "[$content - $note]"
            }
        }
    }

    /** Replaces whole words with the user's phonetic respelling. Tag lines are left alone. */
    fun applyFixes(lyrics: String, fixes: List<PronunciationFix>): String {
        val active = fixes.filter { it.word.isNotBlank() && it.soundsLike.isNotBlank() }
        if (active.isEmpty()) return lyrics
        val patterns = active.map { fix ->
            val word = Regex.escape(fix.word.trim())
            Regex("(?<![\\p{L}\\p{M}])$word(?![\\p{L}\\p{M}])", RegexOption.IGNORE_CASE) to fix.soundsLike.trim()
        }
        return lyrics.lines().joinToString("\n") { line ->
            if (TAG_LINE.matches(line)) {
                line
            } else {
                var out = line
                for ((regex, replacement) in patterns) {
                    out = regex.replace(out, Regex.escapeReplacement(replacement))
                }
                out
            }
        }
    }

    // ---------------------------------------------------------------- language

    /** Guesses the singing language from the writing system used in the lyrics. */
    fun detectLanguage(text: String): String {
        var bengali = 0
        var devanagari = 0
        var arabic = 0
        var urduOnly = 0
        var kana = 0
        var hangul = 0
        var han = 0
        var cyrillic = 0
        var latin = 0
        text.lines().filterNot { TAG_LINE.matches(it) }.forEach { line ->
            for (ch in line) {
                when (ch.code) {
                    in 0x0980..0x09FF -> bengali++
                    in 0x0900..0x097F -> devanagari++
                    0x0679, 0x0688, 0x0691, 0x06BA, 0x06BE, 0x06C1, 0x06D2 -> { arabic++; urduOnly++ }
                    in 0x0600..0x06FF, in 0x0750..0x077F -> arabic++
                    in 0x3040..0x30FF -> kana++
                    in 0xAC00..0xD7AF, in 0x1100..0x11FF -> hangul++
                    in 0x4E00..0x9FFF -> han++
                    in 0x0400..0x04FF -> cyrillic++
                    in 0x0041..0x005A, in 0x0061..0x007A, in 0x00C0..0x024F -> latin++
                }
            }
        }
        val scores = listOf(
            "bn" to bengali,
            "hi" to devanagari,
            (if (urduOnly > 0) "ur" else "ar") to arabic,
            "ja" to kana,
            "ko" to hangul,
            (if (kana > 0) "ja" else "zh") to han,
            "ru" to cyrillic,
            "en" to latin,
        )
        val best = scores.maxByOrNull { it.second } ?: return "en"
        return if (best.second == 0) "en" else best.first
    }

    // ---------------------------------------------------------------- syllables

    private val LATIN_VOWELS = Regex("[aeiouyàáâãäåæèéêëìíîïòóôõöøùúûüý]+", RegexOption.IGNORE_CASE)

    /** Rough syllable count for one lyric line, used to spot lines that are too long to sing. */
    fun syllables(line: String): Int {
        if (TAG_LINE.matches(line)) return 0
        var count = 0
        val chars = line.toCharArray()
        var i = 0
        val latinWord = StringBuilder()
        fun flushLatin() {
            if (latinWord.isEmpty()) return
            val w = latinWord.toString().lowercase()
            var n = LATIN_VOWELS.findAll(w).count()
            if (n > 1 && w.endsWith("e") && !w.endsWith("le")) n--
            count += maxOf(1, n)
            latinWord.clear()
        }
        while (i < chars.size) {
            val c = chars[i]
            val code = c.code
            val next = if (i + 1 < chars.size) chars[i + 1].code else 0
            when {
                c.isLetter() && code < 0x0250 -> latinWord.append(c)
                // Bengali: independent vowels, or consonants not silenced by hasanta.
                code in 0x0985..0x0994 -> { flushLatin(); count++ }
                (code in 0x0995..0x09B9 || code in 0x09DC..0x09DF) && next != 0x09CD -> { flushLatin(); count++ }
                // Devanagari, same rule with the virama.
                code in 0x0905..0x0914 -> { flushLatin(); count++ }
                (code in 0x0915..0x0939 || code in 0x0958..0x095F) && next != 0x094D -> { flushLatin(); count++ }
                // CJK and Hangul: one character, one syllable.
                code in 0x3040..0x30FF || code in 0x4E00..0x9FFF || code in 0xAC00..0xD7AF -> { flushLatin(); count++ }
                else -> flushLatin()
            }
            i++
        }
        flushLatin()
        return count
    }
}
