package com.melodia.app.model

/** All the choices the UI offers. Plain data so it can be unit-tested and reused. */
object Catalog {

    val genres = listOf(
        "Pop", "Rock", "Hip-Hop", "R&B", "EDM", "Lo-fi", "Jazz", "Cinematic", "Folk", "Acoustic",
        "Country", "Metal", "Ballad", "Ambient", "Afrobeats", "Reggaeton", "K-pop", "Bollywood",
        "Bangla Folk", "Baul", "Rabindra Sangeet", "Nazrul Geeti", "Qawwali", "Ghazal", "Sufi",
        "Soul", "Gospel", "Funk", "Disco", "House", "Techno", "Trap", "Synthwave", "Indie",
        "Punk", "Blues", "Classical", "Latin", "Arabic Pop",
    )

    val moods = listOf(
        "Happy", "Sad", "Romantic", "Energetic", "Chill", "Dark", "Epic", "Dreamy", "Nostalgic",
        "Hopeful", "Angry", "Peaceful", "Melancholic", "Playful", "Spiritual",
    )

    val instrumentGroups: List<Pair<String, List<String>>> = listOf(
        "Keys" to listOf("Piano", "Rhodes", "Organ", "Synth pad"),
        "Guitars" to listOf("Acoustic guitar", "Electric guitar", "Distorted guitar", "Bass guitar"),
        "Drums" to listOf("Drum kit", "808", "Trap hi-hats", "Hand percussion"),
        "Strings" to listOf("Violin", "Cello", "String ensemble", "Harp"),
        "Winds & brass" to listOf("Flute", "Saxophone", "Trumpet", "Clarinet"),
        "South Asian" to listOf("Tabla", "Dhol", "Sitar", "Bansuri", "Harmonium", "Ektara", "Dotara", "Sarod", "Santoor"),
        "Electronic" to listOf("Lead synth", "Arp synth", "Sub bass", "Vocal chops"),
    )

    /** Code to display name. "auto" means detect from the lyrics' writing system. */
    val languages: List<Pair<String, String>> = listOf(
        "auto" to "Auto (from lyrics)",
        "en" to "English",
        "bn" to "Bangla",
        "hi" to "Hindi",
        "ur" to "Urdu",
        "ar" to "Arabic",
        "es" to "Spanish",
        "fr" to "French",
        "pt" to "Portuguese",
        "de" to "German",
        "it" to "Italian",
        "tr" to "Turkish",
        "id" to "Indonesian",
        "ru" to "Russian",
        "ja" to "Japanese",
        "ko" to "Korean",
        "zh" to "Chinese",
    )

    fun languageName(code: String): String =
        languages.firstOrNull { it.first == code }?.second ?: code

    val keys: List<String> = buildList {
        val notes = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
        for (n in notes) {
            add("$n major")
            add("$n minor")
        }
    }

    val timeSignatures = listOf("" to "Auto", "4" to "4/4", "3" to "3/4", "6" to "6/8")

    val durations = listOf(30, 60, 90, 120, 150, 180, 240)

    // ---- Voice designer ----

    val voices = listOf("Female", "Male", "Duet", "Choir")

    fun rangesFor(voice: String): List<String> = when (voice) {
        "Female" -> listOf("Soprano", "Mezzo-soprano", "Alto")
        "Male" -> listOf("Tenor", "Baritone", "Bass")
        else -> emptyList()
    }

    val timbres = listOf("Warm", "Bright", "Husky", "Raspy", "Breathy", "Silky", "Deep", "Nasal", "Clear", "Gritty")

    val deliveries = listOf(
        "Soft & intimate", "Powerful", "Belting", "Falsetto", "Crooning", "Rap flow", "Spoken word", "Whisper", "Playful",
    )

    val emotions = listOf("Tender", "Joyful", "Melancholic", "Longing", "Angry", "Hopeful", "Confident", "Devotional")

    val vibratos = listOf("None", "Light", "Strong")

    val techniques = listOf("Melisma runs", "Ad-libs", "Backing harmonies", "Auto-tune", "Vocal fry", "Doubled vocals")

    val styles = listOf(
        "Western pop", "Bangla folk / Baul", "Rabindra Sangeet", "Bollywood playback", "Qawwali", "Ghazal",
        "Soul / Gospel", "R&B", "K-pop", "Arabic", "Latin", "Country", "Rock", "Opera / classical",
    )

    val mics = listOf("Close-mic & dry", "Polished studio", "Hall reverb", "Lo-fi", "Live performance")

    val sections = listOf("Intro", "Verse", "Pre-Chorus", "Chorus", "Bridge", "Outro")

    val sectionNotes = listOf(
        "soft", "gentle", "whispered", "building", "emotional", "powerful", "belting", "airy falsetto", "spoken", "rap",
    )

    // ---- Presets for the Create screen ----

    data class Preset(
        val name: String,
        val genre: String,
        val moods: List<String>,
        val instruments: List<String>,
        val lead: String,
        val bpm: Int,
        val language: String? = null,
        val voiceId: String? = null,
    )

    val presets = listOf(
        Preset("Bangla acoustic", "Bangla Folk", listOf("Romantic", "Nostalgic"),
            listOf("Acoustic guitar", "Dotara", "Tabla", "Bansuri"), "Bansuri", 84, "bn", "warm-female"),
        Preset("Baul", "Baul", listOf("Spiritual", "Melancholic"),
            listOf("Ektara", "Dotara", "Dhol", "Harmonium"), "Ektara", 96, "bn", "baul-singer"),
        Preset("Lo-fi study", "Lo-fi", listOf("Chill", "Dreamy"),
            listOf("Rhodes", "Drum kit", "Bass guitar"), "Rhodes", 78),
        Preset("EDM anthem", "EDM", listOf("Energetic", "Happy"),
            listOf("Lead synth", "Sub bass", "Drum kit", "Synth pad"), "Lead synth", 126),
        Preset("Cinematic", "Cinematic", listOf("Epic", "Hopeful"),
            listOf("String ensemble", "Piano", "Cello"), "Piano", 90),
        Preset("Rainy-day R&B", "R&B", listOf("Romantic", "Melancholic"),
            listOf("Rhodes", "Bass guitar", "Drum kit", "Saxophone"), "Rhodes", 72),
        Preset("Bollywood romance", "Bollywood", listOf("Romantic", "Happy"),
            listOf("String ensemble", "Tabla", "Acoustic guitar", "Flute"), "Flute", 92, "hi", "duet"),
    )

    /** Starter voices so the Voice card is never empty. */
    val defaultVoices: List<VoiceProfile> = listOf(
        VoiceProfile(
            id = "warm-female", name = "Warm female", voice = "Female", range = "Alto",
            timbre = listOf("Warm", "Breathy"), delivery = listOf("Soft & intimate"), emotion = "Tender",
            vibrato = "Light", mic = "Close-mic & dry",
            sectionDelivery = mapOf("Verse" to "soft", "Chorus" to "emotional"),
        ),
        VoiceProfile(
            id = "bright-male", name = "Bright male", voice = "Male", range = "Tenor",
            timbre = listOf("Bright", "Clear"), delivery = listOf("Powerful"), emotion = "Hopeful",
            vibrato = "Light", techniques = listOf("Backing harmonies"), style = "Western pop", mic = "Polished studio",
        ),
        VoiceProfile(
            id = "baul-singer", name = "Baul singer", voice = "Male", range = "Baritone",
            timbre = listOf("Husky", "Raspy"), delivery = listOf("Crooning"), emotion = "Longing",
            vibrato = "Light", techniques = listOf("Melisma runs"), style = "Bangla folk / Baul", mic = "Live performance",
        ),
        VoiceProfile(
            id = "duet", name = "Romantic duet", voice = "Duet",
            timbre = listOf("Warm"), delivery = listOf("Soft & intimate", "Powerful"), emotion = "Tender",
            vibrato = "Light", techniques = listOf("Backing harmonies"), style = "Bollywood playback", mic = "Polished studio",
        ),
    )
}
