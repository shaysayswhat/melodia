@file:OptIn(ExperimentalMaterial3Api::class)

package com.melodia.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melodia.app.BuildConfig
import com.melodia.app.appContainer
import com.melodia.app.engine.EngineRouter
import com.melodia.app.model.EngineMode
import com.melodia.app.ui.Navigator
import com.melodia.app.ui.Overlay
import com.melodia.app.ui.components.Dropdown
import com.melodia.app.ui.components.Hint
import com.melodia.app.ui.components.SectionCard
import com.melodia.app.ui.components.SingleChoice
import com.melodia.app.ui.components.StatusDot
import com.melodia.app.ui.components.SwitchRow
import com.melodia.app.ui.theme.MelodiaColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: Navigator, snackbar: SnackbarHostState) {
    val c = LocalContext.current.appContainer
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val status by c.router.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var pcUrl by remember { mutableStateOf(settings.pcUrl) }
    var termuxPort by remember { mutableStateOf(settings.termuxPort.toString()) }

    fun check(announce: Boolean) {
        scope.launch {
            checking = true
            val s = c.router.refresh()
            checking = false
            if (announce) {
                snackbar.showSnackbar(
                    when {
                        s.pcUrl.isEmpty() -> "No PC address entered."
                        s.pcUp -> "Connected to your PC" + (s.pcProps?.version?.takeIf { it.isNotEmpty() }?.let { " (engine $it)" } ?: "")
                        else -> "Can't reach ${s.pcUrl}. Check the address, that the engine is running, and that both are on the same Wi-Fi."
                    }
                )
            }
        }
    }

    LaunchedEffect(Unit) { check(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
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
            SectionCard("Engine") {
                SingleChoice(
                    listOf("Auto" to EngineMode.AUTO.name, "Phone AI" to EngineMode.PHONE.name, "PC AI" to EngineMode.PC.name),
                    settings.engineMode.name,
                ) { v -> c.settings.update { it.copy(engineMode = EngineMode.valueOf(v)) } }
                Spacer(Modifier.height(8.dp))
                StatusLine("Phone AI", status.phoneReady, if (status.phoneReady) "Ready" else status.phoneProblem())
                StatusLine("Termux engine", status.termuxUp, if (status.termuxUp) "Running" else "Not running")
                StatusLine(
                    "PC AI",
                    status.pcUp,
                    when {
                        status.pcUrl.isEmpty() -> "Not set up"
                        status.pcUp -> "Connected"
                        else -> "Not reachable"
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { check(false) }, enabled = !checking) { Text("Check again") }
                    if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            SectionCard("Phone AI") {
                Hint(
                    "Makes songs with natural sung vocals right on this phone, offline. Needs a one-time model " +
                        "download (about 2.5 to 3.5 GB, plus 2 GB for the songwriter). Expect about 3 to 5 minutes for a " +
                        "30-second preview and roughly 6 to 9 minutes per minute of song."
                )
                Spacer(Modifier.height(4.dp))
                Hint(
                    "This phone: about ${c.device.ramClassGb} GB RAM" +
                        (if (c.device.isArm64) ", 64-bit ARM" else ", not 64-bit ARM") +
                        (if (status.phoneBundled) ", engine included" else ", engine not included in this build"),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { nav.push(Overlay.PhoneAi) }) { Text("Models & setup") }
                SwitchRow(
                    "Songwriter brain on the phone",
                    settings.phoneSongwriter,
                    { v -> c.settings.update { it.copy(phoneSongwriter = v) } },
                    "Plans each song before singing, so the singer follows your lyrics closely and in order. " +
                        "Adds a minute or two per song. Needs the songwriter download.",
                    enabled = c.models.songwriterInstalled(),
                )
                SwitchRow(
                    "Try on phones with less RAM",
                    settings.allowLowRam,
                    { v -> c.settings.update { it.copy(allowLowRam = v) } },
                    "May crash or be very slow below 8 GB.",
                )
            }

            SectionCard("PC AI") {
                Hint("Use your computer's graphics card for faster, higher-quality songs. Both devices must be on the same Wi-Fi.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pcUrl,
                    onValueChange = {
                        pcUrl = it
                        c.settings.update { s -> s.copy(pcUrl = it.trim()) }
                    },
                    label = { Text("PC address") },
                    placeholder = { Text("192.168.1.20:8085") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (pcUrl.isNotBlank()) Hint("Will connect to " + EngineRouter.normalizeUrl(pcUrl))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilledTonalButton(onClick = { check(true) }, enabled = !checking) { Text("Test connection") }
                    TextButton(onClick = { nav.push(Overlay.PcSetup) }) { Text("How to set up") }
                }
                val props = status.pcProps
                if (status.pcUp && props != null) {
                    Spacer(Modifier.height(8.dp))
                    if (props.dit.size > 1) {
                        Dropdown(
                            label = "Singer model",
                            value = settings.pcDitModel,
                            options = listOf("" to "Engine default") + props.dit.map { it to it },
                            onSelect = { v -> c.settings.update { it.copy(pcDitModel = v) } },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (props.lm.isNotEmpty()) {
                        Dropdown(
                            label = "Songwriter model",
                            value = settings.pcLmModel,
                            options = listOf("" to "Engine default") + props.lm.map { it to it },
                            onSelect = { v -> c.settings.update { it.copy(pcLmModel = v) } },
                        )
                    } else {
                        Hint("No songwriter model on the PC. Custom lyrics still work.")
                    }
                }
                SwitchRow(
                    "Songwriter brain on the PC",
                    settings.pcSongwriter,
                    { v -> c.settings.update { it.copy(pcSongwriter = v) } },
                    "Recommended: the singer follows your lyrics much more closely. Uses the biggest songwriter on the PC.",
                )
            }

            SectionCard("Termux engine (advanced)", collapsible = true, startExpanded = false) {
                Hint("Runs the same singer inside the free Termux app on this phone. Useful if this build has no engine included.")
                OutlinedTextField(
                    value = termuxPort,
                    onValueChange = { v ->
                        termuxPort = v.filter { it.isDigit() }.take(5)
                        termuxPort.toIntOrNull()?.let { p -> c.settings.update { it.copy(termuxPort = p) } }
                    },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                TextButton(onClick = { nav.push(Overlay.TermuxGuide) }) { Text("Termux setup guide") }
            }

            SectionCard("Sound & style") {
                Text("MP3 quality", style = MaterialTheme.typography.labelLarge)
                SingleChoice(
                    listOf("128 kbps" to "128", "192 kbps" to "192", "256 kbps" to "256", "320 kbps" to "320"),
                    settings.mp3Bitrate.toString(),
                ) { v -> c.settings.update { it.copy(mp3Bitrate = v.toInt()) } }
                SwitchRow(
                    "Let the AI refine my style text",
                    settings.refineStyle,
                    { v -> c.settings.update { it.copy(refineStyle = v) } },
                    "Only when the Songwriter brain runs. Off keeps your voice instructions word for word.",
                )
                SwitchRow(
                    "Keep the screen on while making a song",
                    settings.keepScreenOn,
                    { v -> c.settings.update { it.copy(keepScreenOn = v) } },
                    "Some phones slow down background work when the screen is off.",
                )
            }

            SectionCard("Help") {
                TextButton(onClick = { nav.push(Overlay.EngineLog) }) { Text("Engine log") }
                TextButton(onClick = { nav.push(Overlay.About) }) { Text("About & licences") }
                Hint("Melodia ${BuildConfig.VERSION_NAME}")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusLine(title: String, ok: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        StatusDot(ok)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MelodiaColors.Green else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
