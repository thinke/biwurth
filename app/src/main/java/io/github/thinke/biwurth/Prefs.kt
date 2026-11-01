package io.github.thinke.biwurth

import android.content.Context
import androidx.core.content.edit
import java.security.SecureRandom

/** How the panel sleeps after the idle timeout. */
enum class SleepMode {
    /** Backlight off, screen still logically on: a single touch wakes it. */
    BACKLIGHT_OFF,

    /** Screen really off (needs device admin): only the power button or Home Assistant wakes it. */
    SCREEN_OFF,
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("panel", Context.MODE_PRIVATE)

    /** Seconds without a touch before the panel sleeps; 0 = never. */
    var idleSeconds: Int
        get() = sp.getInt("idleSeconds", 120)
        set(value) = sp.edit { putInt("idleSeconds", value) }

    var sleepMode: SleepMode
        get() = sp.getString("sleepMode", null)?.let { runCatching { SleepMode.valueOf(it) }.getOrNull() }
            ?: SleepMode.BACKLIGHT_OFF
        set(value) = sp.edit { putString("sleepMode", value.name) }

    /** Screen brightness 0–100 while awake, or -1 to follow the system setting. */
    var brightness: Int
        get() = sp.getInt("brightness", -1)
        set(value) = sp.edit { putInt("brightness", value) }

    /** Settings → FPS counter over the panel. */
    var showFps: Boolean
        get() = sp.getBoolean("showFps", false)
        set(value) = sp.edit { putBoolean("showFps", value) }

    /** Lock the tablet to this app (only takes effect when the app is device owner). */
    var kiosk: Boolean
        get() = sp.getBoolean("kiosk", true)
        set(value) = sp.edit { putBoolean("kiosk", value) }

    /** Check GitHub for new releases (daily); see Updater. */
    var updateCheck: Boolean
        get() = sp.getBoolean("updateCheck", true)
        set(value) = sp.edit { putBoolean("updateCheck", value) }

    var updatePrerelease: Boolean
        get() = sp.getBoolean("updatePrerelease", false)
        set(value) = sp.edit { putBoolean("updatePrerelease", value) }

    /** Install what the daily check finds; silent on a device-owner panel. */
    var updateAutoInstall: Boolean
        get() = sp.getBoolean("updateAutoInstall", false)
        set(value) = sp.edit { putBoolean("updateAutoInstall", value) }

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0)
        set(value) = sp.edit { putLong("lastUpdateCheck", value) }

    var skippedVersion: String?
        get() = sp.getString("skippedVersion", null)
        set(value) = sp.edit { putString("skippedVersion", value) }

    /** Bearer token for the control API, made once per install. */
    val token: String
        get() = sp.getString("token", null) ?: newToken().also { sp.edit { putString("token", it) } }

    private fun newToken(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return String(CharArray(32) { alphabet[random.nextInt(alphabet.length)] })
    }
}
