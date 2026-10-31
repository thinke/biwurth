package io.github.thinke.biwurth

import io.github.thinke.biwurth.config.Layout
import io.github.thinke.biwurth.config.Tile
import io.github.thinke.biwurth.config.TileTypes
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
    /**
     * A problem found in the layout, as a sentence ([message]) and as data: a stable [code] plus the
     * page, tile and entity it concerns, so a program can tell exactly what to fix.
     */
    class Issue(val code: String, val message: String, val page: String? = null, val tile: String? = null, val entity: String? = null) {
        fun toJson(): JSONObject = JSONObject().put("code", code).put("message", message)
            .apply {
                page?.let { put("page", it) }
                tile?.let { put("tile", it) }
                entity?.let { put("entity", it) }
            }
    }

    class Report(val issues: List<Issue>, val preloaded: Int, val millis: Long) {
        val warnings: List<String> get() = issues.map { it.message }

        fun toJson(): JSONObject = JSONObject()
            .put("warnings", JSONArray(warnings))
            .put("issues", JSONArray(issues.map { it.toJson() }))
            .put("preloaded", preloaded)
            .put("millis", millis)
    }

    /** Checks [layout] and, with [preload], loads what its tiles show into their caches. */
    suspend fun compile(layout: Layout, preload: Boolean = true): Report {
        val started = System.currentTimeMillis()
        val warnings = mutableListOf<Issue>()
        val entities = app.hub.entities.value
        // Own entities may not have a value yet; check them against the source settings instead.
        val ownIds = app.config.sources.value.flatMap { source -> source.fields.map { "src.${source.id}.${source.fieldId(it)}" } }.toSet()
        val hub = app.hub

        val background = layout.background
        if (background.type == "image" && background.image?.let(app.backgrounds::file) == null) {
            warnings += Issue("background_missing", "The background picture is missing; the theme colour shows instead.")
        }

        for (page in layout.pages) {
            fun issue(code: String, message: String, tile: Tile, entity: String? = null) =
                Issue(code, "${page.name}: $message", page.id, tile.id, entity)
            page.tiles.forEachIndexed { i, a ->
                page.tiles.drop(i + 1).filter { b -> overlap(a, b) }.forEach { b ->
                    warnings += issue("overlap", "${label(a)} and ${label(b)} overlap.", b)
                }
            }
            for (tile in page.tiles) {
                val ids = entityIds(tile)
                val min = TileTypes.BY_KEY[tile.type]?.minSize
                if (min != null && (tile.w < min.first || tile.h < min.second)) {
                    warnings += issue("too_small", "${label(tile)} is ${tile.w}×${tile.h}; a ${tile.type} tile needs at least ${min.first}×${min.second} to read well.", tile)
                }
                if (tile.type in ENTITY_TYPES && ids.isEmpty()) warnings += issue("no_entity", "${label(tile)} has no entity.", tile)
                for (id in ids.filter(hub::isOwn)) {
                    if (id !in ownIds && !id.endsWith("_ms")) warnings += issue("unknown_field", "${label(tile)}: $id isn't a field of any source.", tile, id)
                }
                val domains = TileTypes.BY_KEY[tile.type]?.domains.orEmpty()
                // Not a toggle's measurement: that's a sensor.
                for (id in ids.filter { domains.isNotEmpty() && it != tile.prop("measurement") && TileTypes.domainOf(it) !in domains }) {
                    warnings += issue("wrong_domain", "${label(tile)}: a ${tile.type} tile takes ${domains.joinToString("/")} entities, not $id.", tile, id)
                }
                for (id in ids.filterNot(hub::isOwn)) {
                    // A Home Assistant that's not connected can't say what it has: check it when it is.
                    if (!hub.canFetch(id)) {
                        warnings += issue("not_checked", "${label(tile)}: $id wasn't checked, its Home Assistant isn't connected.", tile, id)
                        continue
                    }
                    val entity = entities[id]
                    when {
                        entity == null -> warnings += issue("entity_missing", "${label(tile)}: $id doesn't exist in its Home Assistant.", tile, id)
                        tile.type == "graph" && entity.state.toFloatOrNull() == null && entity.state != "unavailable" ->
                            warnings += issue("not_numeric", "${label(tile)}: $id isn't numeric (\"${entity.state}\"), so it can't be graphed.", tile, id)
                    }
                }
                if (tile.type == "camera" && tile.prop("camera")?.let(app.config.cameras.value::streamUrl) == null) {
                    warnings += issue("no_stream", "${label(tile)} needs a go2rtc address (Cameras tab) or a full rtsp:// URL.", tile)
                }
            }
        }
        if (!preload) return Report(warnings, 0, System.currentTimeMillis() - started)

        val tiles = layout.pages.flatMap { it.tiles }
        val loads = coroutineScope {
            tiles.mapNotNull { tile -> preload(tile)?.let { load -> async { runCatching { load() }.isSuccess } } }
                .let { jobs ->
                    // Past the limit, give up on the stragglers: their tiles load when shown.
                    withTimeoutOrNull(PRELOAD_TIMEOUT_MS) { jobs.awaitAll() }
                        ?: jobs.map { if (it.isCompleted) it.await() else false.also { _ -> it.cancel() } }
                }
        }
        val failed = loads.count { !it }
        if (failed > 0) warnings += Issue("preload_failed", "$failed tile${if (failed == 1) "" else "s"} couldn't preload; they load when shown.")
        return Report(warnings, loads.count { it }, System.currentTimeMillis() - started)
    }

    private fun preload(tile: Tile): (suspend () -> Unit)? {
        val hub = app.hub
        val ids = entityIds(tile)
        val connected = ids.isNotEmpty() && hub.historyAvailable(ids)
        return when (tile.type) {
            "graph" -> if (ids.isNotEmpty() && app.hub.historyAvailable(ids)) {
                suspend { app.hub.history(ids, tile.intProp("hours", 24).coerceIn(1, 24 * 14)) }
            } else {
                null
            }
            "weather" -> if (connected) suspend { hub.forecast(ids.first()) } else null
            "calendar" -> if (connected) suspend { hub.calendar(ids.joinToString(","), tile.intProp("days", 7).coerceIn(1, 60)) } else null
            "camera" -> {
                val camera = tile.prop("camera") ?: return null
                val url = tile.prop("snapshot") ?: app.config.cameras.value.snapshotUrl(camera) ?: return null
                suspend { check(preloadSnapshot(url)) { "no snapshot" } }
            }
            else -> null
        }
    }

    // The entities a tile shows: its entity (or several, comma separated) and a button's measurement.
    private fun entityIds(tile: Tile) =
        (tile.prop("entity")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()) +
            listOfNotNull(tile.prop("measurement")).filter { tile.type == "toggle" }

    private fun overlap(a: Tile, b: Tile) = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

    private fun label(tile: Tile) = "“${tile.prop("name") ?: tile.prop("entity")?.substringBefore(',') ?: tile.type}”"

    private companion object {
        val ENTITY_TYPES = setOf("entity", "toggle", "graph", "weather", "calendar")
        const val PRELOAD_TIMEOUT_MS = 15_000L
    }
}
