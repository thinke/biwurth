package io.github.thinke.biwurth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the panel's process at foreground priority, so MQTT, the website and the Home Assistant
 * connection (all owned by PanelApp) keep running when another app is in front or the screen is off.
 * While the screen is off it also holds the CPU awake: otherwise MQTT keep-alives stall, the broker
 * drops us and Home Assistant can no longer wake the panel.
 */
class PanelService : Service() {
    private lateinit var wakeLock: PowerManager.WakeLock

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> if (!wakeLock.isHeld) wakeLock.acquire()
                Intent.ACTION_SCREEN_ON -> if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "panel:connections")
            .apply { setReferenceCounted(false) }
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Silent and out of the way; in kiosk mode the status bar that would show it is hidden anyway.
    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "biwurth running", NotificationManager.IMPORTANCE_MIN),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("biwurth is running")
            .setContentText("Website, Home Assistant and MQTT")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "panel"
        private const val NOTIFICATION_ID = 1

        /** Call from the activity: Android only lets a visible app start a foreground service. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, PanelService::class.java))
        }
    }
}
