package io.github.thinke.biwurth

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import io.github.thinke.biwurth.config.ConfigStore
import io.github.thinke.biwurth.ha.HomeAssistant
import io.github.thinke.biwurth.update.Updater

class PanelApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var config: ConfigStore
        private set
    lateinit var ha: HomeAssistant
        private set
    lateinit var screen: ScreenController
        private set
    lateinit var mqtt: MqttDevice
        private set
    lateinit var updater: Updater
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        config = ConfigStore(this)
        ha = HomeAssistant(config).also { it.start() }
        screen = ScreenController(this, prefs)
        updater = Updater(this, prefs)
        mqtt = MqttDevice(this).also { it.start() }
        WebServer(this).start()
        keepWirelessDebuggingOn()
    }

    // Wireless debugging switches itself off on every reboot, which would leave a wall panel
    // unreachable. With WRITE_SECURE_SETTINGS granted over adb (README) we switch it back on.
    private fun keepWirelessDebuggingOn() {
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 1) }
    }
}

val Context.panel: PanelApp get() = applicationContext as PanelApp
