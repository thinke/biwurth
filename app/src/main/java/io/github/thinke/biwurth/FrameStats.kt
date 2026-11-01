package io.github.thinke.biwurth

import android.os.Handler
import android.os.HandlerThread
import android.system.Os
import android.system.OsConstants
import android.view.FrameMetrics
import android.view.Window
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

/**
 * Every frame the panel's window draws, as Android measures it (FrameMetrics: from the frame's
 * start to the GPU done), kept for the last minute or so. The FPS counter and the benchmark read it.
 */
object FrameStats {
    /** Settings → FPS counter: shown over the panel. */
    val showCounter = MutableStateFlow(false)

    private const val CAPACITY = 8_192
    private val ends = LongArray(CAPACITY)
    private val durations = LongArray(CAPACITY)
    private var count = 0L
    private val thread by lazy { HandlerThread("frame-stats").apply { start() } }

    @Volatile var refreshRate = 60f
        private set

    @Volatile var attached = false
        private set

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        synchronized(this) {
            val i = (count % CAPACITY).toInt()
            ends[i] = System.nanoTime()
            durations[i] = duration
            count++
        }
    }

    fun attach(window: Window) {
        refreshRate = window.windowManager.defaultDisplay.refreshRate.takeIf { it > 1f } ?: 60f
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        attached = true
    }

    fun detach(window: Window) {
        runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
        attached = false
    }

    /** Frame durations (ns) of the frames that finished after [sinceNanos]. */
    fun since(sinceNanos: Long): LongArray = synchronized(this) {
        val available = minOf(count, CAPACITY.toLong()).toInt()
        val out = ArrayList<Long>(available)
        for (k in available downTo 1) {
            val i = ((count - k) % CAPACITY).toInt()
            if (ends[i] >= sinceNanos) out += durations[i]
        }
        out.toLongArray()
    }

    /** Frames per second, frame-time percentiles (ms) and the share over the display's budget. */
    class Summary(val frames: Int, val seconds: Double, val durations: LongArray, val budgetNanos: Long) {
        private val sorted = durations.sortedArray()
        val fps get() = frames / seconds
        fun percentileMs(p: Double) = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()] / 1e6
        val overBudget get() = if (sorted.isEmpty()) 0.0 else sorted.count { it > budgetNanos } * 100.0 / sorted.size

        fun toJson(): JSONObject = JSONObject()
            .put("frames", frames)
            .put("fps", round(fps))
            .put("frameMs", JSONObject().put("p50", round(percentileMs(0.5))).put("p90", round(percentileMs(0.9))).put("p99", round(percentileMs(0.99))).put("max", round(percentileMs(1.0))))
            .put("overBudgetPercent", round(overBudget))
            .put("budgetMs", round(budgetNanos / 1e6))
    }

    fun summary(sinceNanos: Long, untilNanos: Long = System.nanoTime()): Summary {
        val durations = since(sinceNanos)
        return Summary(durations.size, (untilNanos - sinceNanos) / 1e9, durations, (1e9 / refreshRate).toLong())
    }

    /** This process's CPU time so far, in seconds (user + system). */
    fun cpuSeconds(): Double {
        val fields = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ')
        // After the name: state is field 3, so utime (14) and stime (15) are at 11 and 12.
        val ticks = fields[11].toLong() + fields[12].toLong()
        return ticks.toDouble() / Os.sysconf(OsConstants._SC_CLK_TCK)
    }

    private fun round(value: Double) = Math.round(value * 10) / 10.0
}
