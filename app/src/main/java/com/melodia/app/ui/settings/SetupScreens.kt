@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.melodia.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melodia.app.appContainer
import com.melodia.app.engine.GenerationService
import com.melodia.app.engine.ModelCatalog
import com.melodia.app.engine.ModelFileState
import com.melodia.app.engine.formatBytes
import com.melodia.app.model.ModelPack
import com.melodia.app.model.Song
import com.melodia.app.model.SongRequest
import com.melodia.app.model.SongStatus
import com.melodia.app.ui.Navigator
import com.melodia.app.ui.Overlay
import com.melodia.app.ui.Tab
import com.melodia.app.ui.components.BackTopBar
import com.melodia.app.ui.components.CodeBlock
import com.melodia.app.ui.components.Heading
import com.melodia.app.ui.components.Hint
import com.melodia.app.ui.components.Para
import com.melodia.app.ui.components.SectionCard
import com.melodia.app.ui.components.SingleChoice
import com.melodia.app.ui.components.SwitchRow
import com.melodia.app.ui.theme.MelodiaColors
import com.melodia.app.ui.theme.MonoStyle
import com.melodia.app.util.Exporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun PhoneAiScreen(nav: Navigator) {
    val context = LocalContext.current
    val c = context.appContainer
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val files by c.models.state.collectAsStateWithLifecycle()
    val sizes by c.models.remoteSizes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val verdict = remember(settings.allowLowRam) { c.device.phoneVerdict(settings.allowLowRam) }

    LaunchedEffect(Unit) {
        c.models.fetchRemoteSizes(c.http, c.json)
        while (true) {
            c.models.refresh()
            delay(1000)
        }
    }

    val pack = settings.modelPack
    val packFiles = ModelCatalog.packFiles(pack)
    val packBytes = packFiles.sumOf { c.models.sizeOf(it) }
    val freeBytes = remember(files) { c.device.freeBytes(c.models.modelsDir) }
    val ready = c.models.phoneModelsReady()

    Scaffold(
        topBar = { BackTopBar("Phone AI", onBack = nav::pop) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("This phone") {
                Text(
                    "About ${c.device.ramClassGb} GB RAM · ${if (c.device.isArm64) "64-bit ARM" else "not 64-bit ARM"} · " +
                        "${formatBytes(freeBytes)} free",
                    style = MaterialTheme.typography.bodyMedium,
                )
                when {
                    !verdict.ok -> Hint(verdict.message.orEmpty(), color = MelodiaColors.Red)
                    verdict.message != null -> Hint(verdict.message, color = MelodiaColors.Amber)
                    else -> Hint("Good to go.", color = MelodiaColors.Green)
                }
                if (!c.embedded.isBundled()) {
                    Spacer(Modifier.height(6.dp))
                    Hint(
                        "This build doesn't include the on-phone engine (it's added by the GitHub build). " +
                            "You can still run it through Termux.",
                        color = MelodiaColors.Amber,
                    )
                    TextButton(onClick = { nav.push(Overlay.TermuxGuide) }) { Text("Termux guide") }
                }
            }

            SectionCard("Singer models") {
                SingleChoice(
                    listOf("Lite" to ModelPack.LITE.name, "Standard" to ModelPack.STANDARD.name),
                    pack.name,
                ) { v -> c.settings.update { it.copy(modelPack = ModelPack.valueOf(v)) } }
                Hint(
                    if (pack == ModelPack.LITE) {
                        "Lite: smaller and faster, best for 8 to 11 GB phones. Slightly less detailed sound."
                    } else {
                        "Standard: best quality, for 12 GB+ phones."
                    }
                )
                Spacer(Modifier.height(8.dp))
                packFiles.forEach { name -> ModelRow(files[name], name, sizes[name], c.models.sizeOf(name), onCancel = { c.models.cancel(name) }, onDelete = { c.models.delete(name) }) }
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    "Download on Wi-Fi only",
                    settings.wifiOnlyDownloads,
                    { v -> c.settings.update { it.copy(wifiOnlyDownloads = v) } },
                )
                val missing = packFiles.filter { files[it]?.installed != true && files[it]?.downloading != true }
                if (missing.isNotEmpty()) {
                    Button(onClick = {
                        val error = c.models.download(missing, settings.wifiOnlyDownloads)
                        if (error != null) scope.launch { snackbar.showSnackbar(error) }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Download ${pack.name.lowercase().replaceFirstChar { it.uppercase() }} pack (${formatBytes(missing.sumOf { c.models.sizeOf(it) })})")
                    }
                } else if (ready) {
                    Hint("Installed. Phone AI is ready.", color = MelodiaColors.Green)
                }
                Hint("Total for this pack: ${formatBytes(packBytes)}. Downloads keep going in the background.")
            }

            SectionCard("Songwriter brain (optional)") {
                Hint(
                    "A small extra model (about ${formatBytes(c.models.sizeOf(ModelCatalog.LM_SMALL))}) that writes lyrics " +
                        "for you and plans the song's structure. Needed for Simple mode and \"Write for me\"."
                )
                Spacer(Modifier.height(6.dp))
                ModelRow(
                    files[ModelCatalog.LM_SMALL], ModelCatalog.LM_SMALL, sizes[ModelCatalog.LM_SMALL],
                    c.models.sizeOf(ModelCatalog.LM_SMALL),
                    onCancel = { c.models.cancel(ModelCatalog.LM_SMALL) },
                    onDelete = { c.models.delete(ModelCatalog.LM_SMALL) },
                )
                val lm = files[ModelCatalog.LM_SMALL]
                if (lm?.installed != true && lm?.downloading != true) {
                    OutlinedButton(onClick = {
                        val error = c.models.download(listOf(ModelCatalog.LM_SMALL), settings.wifiOnlyDownloads)
                        if (error != null) scope.launch { snackbar.showSnackbar(error) }
                    }) { Text("Download songwriter") }
                }
            }

            // Files from the other pack that are still taking space.
            val otherDits = ModelCatalog.allDits.filter { it !in packFiles && files[it]?.installed == true }
            if (otherDits.isNotEmpty()) {
                SectionCard("Other downloaded models") {
                    otherDits.forEach { name ->
                        ModelRow(files[name], name, sizes[name], c.models.sizeOf(name), onCancel = {}, onDelete = { c.models.delete(name) })
                    }
                }
            }

            if (ready && verdict.ok && c.embedded.isBundled()) {
                SectionCard("Test it") {
                    Hint("Makes a 20-second instrumental clip to check the engine and measure how fast this phone is.")
                    Button(onClick = {
                        scope.launch {
                            val song = Song(
                                id = UUID.randomUUID().toString(),
                                title = "Engine test",
                                createdAt = System.currentTimeMillis(),
                                request = SongRequest(
                                    genre = "Lo-fi",
                                    moods = listOf("Chill"),
                                    instruments = listOf("Rhodes", "Drum kit"),
                                    instrumental = true,
                                    durationSec = 20,
                                    bpm = 80,
                                ),
                                status = SongStatus.QUEUED,
                                stage = "Waiting",
                            )
                            c.songs.add(song)
                            GenerationService.enqueue(context, song.id)
                            nav.goTo(Tab.LIBRARY)
                        }
                    }) { Text("Run a quick test") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModelRow(
    state: ModelFileState?,
    name: String,
    remoteSize: Long?,
    approx: Long,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ModelCatalog.label(name), style = MaterialTheme.typography.bodyLarge)
                Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                state?.installed == true -> TextButton(onClick = onDelete) { Text("Delete") }
                state?.downloading == true -> TextButton(onClick = onCancel) { Text("Cancel") }
                else -> Text(formatBytes(remoteSize ?: approx), style = MaterialTheme.typography.bodySmall)
            }
        }
        when {
            state?.installed == true -> Hint("Installed · ${formatBytes(state.installedBytes)}", color = MelodiaColors.Green)
            state?.downloading == true -> {
                val total = state.totalBytes.coerceAtLeast(1)
                LinearProgressIndicator(
                    progress = { (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
                Hint(
                    "${formatBytes(state.downloadedBytes)} of ${formatBytes(total)}" +
                        (state.note?.let { " · $it" } ?: ""),
                )
            }
            state?.note != null -> Hint(state.note, color = MelodiaColors.Red)
        }
    }
}

@Composable
fun PcSetupScreen(nav: Navigator) {
    GuidePage(title = "Set up PC AI", onBack = nav::pop) {
        Para(
            "Your computer runs the same singer as the phone, but much faster, and it can use bigger, better models. " +
                "Melodia talks to it over your home Wi-Fi."
        )
        Heading("1. What you need")
        Para("A Windows, Linux or Mac computer. An NVIDIA graphics card with 4 GB+ of video memory works best. AMD and Intel cards work through Vulkan, Apple Silicon Macs through Metal, and it also runs (slowly) on the processor alone.")
        Heading("2. Get the engine (acestep.cpp)")
        Para("Windows: ready-made builds are linked from the project page. Or build it yourself (needs Git, CMake and a C++ compiler):")
        CodeBlock("git clone --recurse-submodules https://github.com/ServeurpersoCom/acestep.cpp\ncd acestep.cpp")
        Para("Then run the build script that matches your computer:")
        CodeBlock("buildcuda.cmd      (Windows, NVIDIA)\nbuildvulkan.cmd    (Windows, AMD/Intel)\n./buildcuda.sh     (Linux, NVIDIA)\n./buildvulkan.sh   (Linux, AMD/Intel)\n./buildcpu.sh      (Mac or no GPU)")
        Heading("3. Download the models")
        Para("Inside the acestep.cpp folder, make a models folder and download these four files (about 5.4 GB):")
        CodeBlock(
            listOf(
                ModelCatalog.VAE,
                ModelCatalog.TEXT_ENCODER,
                ModelCatalog.DIT_STANDARD,
                "acestep-5Hz-lm-1.7B-Q8_0.gguf",
            ).joinToString("\n") { "curl -L -o models/$it ${ModelCatalog.url(it)}" }
        )
        Para("With 16 GB+ of video memory you can use acestep-5Hz-lm-4B-Q8_0.gguf as the songwriter instead of the 1.7B one for better lyrics and structure.")
        Heading("4. Start it so the phone can reach it")
        CodeBlock("./build/ace-server --models models --host 0.0.0.0 --port 8085")
        Para("On Windows, run ace-server.exe from the build folder with the same options. Allow it through the firewall when Windows asks (private networks).")
        Heading("5. Connect Melodia")
        Para("Find your computer's local address: on Windows run ipconfig, on Mac/Linux run ip addr or check Wi-Fi settings. It looks like 192.168.1.20.")
        Para("In Melodia: Settings, PC AI, type the address, then Test connection.")
        Heading("Safety")
        Para("This engine has no password. Only use it on your own Wi-Fi. To use it away from home, install the free Tailscale app on both devices and use the PC's Tailscale address instead of opening it to the internet.")
    }
}

@Composable
fun TermuxGuideScreen(nav: Navigator) {
    val c = LocalContext.current.appContainer
    val port = c.settings.current.termuxPort
    GuidePage(title = "Termux engine", onBack = nav::pop) {
        Para(
            "If this copy of Melodia doesn't include the on-phone engine, you can run it yourself inside Termux, " +
                "a free Linux terminal app. Everything still stays on your phone and works offline afterwards."
        )
        Heading("1. Install Termux")
        Para("Install Termux from F-Droid or its GitHub page. The Play Store version is outdated.")
        Heading("2. Build the engine (10 to 20 minutes)")
        CodeBlock(
            "pkg update -y && pkg upgrade -y\n" +
                "pkg install -y git cmake build-essential openblas curl\n" +
                "git clone --recurse-submodules https://github.com/ServeurpersoCom/acestep.cpp\n" +
                "cd acestep.cpp\n" +
                "./buildtermux.sh"
        )
        Heading("3. Download the Lite models (about 2.5 GB)")
        CodeBlock(
            "mkdir -p models\n" + listOf(ModelCatalog.VAE, ModelCatalog.TEXT_ENCODER, ModelCatalog.DIT_LITE)
                .joinToString("\n") { "curl -L -o models/$it ${ModelCatalog.url(it)}" }
        )
        Para("Optional songwriter for Simple mode and \"Write for me\":")
        CodeBlock("curl -L -o models/${ModelCatalog.LM_SMALL} ${ModelCatalog.url(ModelCatalog.LM_SMALL)}")
        Heading("4. Start it")
        CodeBlock("termux-wake-lock\n./build/ace-server --models models --host 127.0.0.1 --port $port")
        Para("Leave Termux running, come back to Melodia and the engine chip shows \"Phone AI (Termux)\". Next time you only need step 4 (run cd acestep.cpp first).")
        Heading("If Android closes Termux")
        Para("Android 12+ may stop long-running background processes. Keep Termux's notification visible, and in Settings, Apps, Termux, allow unrestricted battery use.")
    }
}

@Composable
fun EngineLogScreen(nav: Navigator) {
    val context = LocalContext.current
    val c = context.appContainer
    var text by remember { mutableStateOf(c.embedded.tail(300)) }
    Scaffold(
        topBar = {
            BackTopBar("Engine log", onBack = nav::pop, actions = {
                IconButton(onClick = { text = c.embedded.tail(300) }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                IconButton(onClick = {
                    if (c.embedded.logFile.exists()) Exporter.share(context, c.embedded.logFile, "Melodia engine log")
                }) { Icon(Icons.Filled.Share, contentDescription = "Share log") }
            })
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (text.isBlank()) {
                Hint("The on-phone engine hasn't run yet.")
            } else {
                SelectionContainer { Text(text, style = MonoStyle) }
            }
        }
    }
}

@Composable
fun AboutScreen(nav: Navigator) {
    GuidePage(title = "About Melodia", onBack = nav::pop) {
        Para("Melodia turns your lyrics into songs with AI singing, on your phone or your own computer. No account, no cloud service and no per-song fees.")
        Heading("How it works")
        Para("Songs are made by ACE-Step 1.5, an open-source music model by ACE Studio and StepFun, running through acestep.cpp. Your voice settings become a style description; your lyrics are sent with section tags and the language you choose.")
        Heading("Use it responsibly")
        Para("Make original music. Don't imitate real singers or reuse other people's lyrics without permission, and say that a song was made with AI when you share it.")
        Heading("Open-source licences")
        Para("ACE-Step 1.5: MIT licence, github.com/ace-step/ACE-Step-1.5")
        Para("acestep.cpp: MIT licence, github.com/ServeurpersoCom/acestep.cpp")
        Para("GGML: MIT licence, github.com/ggml-org/ggml")
        Para("Model files: huggingface.co/${ModelCatalog.REPO} (see each model card)")
        Para("AndroidX, Jetpack Compose, Media3, OkHttp, kotlinx: Apache 2.0 licence")
    }
}

@Composable
private fun GuidePage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = { BackTopBar(title, onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            content()
            Spacer(Modifier.height(32.dp))
        }
    }
}
