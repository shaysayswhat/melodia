@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.melodia.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melodia.app.appContainer
import com.melodia.app.model.Catalog
import com.melodia.app.ui.components.BackTopBar
import com.melodia.app.ui.components.Hint
import com.melodia.app.ui.theme.MelodiaColors
import com.melodia.app.util.Exporter
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.appContainer
    val state by c.player.state.collectAsStateWithLifecycle()
    val songs by c.songs.songs.collectAsStateWithLifecycle()
    val song = songs.firstOrNull { it.id == state.songId }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var dragging by remember { mutableStateOf<Float?>(null) }

    Scaffold(
        topBar = { BackTopBar("Now playing", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(32.dp))
                    .background(MelodiaColors.coverFor(state.songId ?: "melodia")),
            ) {
                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(96.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(
                song?.title ?: state.title.ifEmpty { "Nothing playing" },
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (song != null) {
                val info = buildList {
                    add(song.request.customStyle.ifBlank { song.request.genre })
                    if (song.language.isNotEmpty() && song.language != "unknown") add(Catalog.languageName(song.language))
                    if (song.bpm > 0) add("${song.bpm} BPM")
                    if (song.keyscale.isNotEmpty()) add(song.keyscale)
                    if (song.engine.isNotEmpty()) add(song.engine)
                }
                Hint(info.joinToString(" · "))
            }
            Spacer(Modifier.height(16.dp))

            val duration = state.durationMs.coerceAtLeast(1)
            val position = dragging ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            Slider(
                value = position,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { c.player.seekTo((it * duration).toLong()) }
                    dragging = null
                },
            )
            Row(Modifier.fillMaxWidth()) {
                Hint(clock((position * duration).toLong()))
                Spacer(Modifier.weight(1f))
                Hint(clock(state.durationMs))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                IconButton(onClick = { c.player.skip(-10_000) }) { Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds") }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MelodiaColors.gradient)
                        .clickable { c.player.toggle() },
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = { c.player.skip(10_000) }) { Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds") }
            }

            if (song != null) {
                if (song.files.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        song.files.forEachIndexed { i, path ->
                            FilterChip(
                                selected = state.version == i,
                                onClick = { c.player.play(song.id, i, song.title, File(path)) },
                                label = { Text("Version ${i + 1}") },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val file = song.files.getOrNull(state.version)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { scope.launch { c.songs.update(song.id) { it.copy(favorite = !it.favorite) } } }) {
                        Icon(
                            if (song.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (song.favorite) MelodiaColors.Pink else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = {
                        if (file != null) {
                            scope.launch {
                                val result = Exporter.saveToMusic(context, File(file), song.title)
                                snackbar.showSnackbar(result.getOrElse { e -> "Couldn't save: ${e.message}" })
                            }
                        }
                    }) { Icon(Icons.Filled.Download, contentDescription = "Save to Music") }
                    IconButton(onClick = { if (file != null) Exporter.share(context, File(file), song.title) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Lyrics", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                song.sentLyrics.ifBlank { "(instrumental)" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        Text("Style sent to the singer", style = MaterialTheme.typography.titleSmall)
                        Hint(song.sentCaption)
                        if (song.seed >= 0) Hint("Seed ${song.seed}")
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** Small bar above the tabs while something is loaded. */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val c = LocalContext.current.appContainer
    val state by c.player.state.collectAsStateWithLifecycle()
    if (state.songId == null) return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MelodiaColors.coverFor(state.songId ?: "")),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                state.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { c.player.toggle() }) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                )
            }
        }
    }
}

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
