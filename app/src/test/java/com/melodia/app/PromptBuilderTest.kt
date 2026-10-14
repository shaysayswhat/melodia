package com.melodia.app

import com.melodia.app.engine.EngineRouter
import com.melodia.app.engine.PromptBuilder
import com.melodia.app.model.Catalog
import com.melodia.app.model.PronunciationFix
import com.melodia.app.model.SongRequest
import com.melodia.app.model.VoiceProfile
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun autoTagMarksRepeatedStanzaAsChorus() {
        val lyrics = """
            Walking home in the rain
            Thinking of you again

            Hold me now
            Hold me now

            City lights are fading slow
            Nowhere else I want to go

            Hold me now
            Hold me now
        """.trimIndent()
        val tagged = PromptBuilder.autoTag(lyrics)
        assertTrue(tagged.startsWith("[Verse 1]\nWalking home"))
        assertEquals(2, Regex("""\[Chorus]""").findAll(tagged).count())
        assertTrue(tagged.contains("[Verse 2]\nCity lights"))
    }

    @Test
    fun autoTagLeavesTaggedLyricsAlone() {
        val lyrics = "[Intro]\n\n[Verse]\nHello"
        assertEquals(lyrics, PromptBuilder.autoTag(lyrics))
    }

    @Test
    fun fixesReplaceWholeWordsOnly() {
        val fixes = listOf(PronunciationFix("Dhaka", "Dhaa-kaa"), PronunciationFix("", "x"))
        val out = PromptBuilder.applyFixes("[Verse - Dhaka]\nDhaka nights, Dhakaiya food\nI love dhaka", fixes)
        assertEquals("[Verse - Dhaka]\nDhaa-kaa nights, Dhakaiya food\nI love Dhaa-kaa", out)
    }

    @Test
    fun fixesWorkForBangla() {
        val fixes = listOf(PronunciationFix("ভালো", "ভালোওও"))
        val out = PromptBuilder.applyFixes("তুমি ভালো থেকো", fixes)
        assertEquals("তুমি ভালোওও থেকো", out)
    }

    @Test
    fun sectionDeliveryIsWrittenIntoTags() {
        val lyrics = "[Verse 1]\nA\n\n[Chorus]\nB\n\n[Bridge - spoken]\nC"
        val out = PromptBuilder.applySectionDelivery(lyrics, mapOf("Verse" to "soft", "Chorus" to "powerful", "Bridge" to "whispered"))
        assertEquals("[Verse 1 - soft]\nA\n\n[Chorus - powerful]\nB\n\n[Bridge - spoken]\nC", out)
    }

    @Test
    fun detectsLanguagesFromScript() {
        assertEquals("bn", PromptBuilder.detectLanguage("[Verse]\nআমার সোনার বাংলা, আমি তোমায় ভালোবাসি"))
        assertEquals("hi", PromptBuilder.detectLanguage("तुम ही हो, अब तुम ही हो"))
        assertEquals("ur", PromptBuilder.detectLanguage("دل دیوانہ ہے، کیا کروں"))
        assertEquals("ar", PromptBuilder.detectLanguage("حبيبي يا نور العين"))
        assertEquals("ja", PromptBuilder.detectLanguage("君の名前を呼んだ"))
        assertEquals("ko", PromptBuilder.detectLanguage("사랑해요 그대"))
        assertEquals("en", PromptBuilder.detectLanguage("Hello darkness my old friend"))
    }

    @Test
    fun instrumentalRequest() {
        val built = PromptBuilder.build(SongRequest(instrumental = true, genre = "Lo-fi"), Catalog.defaultVoices[0])
        assertEquals("[Instrumental]", built.lyrics)
        assertEquals("unknown", built.language)
        assertFalse(built.needsSongwriter)
        assertTrue(built.caption.contains("instrumental"))
        assertFalse(built.caption.contains("female"))
    }

    @Test
    fun simpleModeNeedsSongwriter() {
        val built = PromptBuilder.build(SongRequest(simpleMode = true, description = "a happy birthday song"), null)
        assertTrue(built.needsSongwriter)
        assertEquals("", built.lyrics)
        assertEquals("a happy birthday song", built.caption)
        assertEquals("", built.json["lyrics"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun customRequestCarriesVoiceAndMetadata() {
        val voice = VoiceProfile(
            id = "v", name = "Test", voice = "Female", range = "Alto",
            timbre = listOf("Warm", "Husky"), delivery = listOf("Soft & intimate"), vibrato = "Light",
            style = "Bangla folk / Baul", sectionDelivery = mapOf("Chorus" to "powerful"),
        )
        val request = SongRequest(
            lyrics = "আমার গান\n\nতোমার জন্য\n\nআমার গান",
            genre = "Bangla Folk",
            moods = listOf("Romantic"),
            instruments = listOf("Tabla", "Dotara"),
            leadInstrument = "Dotara",
            bpm = 84,
            keyscale = "A minor",
            durationSec = 90,
            versions = 2,
        )
        val built = PromptBuilder.build(request, voice, PromptBuilder.Options(mp3Bitrate = 256))
        assertEquals("bn", built.language)
        assertTrue(built.caption.startsWith("bangla folk, romantic, female alto vocal"))
        assertTrue(built.caption.contains("warm, husky tone"))
        assertTrue(built.caption.contains("dotara lead, tabla"))
        assertTrue(built.caption.contains("bangla folk baul singing style"))
        assertTrue(built.lyrics.contains("[Chorus - powerful]"))
        assertEquals("84", built.json["bpm"].toString())
        assertEquals("\"A minor\"", built.json["keyscale"].toString())
        assertEquals("2", built.json["synth_batch_size"].toString())
        assertEquals("256", built.json["mp3_bitrate"].toString())
        assertEquals("\"bn\"", built.json["vocal_language"].toString())
        assertFalse(built.json.containsKey("synth_model"))
    }

    @Test
    fun captionIsCapped() {
        val voice = VoiceProfile(id = "v", name = "x", description = "a".repeat(200))
        val request = SongRequest(theme = "b".repeat(400), instruments = Catalog.instrumentGroups.flatMap { it.second })
        val caption = PromptBuilder.caption(request, voice)
        assertTrue(caption.length <= PromptBuilder.MAX_CAPTION)
    }

    @Test
    fun syllableCounts() {
        assertEquals(3, PromptBuilder.syllables("hello world"))
        assertEquals(0, PromptBuilder.syllables("[Chorus]"))
        val bangla = PromptBuilder.syllables("আমার সোনার বাংলা")
        assertTrue(bangla in 5..10)
    }

    @Test
    fun normalizesPcAddress() {
        assertEquals("http://192.168.1.5:8085", EngineRouter.normalizeUrl("192.168.1.5"))
        assertEquals("http://192.168.1.5:9000", EngineRouter.normalizeUrl(" http://192.168.1.5:9000/ "))
        assertEquals("https://box.tail1234.ts.net", EngineRouter.normalizeUrl("https://box.tail1234.ts.net"))
        assertEquals("", EngineRouter.normalizeUrl("  "))
    }
}
