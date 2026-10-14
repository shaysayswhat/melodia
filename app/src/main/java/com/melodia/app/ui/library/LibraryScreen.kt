@file:OptIn(ExperimentalMaterial3Api::class)

package com.melodia.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.melodia.app.engine.GenerationService
import com.melodia.app.engine.formatDuration
import com.melodia.app.model.Song
import com.melodia.app.model.SongStatus
import com.melodia.app.ui.Navigator
import com.melodia.app.ui.Overlay
import com.melodia.app.ui.Tab
import com.melodia.app.ui.components.Hint
import com.melodia.app.ui.theme.MelodiaColors
import com.melodia.app.util.Exporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private enum class Filter(val label: String) { ALL("All"), FAVORITES("Favorites"), READY("Ready"), WORKING("In progress"), FAILED("Failed") }

@Composable
fun LibraryScreen(nav: Navigator, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val c = context.appContainer
    val songs by c.songs.songs.collectAsStateWithLifecycle()
    val player by c.player.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var renameTarget by remember { mutableStateOf<Song?>(null) }
    var deleteTarget by remember { mutableStateOf<Song?>(null) }
    var lyricsTarget by remember { mutableStateOf<Song?>(null) }

    // Ticks once a second so elapsed times on generating songs stay live.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val shown = songs.filter { s ->
        val matchesQuery = query.isBlank() || s.title.contains(query, ignoreCase = true) ||
            s.request.genre.contains(query, ignoreCase = true)
        val matchesFilter = when (filter) {
            Filter.ALL -> true
            Filter.FAVORITES -> s.favorite
            Filter.READY -> s.status == SongStatus.READY
            Filter.WORKING -> s.status == SongStatus.GENERATING || s.status == SongStatus.QUEUED
            Filter.FAILED -> s.status == SongStatus.FAILED || s.status == SongStatus.CANCELLED
        }
        matchesQuery && matchesFilter
    }

    fun play(song: Song, version: Int = 0) {
        val path = song.files.getOrNull(version) ?: return
        c.player.play(song.id, version, song.title, File(path))
        nav.push(Overlay.Player)
    }

    fun retry(song: Song) {
        scope.launch {
            c.songs.update(song.id) { it.copy(status = SongStatus.QUEUED, stage = "Waiting", error = "", elapsedMs = 0) }
            GenerationService.enqueue(context, song.id)
        }
    }

    fun regenerate(song: Song) {
        scope.launch {
            val copy = song.copy(
                id = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                status = SongStatus.QUEUED,
                stage = "Waiting",
                error = "",
                files = emptyList(),
                latentFiles = emptyList(),
                favorite = false,
                elapsedMs = 0,
                request = song.request.copy(seed = -1),
            )
            c.songs.add(copy)
            GenerationService.enqueue(context, copy.id)
        }
    }

    fun reuse(song: Song) {
        val voiceId = song.voice?.id?.takeIf { c.voices.get(it) != null } ?: song.request.voiceProfileId
        c.settings.replaceDraft(song.request.copy(seed = -1, voiceProfileId = voiceId))
        nav.goTo(Tab.CREATE)
        scope.launch { snackbar.showSnackbar("Settings copied. Change anything and tap Create song.") }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search songs") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(Filter.entries.toList()) { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
            if (shown.isEmpty()) {
                EmptyLibrary(songs.isEmpty(), onCreate = { nav.goTo(Tab.CREATE) })
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.id }) { song ->
                        SongCard(
                            song = song,
                            now = now,
                            isPlaying = player.songId == song.id && player.isPlaying,
                            onPlay = { v ->
                                if (player.songId == song.id && player.version == v) {
                                    c.player.toggle()
                                    if (!player.isPlaying) nav.push(Overlay.Player)
                                } else {
                                    play(song, v)
                                }
                            },
                            onCancel = { GenerationService.cancel(context, song.id) },
                            onRetry = { retry(song) },
                            onFavorite = { scope.launch { c.songs.update(song.id) { it.copy(favorite = !it.favorite) } } },
                            onRename = { renameTarget = song },
                            onSave = {
                                val path = song.files.firstOrNull() ?: return@SongCard
                                scope.launch {
                                    val result = Exporter.saveToMusic(context, File(path), song.title)
                                    snackbar.showSnackbar(result.getOrElse { e -> "Couldn't save: ${e.message}" })
                                }
                            },
                            onShare = { song.files.firstOrNull()?.let { Exporter.share(context, File(it), song.title) } },
                            onLyrics = { lyricsTarget = song },
                            onReuse = { reuse(song) },
                            onRegenerate = { regenerate(song) },
                            onDelete = { deleteTarget = song },
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { song ->
        var name by remember(song.id) { mutableStateOf(song.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val newName = name.trim().ifEmpty { song.title }
                    scope.launch { c.songs.update(song.id) { it.copy(title = newName) } }
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    deleteTarget?.let { song ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${song.title}\"?") },
            text = { Text("The song files are removed from the app. Copies you saved to Music stay.") },
            confirmButton = {
                TextButton(onClick = {
                    if (player.songId == song.id) c.player.stop()
                    if (song.status == SongStatus.GENERATING || song.status == SongStatus.QUEUED) {
                        GenerationService.cancel(context, song.id)
                    }
                    scope.launch { c.songs.delete(song.id) }
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    lyricsTarget?.let { song ->
        AlertDialog(
            onDismissRequest = { lyricsTarget = null },
            title = { Text(song.title) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(song.sentLyrics.ifBlank { song.request.lyrics.ifBlank { "(no lyrics)" } })
                }
            },
            confirmButton = { TextButton(onClick = { lyricsTarget = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun EmptyLibrary(firstTime: Boolean, onCreate: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp, start = 32.dp, end = 32.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MelodiaColors.gradient),
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(if (firstTime) "No songs yet" else "Nothing matches", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Hint(if (firstTime) "Write some lyrics, pick a voice, and your songs will appear here." else "Try another search or filter.")
        if (firstTime) {
            TextButton(onClick = onCreate) { Text("Create your first song") }
        }
    }
}

@Composable
private fun SongCard(
    song: Song,
    now: Long,
    isPlaying: Boolean,
    onPlay: (Int) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onFavorite: () -> Unit,
    onRename: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onLyrics: () -> Unit,
    onReuse: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val ready = song.status == SongStatus.READY && song.files.isNotEmpty()
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = ready) { onPlay(0) },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MelodiaColors.coverFor(song.id)),
                ) {
                    Icon(
                        if (ready) (if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow) else Icons.Filled.GraphicEq,
                        contentDescription = if (ready) "Play" else null,
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (song.favorite) "★ " else "") + song.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = buildList {
                        add(song.request.customStyle.ifBlank { song.request.genre })
                        if (song.engine.isNotEmpty()) add(song.engine)
                        if (song.durationSec > 0) add(formatDuration((song.durationSec * 1000).toLong()))
                        add(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(song.createdAt)))
                    }
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (ready) {
                            DropdownMenuItem(text = { Text("Play") }, onClick = { menu = false; onPlay(0) })
                            DropdownMenuItem(text = { Text(if (song.favorite) "Remove favorite" else "Favorite") }, onClick = { menu = false; onFavorite() })
                            DropdownMenuItem(text = { Text("Save to Music") }, onClick = { menu = false; onSave() })
                            DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; onShare() })
                        }
                        DropdownMenuItem(text = { Text("Lyrics") }, onClick = { menu = false; onLyrics() })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Reuse settings") }, onClick = { menu = false; onReuse() })
                        DropdownMenuItem(text = { Text("Make again") }, onClick = { menu = false; onRegenerate() })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                    }
                }
            }

            when (song.status) {
                SongStatus.QUEUED, SongStatus.GENERATING -> {
                    Spacer(Modifier.height(10.dp))
                    val elapsed = if (song.startedAt > 0) now - song.startedAt else 0L
                    val estimate = song.estimateMs
                    if (estimate > 0 && song.status == SongStatus.GENERATING) {
                        LinearProgressIndicator(
                            progress = { (elapsed.toFloat() / estimate).coerceIn(0f, 0.97f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val timing = buildString {
                            if (song.status == SongStatus.GENERATING) {
                                append(formatDuration(elapsed))
                                if (estimate > 0) append(" of about ${formatDuration(estimate)}")
                            }
                        }
                        Hint(
                            listOf(song.stage.ifBlank { "Waiting" }, timing).filter { it.isNotBlank() }.joinToString(" · "),
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                SongStatus.FAILED, SongStatus.CANCELLED -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        song.error.ifBlank { "Something went wrong." },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (song.status == SongStatus.FAILED) MelodiaColors.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }
                SongStatus.READY -> {
                    if (song.files.size > 1) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            song.files.indices.forEach { i ->
                                TextButton(onClick = { onPlay(i) }) { Text("Play version ${i + 1}") }
                            }
                        }
                    }
                }
            }
        }
    }
}
