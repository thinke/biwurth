package io.github.thinke.biwurth.ui

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * The screen the panel draws on, in pixels as it is set now: the display's real size, which
 * follows a resolution set in Android or with `wm size`, where the display mode's physical size
 * would give the hardware's. Width is the long side unless the screen is set up taller.
 */
fun screenPixels(context: Context): Point {
    val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    return Point().also {
        @Suppress("DEPRECATION") // getRealSize is the size apps draw at, on every Android from 8.
        display.getRealSize(it)
    }
}

/**
 * The panel is laid out on a canvas of [PANEL_LONG] × [PANEL_SHORT] units (turned for portrait)
 * scaled to fit the screen, whatever its pixels and density: a layout looks the same on a 7"
 * tablet, a 10" one and a TV, only bigger or smaller, and text keeps its size relative to the
 * tiles. Android's own density would make units of different real size on each device. A
 * 1280×800 screen at 213 dpi is exactly this canvas, so such tablets look as before.
 */
const val PANEL_LONG = 960f
const val PANEL_SHORT = 600f

/** Pixels per panel unit on a [width] × [height] pixel screen. */
fun panelDensity(width: Int, height: Int): Float =
    if (width >= height) minOf(width / PANEL_LONG, height / PANEL_SHORT) else minOf(width / PANEL_SHORT, height / PANEL_LONG)

/** Runs [content] on the panel canvas: its density set so the screen is PANEL_LONG × PANEL_SHORT units. */
@Composable
fun PanelCanvas(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = panelDensity(constraints.maxWidth, constraints.maxHeight).coerceAtLeast(0.1f)
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1f)) { content() }
    }
}
