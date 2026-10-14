package com.melodia.app

import android.app.Application
import android.content.Context
import com.melodia.app.data.SettingsRepository
import com.melodia.app.data.SongStore
import com.melodia.app.data.VoiceStore
import com.melodia.app.engine.DeviceInfo
import com.melodia.app.engine.EmbeddedServer
import com.melodia.app.engine.EngineRouter
import com.melodia.app.engine.ModelManager
import com.melodia.app.engine.Notifications
import com.melodia.app.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MelodiaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MelodiaApp).container

/** Simple manual dependency container shared by the whole app. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    val settings = SettingsRepository(app, json)
    val songs = SongStore(app, json)
    val voices = VoiceStore(app, json)
    val device = DeviceInfo(app)
    val models = ModelManager(app)
    val embedded = EmbeddedServer(app, models, http)
    val router = EngineRouter(settings, models, embedded, device, http)
    val player = PlayerConnection(app)
}
