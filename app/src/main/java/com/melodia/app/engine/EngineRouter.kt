package com.melodia.app.engine

import com.melodia.app.data.SettingsRepository
import com.melodia.app.model.EngineMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

enum class EngineKind { PHONE, TERMUX, PC }

class ResolvedEngine(val kind: EngineKind, val label: String, val client: AceClient)

data class EngineStatus(
    val checked: Boolean = false,
    val phoneVerdict: DeviceInfo.Verdict = DeviceInfo.Verdict(false, null),
    val phoneBundled: Boolean = false,
    val phoneModels: Boolean = false,
    val termuxUp: Boolean = false,
    val pcUrl: String = "",
    val pcUp: Boolean = false,
    val pcProps: AceClient.Props? = null,
) {
    val phoneReady: Boolean get() = phoneVerdict.ok && phoneBundled && phoneModels

    /** Short label for the engine chip and whether a song can be made right now. */
    fun summary(mode: EngineMode): Pair<String, Boolean> {
        if (!checked) return "Checking engines…" to false
        return when (mode) {
            EngineMode.AUTO -> when {
                phoneReady -> "Phone AI" to true
                termuxUp -> "Phone AI (Termux)" to true
                pcUp -> "PC AI" to true
                else -> "No engine set up" to false
            }
            EngineMode.PHONE -> when {
                phoneReady -> "Phone AI" to true
                termuxUp -> "Phone AI (Termux)" to true
                else -> "Phone AI not set up" to false
            }
            EngineMode.PC -> if (pcUp) "PC AI" to true else "PC not reachable" to false
        }
    }

    fun phoneProblem(): String = when {
        !phoneVerdict.ok -> phoneVerdict.message ?: "This phone can't run Phone AI."
        !phoneBundled -> "This app build doesn't include the on-phone engine. Start the Termux engine " +
            "(Settings, Termux) or connect your PC."
        !phoneModels -> "Download the Phone AI models first: Settings, Phone AI."
        else -> ""
    }
}

class EngineRouter(
    private val settings: SettingsRepository,
    private val models: ModelManager,
    private val embedded: EmbeddedServer,
    private val device: DeviceInfo,
    private val http: OkHttpClient,
) {
    private val quickHttp: OkHttpClient = http.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    suspend fun refresh(): EngineStatus {
        val s = settings.current
        models.refresh()
        val termuxUp = AceClient("http://127.0.0.1:${s.termuxPort}", quickHttp).health()
        val pcUrl = normalizeUrl(s.pcUrl)
        var pcProps: AceClient.Props? = null
        val pcUp = if (pcUrl.isNotEmpty()) {
            val client = AceClient(pcUrl, quickHttp)
            client.health().also { up -> if (up) pcProps = runCatching { client.props() }.getOrNull() }
        } else {
            false
        }
        val next = EngineStatus(
            checked = true,
            phoneVerdict = device.phoneVerdict(s.allowLowRam),
            phoneBundled = embedded.isBundled(),
            phoneModels = models.phoneModelsReady(),
            termuxUp = termuxUp,
            pcUrl = pcUrl,
            pcUp = pcUp,
            pcProps = pcProps,
        )
        _status.value = next
        return next
    }

    /** Picks the engine for a new song, starting the on-phone engine if needed. */
    suspend fun resolve(onStage: (String) -> Unit): ResolvedEngine {
        val st = refresh()
        val s = settings.current

        suspend fun phone(): ResolvedEngine? = when {
            st.phoneReady -> ResolvedEngine(EngineKind.PHONE, "Phone AI", AceClient(embedded.ensureRunning(onStage), http))
            st.termuxUp -> ResolvedEngine(EngineKind.TERMUX, "Termux", AceClient("http://127.0.0.1:${s.termuxPort}", http))
            else -> null
        }

        fun pc(): ResolvedEngine? =
            if (st.pcUp) ResolvedEngine(EngineKind.PC, "PC AI", AceClient(st.pcUrl, http)) else null

        return when (s.engineMode) {
            EngineMode.AUTO -> phone() ?: pc() ?: throw EngineException(
                "No engine is ready. ${st.phoneProblem()} Or connect your PC in Settings."
            )
            EngineMode.PHONE -> phone() ?: throw EngineException(st.phoneProblem())
            EngineMode.PC -> pc() ?: throw EngineException(
                if (st.pcUrl.isEmpty()) "Add your PC's address in Settings, PC AI."
                else "Can't reach your PC at ${st.pcUrl}. Is the engine running and are you on the same Wi-Fi?"
            )
        }
    }

    companion object {
        /** "192.168.1.5" -> "http://192.168.1.5:8085" */
        fun normalizeUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (url.isEmpty()) return ""
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
            val afterScheme = url.substringAfter("://")
            val hostPart = afterScheme.substringBefore('/')
            val hasPort = Regex(""":\d+$""").containsMatchIn(hostPart)
            if (!hasPort && url.startsWith("http://")) {
                val rest = afterScheme.removePrefix(hostPart)
                url = "http://$hostPart:8085$rest"
            }
            return url
        }
    }
}
