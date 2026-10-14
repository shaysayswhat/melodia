@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.melodia.app.ui.create

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melodia.app.engine.EngineStatus
import com.melodia.app.engine.Notifications
import com.melodia.app.engine.PromptBuilder
import com.melodia.app.model.Catalog
import com.melodia.app.model.EngineMode
import com.melodia.app.model.PronunciationFix
import com.melodia.app.model.SongRequest
import com.melodia.app.model.VoiceProfile
import com.melodia.app.ui.Navigator
import com.melodia.app.ui.Overlay
import com.melodia.app.ui.Tab
import com.melodia.app.ui.components.ChipGroup
import com.melodia.app.ui.components.Dropdown
import com.melodia.app.ui.components.GradientButton
import com.melodia.app.ui.components.Hint
import com.melodia.app.ui.components.SectionCard
import com.melodia.app.ui.components.SingleChoice
import com.melodia.app.ui.components.StatusDot
import com.melodia.app.ui.components.SwitchRow
import com.melodia.app.ui.theme.MelodiaColors
import com.melodia.app.ui.theme.MonoStyle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun CreateScreen(nav: Navigator, snackbar: SnackbarHostState) {
    val vm: CreateViewModel = viewModel()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val voices by vm.voices.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var warning by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var engineDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun doCreate(preview: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.canPost(context)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val error = vm.create(preview) { nav.goTo(Tab.LIBRARY) }
        if (error != null) scope.launch { snackbar.showSnackbar(error) }
    }

    fun requestCreate(preview: Boolean) {
        val w = vm.preflightWarning()
        if (w != null) warning = w to preview else doCreate(preview)
    }

    val (engineLabel, engineOk) = engine.summary(settings.engineMode)
    val selectedVoice = voices.firstOrNull { it.id == draft.voiceProfileId }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Create") },
                actions = {
                    AssistChip(
                        onClick = { engineDialog = true },
                        label = { Text(engineLabel) },
                        leadingIcon = { StatusDot(engineOk) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (busy != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(busy ?: "", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GradientButton(
                            text = "Create song",
                            onClick = { requestCreate(preview = false) },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { requestCreate(preview = true) }) { Text("Preview 30s") }
                    }
                    if (!engineOk && engine.checked) {
                        Hint(
                            "No engine is ready yet. Tap the engine chip at the top to set one up.",
                            color = MelodiaColors.Amber,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoice(
                options = listOf("Custom" to "custom", "Simple" to "simple"),
                value = if (draft.simpleMode) "simple" else "custom",
                onSelect = { v -> vm.update { it.copy(simpleMode = v == "simple") } },
            )

            if (draft.simpleMode) {
                SimpleCard(draft, vm)
            } else {
                PresetRow(vm)
                TitleCard(draft, vm, onClear = { clearDialog = true })
                LyricsCard(draft, vm, busy != null)
                if (!draft.instrumental) FixesCard(draft, vm)
                StyleCard(draft, vm)
                InstrumentsCard(draft, vm)
            }
            if (!draft.instrumental) {
                VoiceCard(voices, selectedVoice, onSelect = { id -> vm.update { it.copy(voiceProfileId = id) } }, nav = nav)
            }
            SongSettingsCard(draft, vm)
            PreviewCard(draft, selectedVoice)
            Spacer(Modifier.height(24.dp))
        }
    }

    warning?.let { (message, preview) ->
        AlertDialog(
            onDismissRequest = { warning = null },
            title = { Text("Before you start") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    warning = null
                    doCreate(preview)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { warning = null }) { Text("Cancel") } },
        )
    }

    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text("Start a new song?") },
            text = { Text("This clears the title, lyrics and pronunciation fixes. Style and voice stay.") },
            confirmButton = {
                TextButton(onClick = {
                    clearDialog = false
                    vm.clearLyrics()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Keep") } },
        )
    }

    if (engineDialog) {
        EngineDialog(
            status = engine,
            mode = settings.engineMode,
            onMode = vm::setEngineMode,
            onRefresh = vm::refreshEngines,
            onSetup = { overlay ->
                engineDialog = false
                nav.push(overlay)
            },
            onDismiss = { engineDialog = false },
        )
    }
}

@Composable
private fun SimpleCard(draft: SongRequest, vm: CreateViewModel) {
    SectionCard("Describe your song") {
        OutlinedTextField(
            value = draft.description,
            onValueChange = { v -> vm.update { it.copy(description = v) } },
            placeholder = { Text("A rainy-day Bangla love song with acoustic guitar and soft female vocals") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Hint("The Songwriter brain writes the lyrics and picks the details. You can still choose the voice below.")
        SwitchRow("Instrumental", draft.instrumental, { v -> vm.update { it.copy(instrumental = v) } }, "No singing")
    }
}

@Composable
private fun PresetRow(vm: CreateViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
        items(Catalog.presets) { preset ->
            AssistChip(onClick = { vm.applyPreset(preset) }, label = { Text(preset.name) })
        }
    }
}

@Composable
private fun TitleCard(draft: SongRequest, vm: CreateViewModel, onClear: () -> Unit) {
    SectionCard("Title") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft.title,
                onValueChange = { v -> vm.update { it.copy(title = v) } },
                placeholder = { Text("Optional, taken from the first line if empty") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClear) { Icon(Icons.Filled.Delete, contentDescription = "Start a new song") }
        }
    }
}

@Composable
private fun LyricsCard(draft: SongRequest, vm: CreateViewModel, busy: Boolean) {
    var field by remember { mutableStateOf(TextFieldValue(draft.lyrics)) }
    LaunchedEffect(draft.lyrics) {
        if (draft.lyrics != field.text) field = TextFieldValue(draft.lyrics, TextRange(draft.lyrics.length))
    }

    fun insertTag(tag: String) {
        val text = field.text
        val start = field.selection.min.coerceIn(0, text.length)
        val end = field.selection.max.coerceIn(0, text.length)
        val before = text.substring(0, start)
        val after = text.substring(end)
        val prefix = when {
            before.isEmpty() || before.endsWith("\n\n") -> ""
            before.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        val insert = "$prefix[$tag]\n"
        val next = before + insert + after
        field = TextFieldValue(next, TextRange(before.length + insert.length))
        vm.update { it.copy(lyrics = next) }
    }

    val summary = if (draft.instrumental) "Instrumental" else "${draft.lyrics.lines().count { it.isNotBlank() }} lines"
    SectionCard("Lyrics", summary = summary) {
        SwitchRow(
            "Instrumental",
            draft.instrumental,
            { v -> vm.update { it.copy(instrumental = v) } },
            "No singing, music only",
        )
        if (!draft.instrumental) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Catalog.sections.forEach { tag ->
                    AssistChip(onClick = { insertTag(tag) }, label = { Text("[$tag]") })
                }
            }
            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = it
                    vm.update { r -> r.copy(lyrics = it.text) }
                },
                placeholder = { Text("[Verse 1]\nWrite your lyrics here…\n\n[Chorus]\n…") },
                minLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            val syllableWarning = remember(draft.lyrics) { longLineWarning(draft.lyrics) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CounterText("${draft.lyrics.length}/4000", modifier = Modifier.weight(1f))
                TextButton(onClick = vm::addStructure, enabled = draft.lyrics.isNotBlank()) { Text("Add structure") }
                TextButton(onClick = vm::writeLyrics, enabled = !busy) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Write for me")
                }
            }
            if (syllableWarning != null) Hint(syllableWarning, color = MelodiaColors.Amber)
            Hint("Leave lyrics empty to let the Songwriter brain write them. Put [Verse], [Chorus] tags on their own lines.")
        }
    }
}

@Composable
private fun CounterText(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

private fun longLineWarning(lyrics: String): String? {
    val counts = lyrics.lines().mapIndexed { i, line -> i to PromptBuilder.syllables(line) }.filter { it.second > 0 }
    if (counts.size < 2) return null
    val sorted = counts.map { it.second }.sorted()
    val median = sorted[sorted.size / 2]
    val worst = counts.maxByOrNull { it.second } ?: return null
    return if (worst.second >= 16 && worst.second > median * 1.6) {
        "Line ${worst.first + 1} has about ${worst.second} syllables, much longer than the others. Long lines can sound rushed."
    } else {
        null
    }
}

@Composable
private fun FixesCard(draft: SongRequest, vm: CreateViewModel) {
    SectionCard(
        "Pronunciation fixes",
        summary = if (draft.fixes.isEmpty()) "Teach the singer how to say a word" else "${draft.fixes.size} fixes",
        collapsible = true,
        startExpanded = draft.fixes.isNotEmpty(),
    ) {
        Hint("If a word comes out wrong, type how it should sound. Only the singer sees the respelling; your lyrics stay as written. Example: Dhaka → Dhaa-kaa")
        draft.fixes.forEachIndexed { index, fix ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(
                    value = fix.word,
                    onValueChange = { v -> vm.update { r -> r.copy(fixes = r.fixes.replaceAt(index, fix.copy(word = v))) } },
                    label = { Text("Word") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(
                    value = fix.soundsLike,
                    onValueChange = { v -> vm.update { r -> r.copy(fixes = r.fixes.replaceAt(index, fix.copy(soundsLike = v))) } },
                    label = { Text("Sounds like") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.update { r -> r.copy(fixes = r.fixes.filterIndexed { i, _ -> i != index }) } }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove fix")
                }
            }
        }
        TextButton(onClick = { vm.update { it.copy(fixes = it.fixes + PronunciationFix()) } }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Add a fix")
        }
    }
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> = mapIndexed { i, v -> if (i == index) value else v }

@Composable
private fun StyleCard(draft: SongRequest, vm: CreateViewModel) {
    val summary = listOf(draft.customStyle.ifBlank { draft.genre }, draft.moods.joinToString(", "))
        .filter { it.isNotBlank() }.joinToString(" · ")
    SectionCard("Theme & mood", summary = summary, collapsible = true) {
        Text("Genre", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        ChipGroup(
            options = Catalog.genres,
            selected = listOf(draft.genre),
            onChange = { list -> vm.update { it.copy(genre = list.firstOrNull() ?: it.genre) } },
            single = true,
        )
        OutlinedTextField(
            value = draft.customStyle,
            onValueChange = { v -> vm.update { it.copy(customStyle = v) } },
            label = { Text("Or describe the style yourself") },
            placeholder = { Text("e.g. 90s Bangla band rock with jangly guitars") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("Mood (up to 3)", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        ChipGroup(Catalog.moods, draft.moods, { list -> vm.update { it.copy(moods = list) } }, max = 3)
        OutlinedTextField(
            value = draft.theme,
            onValueChange = { v -> vm.update { it.copy(theme = v) } },
            label = { Text("What's it about?") },
            placeholder = { Text("e.g. missing home during the monsoon") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}

@Composable
private fun InstrumentsCard(draft: SongRequest, vm: CreateViewModel) {
    val summary = if (draft.instruments.isEmpty()) "Auto" else draft.instruments.joinToString(", ")
    SectionCard("Instruments", summary = summary, collapsible = true, startExpanded = false) {
        Catalog.instrumentGroups.forEach { (group, options) ->
            Text(group, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            ChipGroup(options, draft.instruments, { list ->
                val removed = draft.instruments.filter { it in options && it !in list }
                val others = draft.instruments.filter { it !in options }
                vm.update {
                    it.copy(
                        instruments = others + list.filter { o -> o in options },
                        leadInstrument = if (it.leadInstrument in removed) "" else it.leadInstrument,
                    )
                }
            })
        }
        if (draft.instruments.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Dropdown(
                label = "Lead instrument",
                value = draft.leadInstrument,
                options = listOf("" to "None") + draft.instruments.map { it to it },
                onSelect = { v -> vm.update { it.copy(leadInstrument = v) } },
            )
        } else {
            Hint("Nothing picked: the AI chooses instruments that fit the genre.")
        }
    }
}

@Composable
private fun VoiceCard(
    voices: List<VoiceProfile>,
    selected: VoiceProfile?,
    onSelect: (String) -> Unit,
    nav: Navigator,
) {
    SectionCard("Voice", summary = selected?.name ?: "Choose a voice") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            voices.forEach { v ->
                androidx.compose.material3.FilterChip(
                    selected = v.id == selected?.id,
                    onClick = { onSelect(v.id) },
                    label = { Text(v.name) },
                )
            }
            AssistChip(
                onClick = { nav.push(Overlay.VoiceDesigner(null)) },
                label = { Text("New voice") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        if (selected != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                PromptBuilder.voiceCaption(selected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { nav.push(Overlay.VoiceDesigner(selected.id)) }) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Edit this voice")
            }
        }
    }
}

@Composable
private fun SongSettingsCard(draft: SongRequest, vm: CreateViewModel) {
    val summary = buildString {
        append(formatSeconds(draft.durationSec))
        append(" · ")
        append(if (draft.bpm > 0) "${draft.bpm} BPM" else "auto tempo")
        if (draft.keyscale.isNotBlank()) append(" · ${draft.keyscale}")
        append(" · ${Catalog.languageName(draft.language)}")
    }
    SectionCard("Song settings", summary = summary, collapsible = true, startExpanded = false) {
        Text("Length: ${formatSeconds(draft.durationSec)}", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = draft.durationSec.toFloat(),
            onValueChange = { v -> vm.update { it.copy(durationSec = ((v / 10f).roundToInt() * 10).coerceIn(30, 240)) } },
            valueRange = 30f..240f,
            steps = 20,
        )
        Hint("Longer songs take longer to make, especially on the phone.")

        SwitchRow("Automatic tempo", draft.bpm == 0, { auto -> vm.update { it.copy(bpm = if (auto) 0 else 100) } })
        if (draft.bpm > 0) {
            Text("Tempo: ${draft.bpm} BPM", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = draft.bpm.toFloat(),
                onValueChange = { v -> vm.update { it.copy(bpm = v.roundToInt().coerceIn(50, 200)) } },
                valueRange = 50f..200f,
            )
        }

        Spacer(Modifier.height(8.dp))
        Dropdown(
            label = "Key",
            value = draft.keyscale,
            options = listOf("" to "Auto") + Catalog.keys.map { it to it },
            onSelect = { v -> vm.update { it.copy(keyscale = v) } },
        )
        Spacer(Modifier.height(8.dp))
        Dropdown(
            label = "Singing language",
            value = draft.language,
            options = Catalog.languages,
            onSelect = { v -> vm.update { it.copy(language = v) } },
        )
        Spacer(Modifier.height(12.dp))
        Text("Time signature", style = MaterialTheme.typography.labelLarge)
        SingleChoice(
            Catalog.timeSignatures.map { (value, label) -> label to value },
            draft.timeSignature,
        ) { v -> vm.update { it.copy(timeSignature = v) } }
        Spacer(Modifier.height(12.dp))
        Text("Versions", style = MaterialTheme.typography.labelLarge)
        SingleChoice(listOf("1 version" to "1", "2 versions" to "2"), draft.versions.toString()) { v ->
            vm.update { it.copy(versions = v.toInt()) }
        }
        Hint("Two versions give you a choice, but take twice as long.")
        OutlinedTextField(
            value = if (draft.seed >= 0) draft.seed.toString() else "",
            onValueChange = { v -> vm.update { it.copy(seed = v.filter { ch -> ch.isDigit() }.take(12).toLongOrNull() ?: -1) } },
            label = { Text("Seed (empty = random)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}

@Composable
private fun PreviewCard(draft: SongRequest, voice: VoiceProfile?) {
    val built = remember(draft, voice) { PromptBuilder.build(draft, if (draft.instrumental) null else voice) }
    SectionCard("What the singer receives", summary = "Style text and lyrics", collapsible = true, startExpanded = false) {
        Text("Style", style = MaterialTheme.typography.labelLarge)
        Text(built.caption.ifEmpty { "(empty)" }, style = MonoStyle)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("Lyrics", style = MaterialTheme.typography.labelLarge)
        Text(
            if (built.lyrics.isEmpty()) "(the Songwriter brain will write them)" else built.lyrics,
            style = MonoStyle,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Language: " + when (built.language) {
                "" -> "decided by the AI"
                "unknown" -> "none (instrumental)"
                else -> Catalog.languageName(built.language)
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun EngineDialog(
    status: EngineStatus,
    mode: EngineMode,
    onMode: (EngineMode) -> Unit,
    onRefresh: () -> Unit,
    onSetup: (Overlay) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Where should songs be made?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EngineOption(
                    "Auto", "Phone AI if it's set up, otherwise your PC.",
                    mode == EngineMode.AUTO, null,
                ) { onMode(EngineMode.AUTO) }
                EngineOption(
                    "Phone AI",
                    when {
                        status.phoneReady -> "Ready. Offline, natural vocals, takes a few minutes."
                        status.termuxUp -> "Using the Termux engine on this phone."
                        else -> status.phoneProblem()
                    },
                    mode == EngineMode.PHONE,
                    status.phoneReady || status.termuxUp,
                ) { onMode(EngineMode.PHONE) }
                EngineOption(
                    "PC AI",
                    when {
                        status.pcUp -> "Connected to ${status.pcUrl}"
                        status.pcUrl.isEmpty() -> "Not set up yet."
                        else -> "Can't reach ${status.pcUrl}"
                    },
                    mode == EngineMode.PC,
                    status.pcUp,
                ) { onMode(EngineMode.PC) }
                Row {
                    TextButton(onClick = { onSetup(Overlay.PhoneAi) }) { Text("Set up Phone AI") }
                    TextButton(onClick = { onSetup(Overlay.PcSetup) }) { Text("Connect PC") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onRefresh) { Text("Check again") } },
    )
}

@Composable
private fun EngineOption(title: String, subtitle: String, selected: Boolean, ok: Boolean?, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (ok != null) {
                    Spacer(Modifier.width(6.dp))
                    StatusDot(ok)
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun formatSeconds(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}:${s.toString().padStart(2, '0')} min" else "$s s"
}
