@file:OptIn(ExperimentalMaterial3Api::class)

package com.melodia.app.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.melodia.app.appContainer
import com.melodia.app.engine.PromptBuilder
import com.melodia.app.model.Catalog
import com.melodia.app.model.VoiceProfile
import com.melodia.app.ui.components.BackTopBar
import com.melodia.app.ui.components.ChipGroup
import com.melodia.app.ui.components.Dropdown
import com.melodia.app.ui.components.Hint
import com.melodia.app.ui.components.SectionCard
import com.melodia.app.ui.components.SingleChoice
import com.melodia.app.ui.theme.MelodiaColors
import com.melodia.app.util.Exporter
import com.melodia.app.util.VoiceRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@Composable
fun VoiceDesignerScreen(voiceId: String?, onDone: (savedId: String?) -> Unit) {
    val context = LocalContext.current
    val c = context.appContainer
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val original = remember(voiceId) { voiceId?.let { c.voices.get(it) } }
    var voice by remember(voiceId) {
        mutableStateOf(original ?: VoiceProfile(id = UUID.randomUUID().toString(), name = "My voice"))
    }
    var adapters by remember { mutableStateOf(c.models.adapterNames()) }
    var deleteDialog by remember { mutableStateOf(false) }

    val recorder = remember { VoiceRecorder() }
    var recording by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) { onDispose { recorder.stop() } }
    LaunchedEffect(recording) {
        while (recording) {
            level = recorder.level
            delay(100)
        }
        level = 0f
    }

    val referenceFile = File(c.voices.voicesRoot, "${voice.id}.wav")

    fun startRecording() {
        runCatching {
            recorder.start(referenceFile, maxSeconds = 30) { file ->
                scope.launch {
                    recording = false
                    voice = voice.copy(refAudioPath = file.absolutePath)
                }
            }
            recording = true
        }.onFailure { e -> scope.launch { snackbar.showSnackbar(e.message ?: "Couldn't start recording.") } }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else scope.launch { snackbar.showSnackbar("Microphone permission is needed to record.") }
    }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = Exporter.displayName(context, uri).orEmpty().lowercase()
                if (!(name.endsWith(".wav") || name.endsWith(".mp3"))) {
                    snackbar.showSnackbar("Please pick a WAV or MP3 file.")
                    return@launch
                }
                val ext = if (name.endsWith(".mp3")) "mp3" else "wav"
                val target = File(c.voices.voicesRoot, "${voice.id}.$ext")
                Exporter.importUri(context, uri, target)
                    .onSuccess { voice = voice.copy(refAudioPath = it.absolutePath) }
                    .onFailure { snackbar.showSnackbar(it.message ?: "Couldn't import the file.") }
            }
        }
    }

    val pickAdapter = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = Exporter.displayName(context, uri) ?: "adapter.safetensors"
                if (!name.endsWith(".safetensors")) {
                    snackbar.showSnackbar("Voice adapters are .safetensors files.")
                    return@launch
                }
                Exporter.importUri(context, uri, File(c.models.adaptersDir, name))
                    .onSuccess {
                        adapters = c.models.adapterNames()
                        voice = voice.copy(adapter = name)
                    }
                    .onFailure { snackbar.showSnackbar(it.message ?: "Couldn't import the adapter.") }
            }
        }
    }

    fun save() {
        val clean = voice.copy(name = voice.name.trim().ifEmpty { "My voice" })
        scope.launch {
            c.voices.save(clean)
            onDone(clean.id)
        }
    }

    Scaffold(
        topBar = {
            BackTopBar(
                title = if (original == null) "New voice" else "Edit voice",
                onBack = { onDone(null) },
                actions = {
                    if (original != null) {
                        IconButton(onClick = { deleteDialog = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete voice") }
                    }
                    TextButton(onClick = ::save) { Text("Save") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
            OutlinedTextField(
                value = voice.name,
                onValueChange = { voice = voice.copy(name = it) },
                label = { Text("Voice name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionCard("Singer") {
                SingleChoice(Catalog.voices.map { it to it }, voice.voice) { v ->
                    voice = voice.copy(voice = v, range = if (voice.range in Catalog.rangesFor(v)) voice.range else "")
                }
                val ranges = Catalog.rangesFor(voice.voice)
                if (ranges.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Range", style = MaterialTheme.typography.labelLarge)
                    ChipGroup(ranges, listOfNotNull(voice.range.takeIf { it.isNotEmpty() }), { list ->
                        voice = voice.copy(range = list.firstOrNull().orEmpty())
                    }, single = true)
                }
            }

            SectionCard("Tone (up to 3)") {
                ChipGroup(Catalog.timbres, voice.timbre, { voice = voice.copy(timbre = it) }, max = 3)
            }

            SectionCard("Delivery (up to 3)") {
                ChipGroup(Catalog.deliveries, voice.delivery, { voice = voice.copy(delivery = it) }, max = 3)
                Spacer(Modifier.height(10.dp))
                Text("Emotion", style = MaterialTheme.typography.labelLarge)
                ChipGroup(Catalog.emotions, listOfNotNull(voice.emotion.takeIf { it.isNotEmpty() }), {
                    voice = voice.copy(emotion = it.firstOrNull().orEmpty())
                }, single = true)
                Spacer(Modifier.height(10.dp))
                Text("Vibrato", style = MaterialTheme.typography.labelLarge)
                SingleChoice(Catalog.vibratos.map { it to it }, voice.vibrato) { voice = voice.copy(vibrato = it) }
            }

            SectionCard("Techniques & style", collapsible = true) {
                ChipGroup(Catalog.techniques, voice.techniques, { voice = voice.copy(techniques = it) })
                Spacer(Modifier.height(10.dp))
                Text("Singing style", style = MaterialTheme.typography.labelLarge)
                ChipGroup(Catalog.styles, listOfNotNull(voice.style.takeIf { it.isNotEmpty() }), {
                    voice = voice.copy(style = it.firstOrNull().orEmpty())
                }, single = true)
                Spacer(Modifier.height(10.dp))
                Text("Microphone & room", style = MaterialTheme.typography.labelLarge)
                ChipGroup(Catalog.mics, listOfNotNull(voice.mic.takeIf { it.isNotEmpty() }), {
                    voice = voice.copy(mic = it.firstOrNull().orEmpty())
                }, single = true)
            }

            SectionCard("Describe it in your own words") {
                OutlinedTextField(
                    value = voice.description,
                    onValueChange = { voice = voice.copy(description = it.take(200)) },
                    placeholder = { Text("e.g. a warm, slightly husky voice singing softly late at night") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("Describe the sound, not a famous singer's name.")
            }

            SectionCard("Section by section", collapsible = true, startExpanded = voice.sectionDelivery.isNotEmpty()) {
                Hint("How should each part be sung? Leave on Default to keep the overall delivery.")
                Catalog.sections.forEach { section ->
                    Spacer(Modifier.height(8.dp))
                    Dropdown(
                        label = section,
                        value = voice.sectionDelivery[section].orEmpty(),
                        options = listOf("" to "Default") + Catalog.sectionNotes.map { it to it },
                        onSelect = { v ->
                            val map = voice.sectionDelivery.toMutableMap()
                            if (v.isEmpty()) map.remove(section) else map[section] = v
                            voice = voice.copy(sectionDelivery = map)
                        },
                    )
                }
            }

            SectionCard("Sound like me (optional)", collapsible = true, startExpanded = voice.refAudioPath.isNotEmpty()) {
                Hint(
                    "Record 10 to 30 seconds of your own singing, or pick a WAV/MP3. The AI uses it as a tone " +
                        "reference so the singer sounds closer to it. Use your own voice or one you have permission to use."
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (recording) {
                        Button(onClick = { recorder.stop() }) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Stop")
                        }
                    } else {
                        Button(onClick = {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            if (granted) startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }) {
                            Icon(Icons.Filled.Mic, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Record")
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { pickAudio.launch("audio/*") }, enabled = !recording) { Text("Pick a file") }
                }
                if (recording) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Hint("Recording… stops by itself after 30 seconds.")
                }
                if (voice.refAudioPath.isNotEmpty() && !recording) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Reference: " + File(voice.refAudioPath).name,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { voice = voice.copy(refAudioPath = "", refConsent = false) }) { Text("Remove") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = voice.refConsent, onCheckedChange = { voice = voice.copy(refConsent = it) })
                        Text(
                            "This is my voice, or I have the singer's permission.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!voice.refConsent) {
                        Hint("The reference is only used once this box is ticked.", color = MelodiaColors.Amber)
                    }
                }
            }

            SectionCard("Voice adapter (advanced)", collapsible = true, startExpanded = voice.adapter.isNotEmpty()) {
                Hint(
                    "A LoRA adapter is a small extra model trained on a voice or style (for example on your own " +
                        "recordings, on a PC). Import the .safetensors file here."
                )
                Spacer(Modifier.height(8.dp))
                Dropdown(
                    label = "Adapter",
                    value = voice.adapter,
                    options = listOf("" to "None") + adapters.map { it to it },
                    onSelect = { voice = voice.copy(adapter = it) },
                )
                if (voice.adapter.isNotEmpty()) {
                    Text("Strength: ${"%.2f".format(voice.adapterScale)}", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = voice.adapterScale,
                        onValueChange = { voice = voice.copy(adapterScale = it) },
                        valueRange = 0.2f..1.2f,
                    )
                }
                TextButton(onClick = { pickAdapter.launch("*/*") }) { Text("Import adapter") }
                Hint("Adapters work with Phone AI. On a PC, put the file in the engine's adapters folder.")
            }

            SectionCard("What the singer receives") {
                Text(PromptBuilder.voiceCaption(voice), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (deleteDialog && original != null) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("Delete \"${original.name}\"?") },
            text = { Text("Songs you already made keep their sound. New songs can't use this voice.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteDialog = false
                    scope.launch {
                        c.voices.delete(original.id)
                        onDone(null)
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancel") } },
        )
    }
}
