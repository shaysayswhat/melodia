package com.melodia.app.engine

import com.melodia.app.model.Catalog
import com.melodia.app.model.PronunciationFix
import com.melodia.app.model.SongRequest
import com.melodia.app.model.VoiceProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

    /**
     * Request for the songwriter to draft lyrics only ("inspire" mode, no audio).
     * The songwriter follows a plain sentence much better than a list of tags.
     */
    fun inspireRequest(req: SongRequest, voice: VoiceProfile?, opt: Options = Options(), seed: Long = -1): JsonObject {
        val language = lyricsLanguage(req)
        return buildJsonObject {
            put("caption", inspireQuery(req, voice, language))
            put("lyrics", if (req.instrumental) "[Instrumental]" else "")
            put("duration", req.durationSec.coerceIn(10, 600).toDouble())
            if (req.bpm > 0) put("bpm", req.bpm.coerceIn(30, 300))
            if (req.keyscale.isNotBlank()) put("keyscale", req.keyscale.trim())
            put("vocal_language", language)
            put("lm_mode", "inspire")
            put("use_cot_caption", false)
            if (seed >= 0) put("lm_seed", seed)
            if (opt.lmModel.isNotEmpty()) put("lm_model", opt.lmModel)
        }
    }

    /**
     * Puts songwriter-written lyrics into a request that had none. Tempo, key and meter the
     * songwriter picked are used only where the user left them on automatic.
     */
    fun withLyrics(built: Built, lyrics: String, language: String, plan: JsonObject? = null): Built {
        val json = buildJsonObject {
            built.json.forEach { (k, v) -> put(k, v) }
            put("lyrics", lyrics)
            put("vocal_language", language)
            if (plan != null) {
                for (key in listOf("bpm", "keyscale", "timesignature")) {
                    val value = plan[key]
                    val ok = value is JsonPrimitive && value.contentOrNull.let { it != null && it.isNotBlank() && it != "N/A" && it != "0" }
                    if (!built.json.containsKey(key) && ok) put(key, value!!)
                }
            }
        }
        return built.copy(json = json, lyrics = lyrics, language = language, needsSongwriter = false)
    }

    /** The language lyrics should be written in: the chosen one, or guessed from what the user typed. */
    fun lyricsLanguage(req: SongRequest): String {
        if (req.language != "auto") return req.language
        val typed = listOf(req.theme, req.description, req.lyrics).joinToString("\n").trim()
        return if (typed.isEmpty()) "en" else detectLanguage(typed)
    }

    /** "A tender, nostalgic acoustic song about the monsoon, sung in Bengali by a warm female alto. ..." */
    fun inspireQuery(req: SongRequest, voice: VoiceProfile?, language: String): String {
        val about = req.theme.ifBlank { if (req.simpleMode) "" else req.description }.trim().trimEnd('.')
        if (req.simpleMode && req.description.isNotBlank()) {
            val described = req.description.trim().trimEnd('.')
            return trimCaption(
                "$described. Sung in ${Catalog.languageName(language)}" +
                    (singerPhrase(voice)?.let { " by $it" } ?: "") +
                    ". Clear, simple, singable lyrics with verses and a catchy chorus."
            )
        }
        val style = req.customStyle.trim().ifEmpty { req.genre }.lowercase()
        val moods = req.moods.take(2).map { it.lowercase() }
        val head = (if (moods.isEmpty()) "" else moods.joinToString(", ") + " ") + "$style song"
        val article = if (head.first().lowercaseChar() in "aeiou") "An" else "A"
        val sb = StringBuilder("$article $head")
        if (about.isNotEmpty()) sb.append(" about ").append(about)
        if (req.instrumental) {
            sb.append(", instrumental")
        } else {
            sb.append(", sung in ").append(Catalog.languageName(language))
            singerPhrase(voice)?.let { sb.append(" by ").append(it) }
        }
        val instruments = req.instruments.take(4).map { it.lowercase() }
        if (instruments.isNotEmpty()) sb.append(", with ").append(instruments.joinToString(", "))
        sb.append(". Clear, simple, singable lyrics with verses and a catchy chorus")
        if (about.isNotEmpty()) sb.append(" that stay on this topic")
        sb.append('.')
        return trimCaption(sb.toString())
    }

    private fun singerPhrase(v: VoiceProfile?): String? {
        if (v == null) return null
        val tone = v.timbre.firstOrNull()?.lowercase()
        val range = v.range.lowercase().trim()
        val who = when (v.voice) {
            "Male" -> listOfNotNull(tone, "male", range.ifEmpty { null }).joinToString(" ")
            "Duet" -> "a male and female duet"
            "Choir" -> "a choir"
            else -> listOfNotNull(tone, "female", range.ifEmpty { null }).joinToString(" ")
        }
        return if (v.voice == "Duet" || v.voice == "Choir") who else "a $who singer"
    }

    // ---------------------------------------------------------------- lyric quality

    data class LyricsCheck(val score: Float, val problem: String?) {
        val usable: Boolean get() = problem == null
    }

    private val WORD = Regex("""[\p{L}\p{M}']+""")
    private val ENGLISH_WORDS = setOf(
        "the", "i", "you", "me", "my", "your", "and", "a", "to", "of", "in", "is", "it", "on", "we", "be", "with",
        "for", "this", "so", "all", "love", "heart", "night", "home", "know", "never", "can", "can't", "don't",
        "i'm", "it's", "you're", "are", "was", "that", "like", "when", "where", "what", "just", "oh", "feel",
        "go", "come", "no", "not", "our", "us", "every", "day", "light", "rain", "away", "down", "up", "back",
        "still", "here", "there", "time", "now", "one", "into", "from", "by", "at", "let", "hold", "want",
    )
    private val ROMAN_BANGLA_WORDS = setOf(
        "ami", "tumi", "amar", "tomar", "tomay", "amay", "mon", "moner", "mone", "ei", "ki", "na", "je", "ar",
        "hoy", "jay", "jaay", "bhalobasha", "bhalobashi", "brishti", "akash", "chokhe", "chokher", "hridoy",
        "shudhu", "kotha", "din", "raat", "aaj", "ekhon", "kemon", "keno", "kothay", "shathe", "sathe", "pashe",
        "theke", "hoye", "kore", "dhore", "gaan", "sur", "megh", "nodi", "phire", "esho", "eso", "bhule", "ache",
        "achhe", "chhilo", "chilo", "tor", "mor", "shohor", "shohorer", "alo", "chhaya", "hashi", "kanna",
    )
    private val ROMAN_HINDI_WORDS = setOf(
        "main", "mein", "tum", "tu", "hai", "hain", "mera", "meri", "tera", "teri", "dil", "pyaar", "pyar", "ki",
        "ka", "ke", "na", "nahi", "hum", "yeh", "woh", "jaana", "sanam", "zindagi", "raat", "din", "aaj", "kya",
    )

    private val TOPIC_STOP = setOf(
        "about", "during", "with", "from", "that", "this", "there", "their", "into", "when", "where", "song", "songs",
        "some", "very", "just", "like", "feel", "feeling",
    )

    /**
     * Rejects songwriter output that would make a bad song: too short, looping on one line,
     * nonsense syllables, or written in a different language than asked.
     */
    fun checkLyrics(lyrics: String, language: String, theme: String = ""): LyricsCheck {
        val lines = lyrics.lines().map { it.trim() }.filter { it.isNotEmpty() && !TAG_LINE.matches(it) && !it.startsWith("[") }
        if (lines.size < 4) return LyricsCheck(0f, "too short")
        if (lines.any { it.length > 160 }) return LyricsCheck(0.1f, "a line is far too long to sing")
        val normalized = lines.map { l -> WORD.findAll(l.lowercase()).joinToString(" ") { it.value } }
        val unique = normalized.toSet().size.toFloat() / lines.size
        if (unique < 0.35f) return LyricsCheck(unique, "the same line repeats over and over")
        val words = normalized.flatMap { it.split(' ') }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return LyricsCheck(0f, "no words")
        val loopingLines = normalized.count { l ->
            val w = l.split(' ').filter { it.isNotEmpty() }
            w.size >= 4 && w.groupingBy { it }.eachCount().values.max() >= w.size * 0.6
        }
        if (loopingLines > lines.size * 0.3) return LyricsCheck(0.2f, "lines are mostly one repeated word")

        val letters = lyrics.filter { it.isLetter() }
        fun share(range: IntRange) = if (letters.isEmpty()) 0f else letters.count { it.code in range }.toFloat() / letters.length
        val latin = if (letters.isEmpty()) 0f else letters.count { it.code < 0x0250 }.toFloat() / letters.length
        fun known(set: Set<String>) = words.count { it in set }.toFloat() / words.size
        val languageOk = when (language) {
            "en" -> latin > 0.95f && known(ENGLISH_WORDS) >= 0.12f
            "bn" -> share(0x0980..0x09FF) > 0.6f || (latin > 0.9f && known(ROMAN_BANGLA_WORDS) >= 0.06f)
            "hi" -> share(0x0900..0x097F) > 0.6f || (latin > 0.9f && known(ROMAN_HINDI_WORDS) >= 0.08f)
            "ur", "ar" -> share(0x0600..0x06FF) > 0.6f
            "ja" -> share(0x3040..0x30FF) + share(0x4E00..0x9FFF) > 0.5f
            "ko" -> share(0xAC00..0xD7AF) > 0.6f
            "zh" -> share(0x4E00..0x9FFF) > 0.6f
            "ru" -> share(0x0400..0x04FF) > 0.6f
            else -> true
        }
        if (!languageOk) return LyricsCheck(0.3f, "not written in ${Catalog.languageName(language)}")

        val topic = WORD.findAll(theme.lowercase()).map { it.value }.filter { it.length >= 4 && it !in TOPIC_STOP }.toSet()
        val onTopic = topic.isEmpty() || words.any { w -> topic.any { t -> w.startsWith(t.take(5)) } }
        val score = 0.5f + 0.3f * unique.coerceAtMost(1f) + (if (onTopic) 0.2f else 0f)
        return LyricsCheck(score, null)
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

    /** Roughly how many sung lines fit in a song of this length, leaving room for intro and instrumental parts. */
    fun linesThatFit(durationSec: Int): Int = maxOf(4, (durationSec / 3.2).toInt())

    fun sungLines(lyrics: String): Int =
        lyrics.lines().count { it.isNotBlank() && !TAG_LINE.matches(it) && !it.trim().startsWith("[") }

    /**
     * Shortens lyrics to what the song length can hold, keeping whole sections in order.
     * Too many lyrics make the singer rush, skip lines or mumble.
     */
    fun fitLyrics(lyrics: String, durationSec: Int): String {
        val budget = linesThatFit(durationSec)
        if (sungLines(lyrics) <= budget) return lyrics.trim()
        val sections = mutableListOf<MutableList<String>>()
        for (line in lyrics.trim().lines()) {
            val isTag = line.trim().startsWith("[") && line.trim().endsWith("]")
            if (sections.isEmpty() || (isTag && sections.last().any { it.isNotBlank() && !it.trim().startsWith("[") })) {
                sections += mutableListOf<String>()
            }
            if (line.isNotBlank() || sections.last().isNotEmpty()) sections.last() += line
        }
        val kept = mutableListOf<List<String>>()
        var used = 0
        for (section in sections) {
            val sung = sungLines(section.joinToString("\n"))
            if (used > 0 && used + sung > budget) break
            kept += section
            used += sung
        }
        return kept.joinToString("\n\n") { it.joinToString("\n").trim() }.trim()
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
