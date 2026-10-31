package io.github.thinke.biwurth.sources

import io.github.thinke.biwurth.ha.thin
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * History of biwurth's own numeric entities, for graphs without Home Assistant. Each entity is
 * a small CSV file ("epoch ms,value" per line) in app storage, so graphs survive restarts and
 * updates; only changes are written, and two weeks are kept.
 */
class SourceHistory(private val dir: File) {
    private val series = ConcurrentHashMap<String, ArrayList<Pair<Long, Float>>>()

    init {
        dir.mkdirs()
    }

    fun record(id: String, time: Long, value: Float) {
        val points = points(id)
        synchronized(points) {
            val last = points.lastOrNull()
            if (last != null && (last.second == value || time <= last.first)) return
            points.add(time to value)
            file(id).appendText("$time,$value\n")
            // Rewrite the file now and then to drop what has aged out.
            val cutoff = time - RETENTION_MS
            if (points.size > MAX_POINTS || (points.first().first < cutoff - 24 * 3_600_000L)) {
                points.removeAll { it.first < cutoff }
                while (points.size > MAX_POINTS) points.removeAt(0)
                file(id).writeText(points.joinToString("") { "${it.first},${it.second}\n" })
            }
        }
    }

    /** The last [hours], thinned for drawing. */
    fun slice(id: String, hours: Int): List<Pair<Long, Float>> {
        val now = System.currentTimeMillis()
        val start = now - hours * 3_600_000L
        val points = points(id)
        val inside = synchronized(points) {
            val first = points.indexOfFirst { it.first >= start }.let { if (it < 0) points.size else it }
            points.subList((first - 1).coerceAtLeast(0), points.size).toList()
        }
        return thin(inside, start, now)
    }

    private fun points(id: String) = series.getOrPut(id) { load(id) }

    private fun load(id: String): ArrayList<Pair<Long, Float>> {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val file = file(id)
        if (!file.exists()) return ArrayList()
        return file.readLines().mapNotNullTo(ArrayList()) { line ->
            val time = line.substringBefore(',').toLongOrNull() ?: return@mapNotNullTo null
            val value = line.substringAfter(',').toFloatOrNull() ?: return@mapNotNullTo null
            (time to value).takeIf { time >= cutoff }
        }
    }

    // Entity ids are src.<slug>.<slug>: safe as file names.
    private fun file(id: String) = File(dir, "$id.csv")

    private companion object {
        const val RETENTION_MS = 14 * 24 * 3_600_000L
        const val MAX_POINTS = 50_000
    }
}
