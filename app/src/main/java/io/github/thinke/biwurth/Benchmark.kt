package io.github.thinke.biwurth

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Measures how the panel draws, on the tablet itself: for some seconds it lets the panel be
 * ([Mode.IDLE]), redraws the whole screen on every frame ([Mode.FULL]: the most drawing can cost)
 * or swipes between pages ([Mode.SWIPE]), optionally keeping the panel "warm" (a tiny drawing
 * every frame, so the CPU and GPU never slow down). Returns frame rate, frame-time percentiles,
 * frames over the display's budget and the CPU it took.
 */
object Benchmark {
    enum class Mode { IDLE, FULL, SWIPE }

    /** Read by MainActivity: redraw everything every frame / a speck every frame. */
    val redrawAll = mutableStateOf(false)
    val keepWarm = mutableStateOf(false)

    private val mutex = Mutex()

    suspend fun run(app: PanelApp, seconds: Int, mode: Mode, warm: Boolean): JSONObject = mutex.withLock {
        check(FrameStats.attached) { "The panel isn't on screen (Settings or another app is in front)" }
        val screen = app.screen
        withContext(Dispatchers.Main) { screen.wake() }
        delay(1_000)
        val pages = app.config.layout.value.pages.size
        val startPage = screen.page.value
        try {
            withContext(Dispatchers.Main) {
                keepWarm.value = warm
                redrawAll.value = mode == Mode.FULL
            }
            // Settle into the mode before measuring.
            delay(500)
            val cpuBefore = FrameStats.cpuSeconds()
            val start = System.nanoTime()
            coroutineScope {
                val swiper = if (mode == Mode.SWIPE && pages > 1) {
                    launch(Dispatchers.Main) {
                        var page = startPage
                        while (isActive) {
                            page = if (page + 1 < pages) page + 1 else 0
                            screen.page.value = page
                            // Keeps the screen awake, as a touch would.
                            screen.userActivity()
                            delay(1_200)
                        }
                    }
                } else {
                    null
                }
                delay(seconds * 1_000L)
                swiper?.cancel()
            }
            val end = System.nanoTime()
            val cpu = FrameStats.cpuSeconds() - cpuBefore
            val wall = (end - start) / 1e9
            FrameStats.summary(start, end).toJson()
                .put("mode", mode.name.lowercase())
                .put("warm", warm)
                .put("seconds", seconds)
                .put("refreshHz", FrameStats.refreshRate.toDouble())
                .put("cpuPercentOfCore", Math.round(cpu / wall * 1000) / 10.0)
                .put("cores", Runtime.getRuntime().availableProcessors())
                .put("pages", pages)
                .put(
                    "layout",
                    app.config.layout.value.let { layout ->
                        JSONObject().put("theme", layout.theme.preset).put("animations", layout.theme.animations)
                            .put("background", layout.background.type + if (layout.background.animate) " (animated)" else "")
                            .put("tileOpacity", layout.theme.tileOpacity)
                    },
                )
        } finally {
            withContext(Dispatchers.Main) {
                redrawAll.value = false
                keepWarm.value = false
                screen.page.value = startPage
            }
        }
    }
}
