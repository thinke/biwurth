package io.github.thinke.biwurth

import android.content.pm.ActivityInfo
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
import io.github.thinke.biwurth.ui.PanelScreen
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
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
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

                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    PanelScreen(layout, screen.page, onOpenSettings = { showSettings = true })
                    if (showSettings) SettingsScreen(activity = this@MainActivity, onClose = { showSettings = false })
                    // Some panels keep a faint glow at "backlight off"; black pixels hide it.
                    if (state != ScreenState.AWAKE) Box(Modifier.fillMaxSize().background(Color.Black))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Kiosk.closeSystemSettings(this)
        if (panel.prefs.kiosk) Kiosk.enter(this)
        screen.userActivity()
        panel.updater.checkOnStart()
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
