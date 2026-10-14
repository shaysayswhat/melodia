package com.melodia.app.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Background playback with lock-screen and notification controls. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

data class PlayerState(
    val songId: String? = null,
    val version: Int = 0,
    val title: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val loading: Boolean = false,
)

/** App-side handle to the playback service. */
class PlayerConnection(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var connecting = false
    private val waiting = mutableListOf<(MediaController) -> Unit>()
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let { block(it); return }
        waiting += block
        if (connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull() ?: run {
                waiting.clear()
                return@addListener
            }
            controller = c
            c.addListener(listener)
            val pendingBlocks = waiting.toList()
            waiting.clear()
            pendingBlocks.forEach { it(c) }
            publish(c)
        }, ContextCompat.getMainExecutor(context))
    }

    fun play(songId: String, version: Int, title: String, file: File) {
        _state.value = _state.value.copy(songId = songId, version = version, title = title, loading = true)
        withController { c ->
            val item = MediaItem.Builder()
                .setMediaId("$songId#$version")
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist("Melodia")
                        .build()
                )
                .build()
            c.setMediaItem(item)
            c.prepare()
            c.play()
        }
    }

    fun toggle() = withController { c -> if (c.isPlaying) c.pause() else c.play() }

    fun seekTo(ms: Long) = withController { c -> c.seekTo(ms.coerceAtLeast(0)) }

    fun skip(deltaMs: Long) = withController { c -> c.seekTo((c.currentPosition + deltaMs).coerceAtLeast(0)) }

    fun stop() = withController { c ->
        c.stop()
        c.clearMediaItems()
        _state.value = PlayerState()
    }

    private fun publish(player: Player) {
        val mediaId = player.currentMediaItem?.mediaId
        val songId = mediaId?.substringBefore('#')
        val version = mediaId?.substringAfter('#', "0")?.toIntOrNull() ?: 0
        _state.value = PlayerState(
            songId = songId,
            version = version,
            title = player.mediaMetadata.title?.toString() ?: _state.value.title,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
            loading = player.playbackState == Player.STATE_BUFFERING,
        )
        if (player.isPlaying) startTicker() else ticker?.cancel()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                controller?.let { publishPosition(it) }
                delay(500)
            }
        }
    }

    private fun publishPosition(player: Player) {
        _state.value = _state.value.copy(
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: _state.value.durationMs,
            isPlaying = player.isPlaying,
        )
    }
}
