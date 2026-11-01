package io.github.thinke.biwurth

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.thinke.biwurth.config.Orientation
import io.github.thinke.biwurth.ui.FpsCounter
import io.github.thinke.biwurth.ui.PanelScreen
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp
import io.github.thinke.biwurth.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val screen get() = panel.screen

    // The touch that wakes the panel is eaten whole (down to up), so it can't press anything.
    private var swallowingTouch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Kiosk.applyPolicies(this)
        PanelService.start(this)

        // Our idle timer decides when to sleep, not the system screen timeout.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Shown over the lock screen when Home Assistant wakes us without device owner.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            // Not FLAG_TURN_SCREEN_ON: on 8.0 it wakes the screen at every relayout, which would undo
            // "Screen off" sleep. ScreenController wakes the screen with a wake lock instead.
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        // A launcher has nowhere to go back to; Settings handles Back itself.
        onBackPressedDispatcher.addCallback(this) {}

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by screen.state.collectAsState()
                val layout by panel.config.layout.collectAsState()
                val brightness by screen.brightness.collectAsState()
                var showSettings by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(state, brightness) {
                    window.attributes = window.attributes.apply {
                        screenBrightness = when {
                            state != ScreenState.AWAKE -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
                            brightness < 0 -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                            // 0 % is the dimmest visible level; "off" is what sleep is for.
                            else -> (brightness / 100f).coerceAtLeast(0.01f)
                        }
                    }
                    if (state != ScreenState.AWAKE) showSettings = false
                }

                LaunchedEffect(layout.orientation) { requestedOrientation = layout.orientation.toActivityInfo() }

                // Made device owner while running: lock into kiosk mode now, not at the next start.
                val owner by Kiosk.deviceOwner.collectAsState()
                LaunchedEffect(owner) { if (owner && panel.prefs.kiosk) Kiosk.enter(this@MainActivity) }

                // The benchmark: a tick every frame redraws everything (full) or a speck (warm).
                val redrawAll by Benchmark.redrawAll
                val warm by Benchmark.keepWarm
                val tick = remember { mutableLongStateOf(0L) }
                LaunchedEffect(redrawAll, warm) {
                    if (redrawAll || warm) while (true) withFrameNanos { tick.longValue = it }
                }
                val showFps by FrameStats.showCounter.collectAsState()

                Box(
                    Modifier.fillMaxSize().background(Color.Black)
                        .then(if (redrawAll) Modifier.drawWithContent { tick.longValue; drawContent() } else Modifier),
                ) {
                    if (warm) Canvas(Modifier.size(1.dp)) { drawRect(Color.Black.copy(alpha = (tick.longValue % 2) / 255f)) }
                    PanelScreen(layout, screen.page, onOpenSettings = { showSettings = true })
                    if (showSettings) SettingsScreen(activity = this@MainActivity, onClose = { showSettings = false })
                    // Some panels keep a faint glow at "backlight off"; black pixels hide it.
                    if (state != ScreenState.AWAKE) Box(Modifier.fillMaxSize().background(Color.Black))
                    if (showFps && state == ScreenState.AWAKE) FpsCounter(Modifier.align(Alignment.TopEnd))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FrameStats.attach(window)
        Kiosk.closeSystemSettings(this)
        if (panel.prefs.kiosk) Kiosk.enter(this)
        screen.userActivity()
        panel.updater.checkOnStart()
    }

    override fun onPause() {
        FrameStats.detach(window)
        super.onPause()
    }

    // Before Android 11 the hidden bars are only view flags, which Android clears when a dialog, the
    // installer or the lock screen takes focus; hide them again when we get it back.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && screen.userActivity()) swallowingTouch = true
        if (swallowingTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowingTouch = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}

private fun Orientation.toActivityInfo() = when (this) {
    Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    Orientation.REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    Orientation.REVERSE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    Orientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
}
