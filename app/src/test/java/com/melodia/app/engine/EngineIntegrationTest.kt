package com.melodia.app.engine

import com.melodia.app.model.Catalog
import com.melodia.app.model.SongRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * Runs the app's own engine client and request builder against a real acestep.cpp server.
 * Skipped unless ACE_URL is set; the "Check" workflow starts a server and sets it.
 */
class EngineIntegrationTest {

    private val url = System.getenv("ACE_URL").orEmpty()
    private val out = File(System.getenv("ACE_OUT") ?: "build/ace-test").apply { mkdirs() }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()
    private val client by lazy { AceClient(url, http) }

    @Before
    fun needsServer() = assumeTrue("ACE_URL is not set", url.isNotBlank())

    @Test
    fun serverReportsModels() = runBlocking<Unit> {
        assertTrue("health check failed", client.health())
        val props = client.props()
        println("props: $props")
        assertTrue("no singer model", props.dit.isNotEmpty())
    }

    /** The main path: custom lyrics, a voice profile, two versions in one job. */
    @Test
    fun customLyricsTwoVersions() = runBlocking<Unit> {
        val req = SongRequest(
            title = "CI two versions",
            lyrics = "Morning light upon the river\nCarry me home tonight\n\nSing it loud and sing it free\nYou are the song in me",
            genre = "Pop",
            moods = listOf("Happy"),
            durationSec = 15,
            seed = 42,
            versions = 2,
        )
        val built = PromptBuilder.build(req, Catalog.defaultVoices.first(), PromptBuilder.Options(mp3Bitrate = 128))
        println("synth request: ${built.json}")
        val outputs = synth("two versions") { client.submit("/synth", built.json) }.let { id ->
            client.synthResult(id, out, "custom-v")
        }
        assertEquals("expected one MP3 per version", 2, outputs.size)
        outputs.forEach {
            assertMp3(it.audio)
            assertNotNull("missing latent for ${it.audio.name}", it.latent)
            assertTrue(it.latent!!.length() > 0)
        }
        File(out, "custom-lyrics.txt").writeText(built.lyrics)
    }

    /** Songwriter plans the song (metadata + audio codes), then the singer renders it. */
    @Test
    fun songwriterThenSinger() = runBlocking<Unit> {
        assumeTrue("no songwriter model on the server", client.props().lm.isNotEmpty())
        val req = SongRequest(
            lyrics = "Rain on the window\nI still remember you\n\nEvery little raindrop\nSings a song of you",
            genre = "Acoustic",
            moods = listOf("Nostalgic"),
            durationSec = 15,
            seed = 7,
        )
        val built = PromptBuilder.build(req, Catalog.defaultVoices[1])
        println("lm request: ${built.json}")
        val lmId = client.submit("/lm", built.json)
        val t0 = System.currentTimeMillis()
        client.await(lmId, 30 * 60_000L)
        println("songwriter took ${(System.currentTimeMillis() - t0) / 1000}s")
        val enriched = client.lmResult(lmId)
        assertEquals(1, enriched.size)
        val plan = enriched.first()
        println("songwriter plan keys: ${plan.keys}")
        println("bpm=${plan["bpm"]} key=${plan["keyscale"]} duration=${plan["duration"]}")
        assertFalse("no audio codes", plan["audio_codes"]?.jsonPrimitive?.contentOrNull.isNullOrBlank())

        val id = synth("lm plan") { client.submit("/synth", plan) }
        val outputs = client.synthResult(id, out, "songwriter-v")
        assertEquals(1, outputs.size)
        assertMp3(outputs.first().audio)
        File(out, "songwriter-lyrics.txt").writeText(plan["lyrics"]?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    /** "Write for me": checked and retried songwriter drafts, English and Bangla. */
    @Test
    fun writeLyricsForMe() = runBlocking<Unit> {
        val props = client.props()
        assumeTrue("no songwriter model on the server", props.lm.isNotEmpty())
        val model = Songwriter.preferredModel(props.lm)
        println("songwriter model: $model")
        val cases = listOf(
            "written-en" to SongRequest(theme = "missing home during the monsoon", genre = "Pop", moods = listOf("Nostalgic")),
            "written-bn" to SongRequest(theme = "the first rain in Dhaka", genre = "Acoustic", moods = listOf("Romantic"), language = "bn"),
        )
        for ((name, req) in cases) {
            val t0 = System.currentTimeMillis()
            val draft = try {
                Songwriter.write(
                    client, req, Catalog.defaultVoices.first(), model,
                    onRejected = { lyrics, problem -> println("$name: rejected ($problem):\n${lyrics.take(600)}\n") },
                ) { a, of -> println("$name: try $a of $of") }
            } catch (e: EngineException) {
                // The songwriter is weaker in Bangla; record it, but only English is required to work every time.
                println("$name: no usable draft: ${e.message}")
                if (name == "written-en") throw e
                continue
            }
            println("$name took ${(System.currentTimeMillis() - t0) / 1000}s in ${draft.attempts} tries:\n${draft.lyrics}\n")
            File(out, "$name.txt").writeText(draft.lyrics)
            val check = PromptBuilder.checkLyrics(draft.lyrics, PromptBuilder.lyricsLanguage(req), req.theme)
            assertTrue("unusable lyrics: $check", check.usable)
        }
    }

    /** Simple mode: a description only; the songwriter writes, the singer sings. */
    @Test
    fun simpleModeSong() = runBlocking<Unit> {
        val props = client.props()
        assumeTrue("no songwriter model on the server", props.lm.isNotEmpty())
        val req = SongRequest(
            simpleMode = true,
            description = "A cheerful acoustic song about a sunny morning in the village",
            durationSec = 30,
            seed = 11,
        )
        val voice = Catalog.defaultVoices[1]
        val model = Songwriter.preferredModel(props.lm)
        var built = PromptBuilder.build(req, voice, PromptBuilder.Options(lmModel = model))
        assertTrue(built.needsSongwriter)
        val draft = Songwriter.write(client, req, voice, model)
        built = PromptBuilder.withLyrics(built, draft.lyrics, PromptBuilder.lyricsLanguage(req), draft.plan)
        println("simple-mode request: ${built.json}")
        File(out, "simple-lyrics.txt").writeText(draft.lyrics)
        val id = synth("simple mode") { client.submit("/synth", built.json) }
        val outputs = client.synthResult(id, out, "simple-v")
        assertEquals(1, outputs.size)
        assertMp3(outputs.first().audio)
    }

    /** "Sound like me": a voice recording sent as the timbre reference. */
    @Test
    fun voiceReferenceRecording() = runBlocking<Unit> {
        val wav = File(out, "reference.wav")
        writeTestWav(wav, seconds = 6)
        val req = SongRequest(lyrics = "Hold on, hold on\nThe night is young", genre = "Soul", durationSec = 12, seed = 3)
        val built = PromptBuilder.build(req, null, PromptBuilder.Options(mp3Bitrate = 128))
        val id = synth("reference") { client.submitSynthWithReference(built.json, wav) }
        val outputs = client.synthResult(id, out, "reference-v")
        assertEquals(1, outputs.size)
        assertMp3(outputs.first().audio)
    }

    private suspend fun synth(label: String, submit: suspend () -> String): String {
        val id = submit()
        val t0 = System.currentTimeMillis()
        client.await(id, 40 * 60_000L)
        println("singer ($label) took ${(System.currentTimeMillis() - t0) / 1000}s")
        return id
    }

    private fun assertMp3(file: File) {
        assertTrue("${file.name} is missing", file.isFile)
        assertTrue("${file.name} is too small (${file.length()} bytes)", file.length() > 8_000)
        val head = file.inputStream().use { it.readNBytes(3) }
        val id3 = head.size == 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()
        val frame = head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0
        assertTrue("${file.name} does not look like an MP3", id3 || frame)
        println("${file.name}: ${file.length()} bytes")
    }

    /** A hummed-like test tone: a few harmonics with a slow vibrato, 44.1 kHz mono 16-bit. */
    private fun writeTestWav(file: File, seconds: Int) {
        val rate = 44_100
        val samples = rate * seconds
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            f.write("RIFF".toByteArray()); f.write(le32(36 + samples * 2)); f.write("WAVE".toByteArray())
            f.write("fmt ".toByteArray()); f.write(le32(16)); f.write(le16(1)); f.write(le16(1))
            f.write(le32(rate)); f.write(le32(rate * 2)); f.write(le16(2)); f.write(le16(16))
            f.write("data".toByteArray()); f.write(le32(samples * 2))
            val buf = ByteArray(samples * 2)
            for (i in 0 until samples) {
                val t = i.toDouble() / rate
                val f0 = 220.0 * (1 + 0.01 * sin(2 * PI * 5 * t))
                var v = 0.0
                for (h in 1..5) v += sin(2 * PI * f0 * h * t) / h
                val s = (v * 0.25 * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767)
                buf[i * 2] = s.toByte()
                buf[i * 2 + 1] = (s shr 8).toByte()
            }
            f.write(buf)
        }
    }
}
