@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.melodia.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melodia.app.appContainer
import com.melodia.app.engine.GenerationService
import com.melodia.app.ui.create.CreateScreen
import com.melodia.app.ui.library.LibraryScreen
import com.melodia.app.ui.player.MiniPlayer
import com.melodia.app.ui.player.PlayerScreen
import com.melodia.app.ui.settings.AboutScreen
import com.melodia.app.ui.settings.EngineLogScreen
import com.melodia.app.ui.settings.PcSetupScreen
import com.melodia.app.ui.settings.PhoneAiScreen
import com.melodia.app.ui.settings.SettingsScreen
import com.melodia.app.ui.settings.TermuxGuideScreen
import com.melodia.app.ui.voice.VoiceDesignerScreen

@Composable
fun MelodiaRoot(openLibraryRequests: Int) {
    val c = LocalContext.current.appContainer
    val nav = remember { Navigator() }
    val snackbar = remember { SnackbarHostState() }
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val generating by GenerationService.active.collectAsStateWithLifecycle()

    LaunchedEffect(openLibraryRequests) {
        if (openLibraryRequests > 0) nav.goTo(Tab.LIBRARY)
    }

    // Some phones throttle work when the screen turns off; keep it on while a song is being made.
    val view = LocalView.current
    val keepOn = generating != null && settings.keepScreenOn
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    MiniPlayer(onOpen = { nav.push(Overlay.Player) })
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NavigationBarItem(
                            selected = nav.tab == Tab.CREATE,
                            onClick = { nav.goTo(Tab.CREATE) },
                            icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                            label = { Text("Create") },
                        )
                        NavigationBarItem(
                            selected = nav.tab == Tab.LIBRARY,
                            onClick = { nav.goTo(Tab.LIBRARY) },
                            icon = { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
                            label = { Text("Library") },
                        )
                        NavigationBarItem(
                            selected = nav.tab == Tab.SETTINGS,
                            onClick = { nav.goTo(Tab.SETTINGS) },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            label = { Text("Settings") },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (nav.tab) {
                    Tab.CREATE -> CreateScreen(nav, snackbar)
                    Tab.LIBRARY -> LibraryScreen(nav, snackbar)
                    Tab.SETTINGS -> SettingsScreen(nav, snackbar)
                }
            }
        }

        val top = nav.stack.lastOrNull()
        if (top != null) {
            BackHandler { nav.pop() }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when (top) {
                    is Overlay.VoiceDesigner -> VoiceDesignerScreen(top.voiceId) { savedId ->
                        nav.pop()
                        if (savedId != null) c.settings.updateDraft { it.copy(voiceProfileId = savedId) }
                    }
                    Overlay.Player -> PlayerScreen(onBack = nav::pop)
                    Overlay.PhoneAi -> PhoneAiScreen(nav)
                    Overlay.PcSetup -> PcSetupScreen(nav)
                    Overlay.TermuxGuide -> TermuxGuideScreen(nav)
                    Overlay.EngineLog -> EngineLogScreen(nav)
                    Overlay.About -> AboutScreen(nav)
                }
            }
        }
    }

    if (!settings.welcomeSeen) {
        AlertDialog(
            onDismissRequest = { c.settings.update { it.copy(welcomeSeen = true) } },
            title = { Text("Welcome to Melodia") },
            text = {
                Text(
                    "Melodia sings your lyrics with AI, without any cloud service.\n\n" +
                        "Phone AI: runs on this phone, offline, after a one-time download of about 2.5 GB. Needs 8 GB+ RAM. " +
                        "Each song takes a few minutes.\n\n" +
                        "PC AI: uses your computer's graphics card on the same Wi-Fi. Faster and higher quality.\n\n" +
                        "Set up at least one to start."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    c.settings.update { it.copy(welcomeSeen = true) }
                    nav.push(Overlay.PhoneAi)
                }) { Text("Set up Phone AI") }
            },
            dismissButton = {
                TextButton(onClick = {
                    c.settings.update { it.copy(welcomeSeen = true) }
                    nav.push(Overlay.PcSetup)
                }) { Text("Use my PC") }
            },
        )
    }
}
