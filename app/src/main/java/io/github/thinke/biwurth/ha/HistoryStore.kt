package io.github.thinke.biwurth.ha

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory time series for the graph tiles. Each entity's history is fetched from Home Assistant
 * once, then kept current from the live state changes, so graphs never download the same data
 * twice. Each series keeps the time span it was loaded for. Clear it on every reconnect: changes
 * may have been missed while offline.
 */
class HistoryStore {
    private class Series(val windowMs: Long, val points: ArrayList<Pair<Long, Float>>)

    private val series = ConcurrentHashMap<String, Series>()

    /** The entities whose history doesn't reach back to [start] yet. */
    fun missing(ids: List<String>, start: Long, now: Long): List<String> =
        ids.filter { id -> series[id].let { it == null || now - it.windowMs > start } }

    fun load(id: String, windowMs: Long, points: List<Pair<Long, Float>>) {
        series[id] = Series(windowMs, ArrayList(points))
    }

    /** A live numeric state change; ignored unless a graph has loaded this entity. */
    fun append(id: String, time: Long, value: Float) {
        val s = series[id] ?: return
        synchronized(s) {
            val last = s.points.lastOrNull()
            if (last != null && (time <= last.first || last.second == value)) return
            s.points.add(time to value)
            // Drop what has slid out of the window, keeping the last point before it: a step
            // line needs the value the window starts with.
            val cutoff = time - s.windowMs
            val firstInside = s.points.indexOfFirst { it.first >= cutoff }
            if (firstInside > 1) s.points.subList(0, firstInside - 1).clear()
        }
    }

    /** Points from [start] on, with the one just before it; null when not loaded that far back. */
    fun slice(id: String, start: Long, now: Long): List<Pair<Long, Float>>? {
        val s = series[id] ?: return null
        if (now - s.windowMs > start) return null
        return synchronized(s) {
            val firstInside = s.points.indexOfFirst { it.first >= start }.let { if (it < 0) s.points.size else it }
            s.points.subList((firstInside - 1).coerceAtLeast(0), s.points.size).toList()
        }
    }

    fun clear() = series.clear()
}
