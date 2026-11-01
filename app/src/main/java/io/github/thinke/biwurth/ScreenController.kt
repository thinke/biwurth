package io.github.thinke.biwurth

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class ScreenState { AWAKE, DIMMED, OFF }

/**
 * Owns when the panel is awake. A touch restarts the idle countdown; when it runs out the panel
 * sleeps the way [Prefs.sleepMode] says. Main thread only (WebServer and MqttDevice post to it).
 */
class ScreenController(private val context: Context, private val prefs: Prefs) {
    private val _state = MutableStateFlow(ScreenState.AWAKE)
    val state: StateFlow<ScreenState> = _state

    /** The page on show; swipes, Home Assistant and sleep (back to the first page) all set it. */
    val page = MutableStateFlow(0)

    /** Screen brightness 0–100 while awake, or -1 to follow the system setting. */
    val brightness = MutableStateFlow(prefs.brightness)

    fun setBrightness(percent: Int) {
        prefs.brightness = percent
        brightness.value = percent
    }

    /** When the panel was last touched (epoch ms), for presence in Home Assistant. */
    val lastTouch = MutableStateFlow(System.currentTimeMillis())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val power = context.getSystemService(PowerManager::class.java)
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private var idleJob: Job? = null

    /** A touch, or the screen coming back on. Returns true when this woke the panel. */
    fun userActivity(): Boolean {
        lastTouch.value = System.currentTimeMillis()
        val woke = _state.value != ScreenState.AWAKE
        if (woke) wake() else restartIdleTimer()
        return woke
    }

    /**
     * Someone moved in front of the panel (Presence): wakes it and, with [keepAwake], keeps an
     * awake panel from sleeping, like a touch would, without counting as one.
     */
    fun motion(keepAwake: Boolean) {
        when {
            _state.value != ScreenState.AWAKE -> wake()
            keepAwake -> restartIdleTimer()
        }
    }

    fun wake() {
        if (!power.isInteractive) turnScreenOn()
        _state.value = ScreenState.AWAKE
        restartIdleTimer()
    }

    fun sleep() {
        idleJob?.cancel()
        page.value = 0
        if (prefs.sleepMode == SleepMode.SCREEN_OFF && Kiosk.isAdmin(context)) {
            _state.value = ScreenState.OFF
            dpm.lockNow()
        } else {
            _state.value = ScreenState.DIMMED
        }
    }

    fun restartIdleTimer() {
        idleJob?.cancel()
        val seconds = prefs.idleSeconds
        if (seconds <= 0) return
        idleJob = scope.launch {
            delay(seconds * 1000L)
            sleep()
        }
    }

    // FULL_WAKE_LOCK with ACQUIRE_CAUSES_WAKEUP is deprecated, but it is still the only way an
    // ordinary app can switch the screen on without a touch (a request from Home Assistant).
    @Suppress("DEPRECATION")
    private fun turnScreenOn() {
        power.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "panel:wake")
            .acquire(3_000)
    }
}
