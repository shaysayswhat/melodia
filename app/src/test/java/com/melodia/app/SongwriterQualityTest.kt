package com.melodia.app

import com.melodia.app.engine.PromptBuilder
import com.melodia.app.model.Catalog
import com.melodia.app.model.SongRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lyric check, tested on real songwriter output (0.6B and 1.7B) collected from the engine. */
class SongwriterQualityTest {

    private fun samples(): List<JsonObject> {
        val text = javaClass.classLoader!!.getResource("songwriter-samples.json")!!.readText()
        return (Json.parseToJsonElement(text) as JsonArray).map { it as JsonObject }
    }

    @Test
    fun realSongwriterOutputIsSortedCorrectly() {
        val wrong = samples().mapNotNull { s ->
            val id = s["id"]!!.jsonPrimitive.content
            val expectGood = s["expect"]!!.jsonPrimitive.content == "good"
            val check = PromptBuilder.checkLyrics(s["lyrics"]!!.jsonPrimitive.content, s["language"]!!.jsonPrimitive.content)
            if (check.usable != expectGood) "$id: expected ${if (expectGood) "good" else "bad"}, got $check" else null
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun banglaScriptLyricsPass() {
        val lyrics = """
            [Verse 1]
            বৃষ্টি নামে আমার শহরে
            তোমার কথা মনে পড়ে
            মেঘের ভেলায় ভেসে ভেসে
            মন যে শুধু তোমায় খোঁজে

            [Chorus]
            ফিরে এসো, ফিরে এসো
            এই মেঘলা দিনে ফিরে এসো
        """.trimIndent()
        assertTrue(PromptBuilder.checkLyrics(lyrics, "bn").usable)
        assertFalse("Bangla is not English", PromptBuilder.checkLyrics(lyrics, "en").usable)
    }

    @Test
    fun onTopicLyricsScoreHigher() {
        val lyrics = "Rain on the window\nI'm missing home tonight\nThe monsoon sings my name\nCarry me back to the light"
        val on = PromptBuilder.checkLyrics(lyrics, "en", theme = "missing home during the monsoon")
        val off = PromptBuilder.checkLyrics(lyrics, "en", theme = "a road trip through the desert")
        assertTrue(on.usable && off.usable)
        assertTrue(on.score > off.score)
    }

    @Test
    fun inspireRequestIsAPlainSentenceInTheRightLanguage() {
        val req = SongRequest(theme = "missing home during the monsoon", genre = "Pop", moods = listOf("Nostalgic"))
        val json = PromptBuilder.inspireRequest(req, Catalog.defaultVoices.first(), seed = 5)
        val caption = json["caption"]!!.jsonPrimitive.content
        assertTrue(caption, caption.startsWith("A nostalgic pop song about missing home during the monsoon, sung in English"))
        assertEquals(1, Regex("missing home").findAll(caption).count())
        assertEquals("en", json["vocal_language"]!!.jsonPrimitive.content)
        assertEquals("inspire", json["lm_mode"]!!.jsonPrimitive.content)
        assertEquals(5L, json["lm_seed"]!!.jsonPrimitive.longOrNull)

        val bangla = PromptBuilder.inspireRequest(SongRequest(theme = "ঢাকার প্রথম বৃষ্টি", genre = "Acoustic"), null)
        assertEquals("bn", bangla["vocal_language"]!!.jsonPrimitive.content)

        val simple = PromptBuilder.inspireRequest(
            SongRequest(simpleMode = true, description = "An upbeat Eid song for my family", language = "bn"), null,
        )
        assertTrue(simple["caption"]!!.jsonPrimitive.contentOrNull!!.startsWith("An upbeat Eid song for my family. Sung in Bangla"))
    }

    @Test
    fun writtenLyricsGoIntoTheRequestWithoutOverridingUserChoices() {
        val req = SongRequest(simpleMode = true, description = "A happy birthday song", bpm = 100)
        val built = PromptBuilder.build(req, null)
        assertTrue(built.needsSongwriter)
        val plan = Json.parseToJsonElement("""{"bpm":128,"keyscale":"G major","timesignature":"N/A","lyrics":"x"}""") as JsonObject
        val merged = PromptBuilder.withLyrics(built, "[Verse]\nHappy day", "en", plan)
        assertFalse(merged.needsSongwriter)
        assertEquals("[Verse]\nHappy day", merged.json["lyrics"]!!.jsonPrimitive.content)
        assertEquals("100", merged.json["bpm"]!!.jsonPrimitive.content)
        assertEquals("G major", merged.json["keyscale"]!!.jsonPrimitive.content)
        assertFalse(merged.json.containsKey("timesignature"))
        assertEquals("en", merged.json["vocal_language"]!!.jsonPrimitive.content)
    }

    @Test
    fun longLyricsAreCutToWholeSectionsThatFit() {
        val lyrics = """
            [Intro - Acoustic Guitar]

            [Verse 1]
            one
            two
            three
            four

            [Chorus]
            five
            six
            seven
            eight

            [Verse 2]
            nine
            ten
            eleven
            twelve

            [Chorus]
            five
            six
        """.trimIndent()
        assertEquals(9, PromptBuilder.linesThatFit(30))
        val fitted = PromptBuilder.fitLyrics(lyrics, 30)
        assertEquals(8, PromptBuilder.sungLines(fitted))
        assertTrue(fitted.startsWith("[Intro - Acoustic Guitar]\n\n[Verse 1]\none"))
        assertTrue(fitted.endsWith("[Chorus]\nfive\nsix\nseven\neight"))
        assertEquals(lyrics.trim(), PromptBuilder.fitLyrics(lyrics, 120))
    }
}
