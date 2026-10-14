package com.melodia.app.engine

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.melodia.app.appContainer
import com.melodia.app.model.Song
import com.melodia.app.model.SongStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Foreground service that makes songs one at a time, so generation keeps going
 * when the screen is off or the user switches apps.
 */
class GenerationService : Service() {

    companion object {
        private const val ACTION_ENQUEUE = "com.melodia.app.ENQUEUE"
        private const val ACTION_CANCEL = "com.melodia.app.CANCEL"
        private const val EXTRA_ID = "song_id"

        private val _active = MutableStateFlow<String?>(null)
        /** Id of the song being generated right now, if any. */
        val active: StateFlow<String?> = _active.asStateFlow()

        fun enqueue(context: Context, songId: String) {
            val intent = Intent(context, GenerationService::class.java)
                .setAction(ACTION_ENQUEUE)
                .putExtra(EXTRA_ID, songId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, songId: String) {
            val intent = Intent(context, GenerationService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_ID, songId)
            ContextCompat.startForegroundService(context, intent)
        }

        private const val LM_TIMEOUT_MS = 60L * 60 * 1000
        private const val SYNTH_TIMEOUT_MS = 3L * 60 * 60 * 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = ArrayDeque<String>()
    private var worker: Job? = null
    private var currentId: String? = null
    private var currentJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastTitle = "Melodia"
    private var lastText = "Getting ready"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(lastText, lastTitle)
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_ENQUEUE -> if (id != null && id != currentId && id !in pending) pending.addLast(id)
            ACTION_CANCEL -> if (id != null) cancelSong(id)
        }
        if (worker == null) {
            worker = scope.launch { runQueue() }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(text: String, title: String) {
        val notification = Notifications.progress(this, title, text, cancelIntent(currentId))
        ServiceCompat.startForeground(
            this,
            Notifications.WORK_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun updateNotification(title: String, text: String) {
        lastTitle = title
        lastText = text
        val notification = Notifications.progress(this, title, text, cancelIntent(currentId))
        runCatching { getSystemService(NotificationManager::class.java).notify(Notifications.WORK_ID, notification) }
    }

    private fun cancelIntent(songId: String?): PendingIntent? {
        if (songId == null) return null
        val intent = Intent(this, GenerationService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, songId)
        return PendingIntent.getForegroundService(
            this, songId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun cancelSong(id: String) {
        if (id == currentId) {
            currentJob?.cancel()
        } else if (pending.remove(id)) {
            scope.launch {
                appContainer.songs.update(id) { it.copy(status = SongStatus.CANCELLED, stage = "", error = "Cancelled") }
            }
        }
    }

    /** Runs on the main thread; heavy work inside is dispatched to IO by the client. */
    private suspend fun runQueue() {
        while (true) {
            val id = pending.removeFirstOrNull() ?: break
            val song = appContainer.songs.get(id) ?: continue
            if (song.status == SongStatus.CANCELLED) continue
            currentId = id
            _active.value = id
            val job = scope.launch { generate(song) }
            currentJob = job
            job.join()
            currentJob = null
            currentId = null
            _active.value = null
        }
        worker = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun generate(song: Song) {
        val c = appContainer
        val id = song.id
        val start = System.currentTimeMillis()
        var engine: ResolvedEngine? = null
        var remoteJob: String? = null
        var lastSave = 0L
        var holding = false
        acquireWakeLock()

        suspend fun stage(text: String) {
            c.songs.update(id) { it.copy(stage = text, elapsedMs = System.currentTimeMillis() - start) }
            updateNotification(song.title, text)
        }

        suspend fun tick(stageText: String, elapsedInJob: Long) {
            val now = System.currentTimeMillis()
            if (now - lastSave < 4000) return
            lastSave = now
            c.songs.update(id) { it.copy(elapsedMs = now - start) }
            updateNotification(song.title, "$stageText · ${formatDuration(now - start)}")
        }

        try {
            c.songs.update(id) {
                it.copy(status = SongStatus.GENERATING, stage = "Starting", error = "", startedAt = start, elapsedMs = 0)
            }
            updateNotification(song.title, "Starting")

            val resolved = c.router.resolve { text -> updateNotification(song.title, text) }
            engine = resolved
            if (resolved.kind == EngineKind.PHONE) {
                c.embedded.hold()
                holding = true
            }
            val settings = c.settings.current
            val props = runCatching { resolved.client.props() }.getOrNull()
            val lmAvailable = props?.lm?.isNotEmpty() == true
            val voice = song.voice
            val adapter = voice?.adapter?.takeIf { it.isNotEmpty() && props?.adapters?.contains(it) == true }.orEmpty()

            val lmModel = Songwriter.preferredModel(
                props?.lm.orEmpty(),
                if (resolved.kind == EngineKind.PC) settings.pcLmModel else "",
            )
            val options = PromptBuilder.Options(
                mp3Bitrate = settings.mp3Bitrate,
                synthModel = if (resolved.kind == EngineKind.PC) {
                    settings.pcDitModel.takeIf { props?.dit?.contains(it) == true }.orEmpty()
                } else {
                    ""
                },
                lmModel = lmModel,
                useCotCaption = settings.refineStyle,
                adapter = adapter,
                adapterScale = voice?.adapterScale ?: 1f,
            )
            var built = PromptBuilder.build(song.request, voice, options)
            if (built.needsSongwriter) {
                if (!lmAvailable) {
                    throw EngineException(
                        "To write the lyrics for you, the Songwriter brain model is needed. Download it in " +
                            "Settings, Phone AI, or switch to Custom mode and type your own lyrics."
                    )
                }
                // Lyrics first, checked and retried, so a bad draft never reaches the singer.
                stage("Writing lyrics")
                val written = Songwriter.write(resolved.client, song.request, voice, lmModel, onJob = { remoteJob = it }) { attempt, of ->
                    stage(if (attempt == 1) "Writing lyrics" else "Writing lyrics (try $attempt of $of)")
                }
                val fitted = PromptBuilder.fitLyrics(written.lyrics, song.request.durationSec)
                built = PromptBuilder.withLyrics(built, fitted, PromptBuilder.lyricsLanguage(song.request), written.plan)
            }
            val wantsLm = when (resolved.kind) {
                EngineKind.PC -> settings.pcSongwriter
                else -> settings.phoneSongwriter
            }
            val useLm = lmAvailable && wantsLm

            val speed = settings.speed[resolved.label] ?: 0f
            val audioSeconds = song.request.durationSec * song.request.versions.coerceAtLeast(1)
            val estimate = if (speed > 0f) (speed * audioSeconds * 1000 * (if (useLm) 1.3f else 1f)).toLong() else 0L
            c.songs.update(id) {
                it.copy(
                    engine = resolved.label,
                    estimateMs = estimate,
                    sentCaption = built.caption,
                    sentLyrics = built.lyrics,
                    language = built.language,
                )
            }

            var synthRequest: JsonObject = built.json
            var finalLyrics = built.lyrics
            if (useLm) {
                stage("Writing the song")
                val lmJob = resolved.client.submit("/lm", built.json)
                remoteJob = lmJob
                resolved.client.await(lmJob, LM_TIMEOUT_MS) { tick("Writing the song", it) }
                val enriched = resolved.client.lmResult(lmJob).firstOrNull()
                    ?: throw EngineException("The songwriter returned nothing.")
                synthRequest = enriched
                finalLyrics = enriched["lyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: finalLyrics
                remoteJob = null
            }

            stage("Singing")
            val reference = voice?.takeIf { it.refConsent }?.refAudioPath?.takeIf { it.isNotEmpty() }?.let { File(it) }
            val synthJob = if (reference != null && reference.isFile) {
                resolved.client.submitSynthWithReference(synthRequest, reference)
            } else {
                resolved.client.submit("/synth", synthRequest)
            }
            remoteJob = synthJob
            resolved.client.await(synthJob, SYNTH_TIMEOUT_MS) { tick("Singing", it) }

            stage("Saving")
            val dir = c.songs.dirFor(id)
            dir.listFiles()?.forEach { it.delete() }
            val outputs = resolved.client.synthResult(synthJob, dir, "v")
            remoteJob = null

            val elapsed = System.currentTimeMillis() - start
            val seconds = synthRequest["duration"]?.jsonPrimitive?.doubleOrNull?.toFloat()
                ?: song.request.durationSec.toFloat()
            if (seconds > 0f) {
                c.settings.recordSpeed(resolved.label, (elapsed / 1000f) / (seconds * outputs.size) / (if (useLm) 1.3f else 1f))
            }
            c.songs.update(id) {
                it.copy(
                    status = SongStatus.READY,
                    stage = "",
                    error = "",
                    files = outputs.map { o -> o.audio.absolutePath },
                    latentFiles = outputs.mapNotNull { o -> o.latent?.absolutePath },
                    durationSec = seconds,
                    bpm = synthRequest["bpm"]?.jsonPrimitive?.intOrNull ?: song.request.bpm,
                    keyscale = synthRequest["keyscale"]?.jsonPrimitive?.contentOrNull ?: song.request.keyscale,
                    seed = synthRequest["seed"]?.jsonPrimitive?.longOrNull ?: song.request.seed,
                    sentLyrics = finalLyrics,
                    elapsedMs = elapsed,
                )
            }
            Notifications.result(this, id, "\"${song.title}\" is ready", "Made with ${resolved.label} in ${formatDuration(elapsed)}.")
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val job = remoteJob
                val eng = engine
                if (job != null && eng != null) eng.client.cancel(job)
                c.songs.update(id) { it.copy(status = SongStatus.CANCELLED, stage = "", error = "Cancelled") }
            }
            throw e
        } catch (e: Exception) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            c.songs.update(id) { it.copy(status = SongStatus.FAILED, stage = "", error = message) }
            Notifications.result(this, id, "\"${song.title}\" failed", message)
        } finally {
            if (holding) c.embedded.release()
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "melodia:generate").apply {
            setReferenceCounted(false)
            acquire(3L * 60 * 60 * 1000)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}
