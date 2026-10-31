package io.github.thinke.biwurth

import android.Manifest
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.thinke.biwurth.config.BackgroundStore
import io.github.thinke.biwurth.config.ConfigStore
import io.github.thinke.biwurth.sources.Sources
import io.github.thinke.biwurth.update.Updater

class PanelApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var config: ConfigStore
        private set
    lateinit var backgrounds: BackgroundStore
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
        config = ConfigStore(this)
        backgrounds = BackgroundStore(this)
        sources = Sources(config, filesDir).also { it.start() }
        hub = DataHub(sources)
        screen = ScreenController(this, prefs)
        updater = Updater(this, prefs)
        mqtt = MqttDevice(this).also { it.start() }
        WebServer(this).start()
        keepWirelessDebuggingOn()
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

    // Wireless debugging switches itself off on every reboot, which would leave a wall panel
    // unreachable. With WRITE_SECURE_SETTINGS granted over adb (README) we switch it back on.
    private fun keepWirelessDebuggingOn() {
        // Wireless debugging is Android 11+. Before that adb over Wi-Fi is `adb tcpip`, which a
        // reboot also clears and an app can't switch back on (README).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 1) }
    }
}

val Context.panel: PanelApp get() = applicationContext as PanelApp
