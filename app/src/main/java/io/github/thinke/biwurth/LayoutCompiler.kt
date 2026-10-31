package io.github.thinke.biwurth

import io.github.thinke.biwurth.config.Layout
import io.github.thinke.biwurth.config.Tile
import io.github.thinke.biwurth.ui.preloadSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compiles a layout before it goes on screen: checks it against the grid and Home Assistant, and
 * preloads what its tiles show (history, forecasts, calendars, camera snapshots) into the caches
 * the tiles read. Saving switches to the layout only afterwards, so it appears complete at once.
 */
class LayoutCompiler(private val app: PanelApp) {
    class Report(val warnings: List<String>, val preloaded: Int, val millis: Long) {
        fun toJson(): JSONObject = JSONObject()
            .put("warnings", JSONArray(warnings))
            .put("preloaded", preloaded)
            .put("millis", millis)
    }

    suspend fun compile(layout: Layout): Report {
        val started = System.currentTimeMillis()
        val warnings = mutableListOf<String>()
        val entities = app.ha.entities.value
        val connected = app.ha.connected.value
        if (!connected) warnings += "Home Assistant isn't connected, so entities weren't checked or preloaded."

        val background = layout.background
        if (background.type == "image" && background.image?.let(app.backgrounds::file) == null) {
            warnings += "The background picture is missing; the theme colour shows instead."
        }

        for (page in layout.pages) {
            page.tiles.forEachIndexed { i, a ->
                page.tiles.drop(i + 1).filter { b -> overlap(a, b) }.forEach { b ->
                    warnings += "${page.name}: ${label(a)} and ${label(b)} overlap."
                }
            }
            for (tile in page.tiles) {
                val ids = entityIds(tile)
                if (tile.type in ENTITY_TYPES && ids.isEmpty()) warnings += "${page.name}: ${label(tile)} has no entity."
                if (!connected) continue
                for (id in ids) {
                    val entity = entities[id]
                    when {
                        entity == null -> warnings += "${page.name}: ${label(tile)}: $id doesn't exist in Home Assistant."
                        tile.type == "graph" && entity.state.toFloatOrNull() == null && entity.state != "unavailable" ->
                            warnings += "${page.name}: ${label(tile)}: $id isn't numeric (\"${entity.state}\"), so it can't be graphed."
                    }
                }
                if (tile.type == "camera" && tile.prop("camera")?.let(app.config.cameras.value::streamUrl) == null) {
                    warnings += "${page.name}: ${label(tile)} needs a go2rtc address (Cameras tab) or a full rtsp:// URL."
                }
            }
        }

        val tiles = layout.pages.flatMap { it.tiles }
        val loads = coroutineScope {
            tiles.mapNotNull { tile -> preload(tile, connected)?.let { load -> async { runCatching { load() }.isSuccess } } }
                .let { jobs ->
                    // Past the limit, give up on the stragglers: their tiles load when shown.
                    withTimeoutOrNull(PRELOAD_TIMEOUT_MS) { jobs.awaitAll() }
                        ?: jobs.map { if (it.isCompleted) it.await() else false.also { _ -> it.cancel() } }
                }
        }
        val failed = loads.count { !it }
        if (failed > 0) warnings += "$failed tile${if (failed == 1) "" else "s"} couldn't preload; they load when shown."
        return Report(warnings, loads.count { it }, System.currentTimeMillis() - started)
    }

    private fun preload(tile: Tile, connected: Boolean): (suspend () -> Unit)? {
        val ha = app.ha
        val ids = entityIds(tile)
        return when (tile.type) {
            "graph" -> if (connected && ids.isNotEmpty()) suspend { ha.history(ids, tile.intProp("hours", 24).coerceIn(1, 24 * 14)) } else null
            "weather" -> if (connected && ids.isNotEmpty()) suspend { ha.forecast(ids.first()) } else null
            "calendar" -> if (connected && ids.isNotEmpty()) suspend { ha.calendar(ids.joinToString(","), tile.intProp("days", 7).coerceIn(1, 60)) } else null
            "camera" -> {
                val camera = tile.prop("camera") ?: return null
                val url = tile.prop("snapshot") ?: app.config.cameras.value.snapshotUrl(camera) ?: return null
                suspend { check(preloadSnapshot(url)) { "no snapshot" } }
            }
            else -> null
        }
    }

    private fun entityIds(tile: Tile) =
        tile.prop("entity")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun overlap(a: Tile, b: Tile) = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

    private fun label(tile: Tile) = "“${tile.prop("name") ?: tile.prop("entity")?.substringBefore(',') ?: tile.type}”"

    private companion object {
        val ENTITY_TYPES = setOf("entity", "toggle", "graph", "weather", "calendar")
        const val PRELOAD_TIMEOUT_MS = 15_000L
    }
}
