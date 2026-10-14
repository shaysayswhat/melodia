package com.melodia.app.engine

import com.melodia.app.model.SongRequest
import com.melodia.app.model.VoiceProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/**
 * Gets lyrics from the songwriter model. Its output varies a lot from seed to seed
 * (sometimes a single line looped, invented words or the wrong language), so each draft
 * is checked and a new seed is tried until one is usable.
 */
object Songwriter {

    data class Draft(val lyrics: String, val plan: JsonObject, val attempts: Int)

    /** The biggest songwriter the engine has: bigger ones write far better lyrics. */
    fun preferredModel(available: List<String>, chosen: String = ""): String {
        if (chosen.isNotEmpty() && chosen in available) return chosen
        fun size(name: String): Double =
            Regex("""lm-(\d+(?:\.\d+)?)B""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        return available.maxByOrNull { size(it) }.orEmpty()
    }

    suspend fun write(
        client: AceClient,
        req: SongRequest,
        voice: VoiceProfile?,
        lmModel: String,
        attempts: Int = 3,
        onJob: (String?) -> Unit = {},
        onAttempt: suspend (attempt: Int, of: Int) -> Unit = { _, _ -> },
    ): Draft {
        val language = PromptBuilder.lyricsLanguage(req)
        val theme = req.theme.ifBlank { req.description }
        var best: Pair<Draft, PromptBuilder.LyricsCheck>? = null
        var lastProblem = ""
        val baseSeed = Random.nextLong(1, Int.MAX_VALUE.toLong())
        for (i in 1..attempts) {
            onAttempt(i, attempts)
            val request = PromptBuilder.inspireRequest(
                req, voice, PromptBuilder.Options(lmModel = lmModel), seed = baseSeed + i * 7919L,
            )
            val id = client.submit("/lm", request)
            onJob(id)
            client.await(id, 30L * 60 * 1000)
            val plan = client.lmResult(id).firstOrNull()
            onJob(null)
            if (plan == null) continue
            val lyrics = plan["lyrics"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val check = PromptBuilder.checkLyrics(lyrics, language, theme)
            if (!check.usable) {
                lastProblem = check.problem.orEmpty()
                continue
            }
            val draft = Draft(lyrics, plan, i)
            if (best == null || check.score > best.second.score) best = draft to check
            if (check.score >= 0.8f) break // usable and on topic: good enough, save the wait
        }
        return best?.first ?: throw EngineException(
            "The songwriter couldn't write usable lyrics this time ($lastProblem). " +
                "Try again, describe the song a little differently, or write your own lyrics."
        )
    }
}
