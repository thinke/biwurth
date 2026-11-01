package io.github.thinke.biwurth

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import io.github.thinke.biwurth.config.BackgroundStore
import io.github.thinke.biwurth.config.ConfigStore
import io.github.thinke.biwurth.config.PictureStore
import io.github.thinke.biwurth.config.ThemeStore
import io.github.thinke.biwurth.sources.Sources
import io.github.thinke.biwurth.update.Updater

class PanelApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var config: ConfigStore
        private set
    lateinit var backgrounds: BackgroundStore
        private set
    lateinit var pictures: PictureStore
        private set
    lateinit var themes: ThemeStore
        private set
    lateinit var sources: Sources
        private set
    lateinit var hub: DataHub
        private set
    lateinit var screen: ScreenController
        private set
    lateinit var mqtt: MqttDevice
        private set
    lateinit var updater: Updater
        private set

    override fun onCreate() {
        super.onCreate()
        EventLog.init(filesDir)
        prefs = Prefs(this)
        FrameStats.showCounter.value = prefs.showFps
        config = ConfigStore(this)
        backgrounds = BackgroundStore(this)
        pictures = PictureStore(this)
        themes = ThemeStore(this, pictures, backgrounds)
        sources = Sources(config, filesDir).also { it.start() }
        hub = DataHub(sources)
        screen = ScreenController(this, prefs)
        updater = Updater(this, prefs)
        mqtt = MqttDevice(this).also { it.start() }
        WebServer(this).start()
        // Device owner can also be set or cleared while we run (adb); follow it at once.
        Kiosk.onRightsChanged(this)
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = Kiosk.onRightsChanged(context)
            },
            IntentFilter(DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}

val Context.panel: PanelApp get() = applicationContext as PanelApp
