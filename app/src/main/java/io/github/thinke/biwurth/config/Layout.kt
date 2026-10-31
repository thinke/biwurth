package io.github.thinke.biwurth.config

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A tile on the panel grid. [props] holds the type's settings (entity, name, hours, …) as strings. */
data class Tile(
    val id: String,
    val type: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val props: Map<String, String>,
) {
    fun prop(key: String): String? = props[key]?.takeIf { it.isNotBlank() }
    fun intProp(key: String, default: Int): Int = prop(key)?.toIntOrNull() ?: default
}

/** One screenful of tiles; the panel swipes between pages. */
data class Page(val id: String, val name: String, val tiles: List<Tile>)

/**
 * What the panel shows: pages of tiles on a grid of [columns] × [rows] cells, stretched over the
 * screen whatever its size, so one layout fits any tablet in the chosen [orientation].
 */
data class Layout(
    val columns: Int = 12,
    val rows: Int = 8,
    val orientation: Orientation = Orientation.LANDSCAPE,
    val pages: List<Page> = emptyList(),
    val theme: ThemeSettings = ThemeSettings(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("columns", columns)
        .put("rows", rows)
        .put("orientation", orientation.key)
        .put("theme", JSONObject().put("preset", theme.preset).put("accent", theme.accent ?: "").put("font", theme.font).put("textScale", theme.textScale.toDouble()))
        .put(
            "pages",
            JSONArray(
                pages.map { page ->
                    JSONObject().put("id", page.id).put("name", page.name).put("tiles", JSONArray(page.tiles.map { it.toJson() }))
                },
            ),
        )

    companion object {
        val TYPES = setOf("clock", "entity", "toggle", "graph", "weather", "calendar", "camera")
        val DEFAULT = Layout(pages = listOf(Page("home", "Home", listOf(Tile("clock", "clock", 0, 0, 12, 8, emptyMap())))))

        /**
         * Parses and cleans up a layout: unknown tile types are dropped, tiles are kept on the grid
         * and there is always at least one page.
         */
        fun fromJson(json: JSONObject): Layout {
            val columns = json.optInt("columns", 12).coerceIn(1, 48)
            val rows = json.optInt("rows", 8).coerceIn(1, 48)
            val pages = json.optJSONArray("pages") ?: JSONArray()
            return Layout(
                columns = columns,
                rows = rows,
                orientation = Orientation.fromKey(json.optString("orientation")),
                theme = json.optJSONObject("theme").let { theme ->
                    ThemeSettings(
                        preset = theme?.optString("preset")?.ifBlank { null } ?: "dark",
                        accent = theme?.optString("accent")?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) },
                        font = if (theme?.optString("font") == "system") "system" else "inter",
                        textScale = (theme?.optDouble("textScale", 1.0) ?: 1.0).toFloat().coerceIn(0.5f, 2f),
                    )
                },
                pages = (0 until pages.length()).mapNotNull { pages.optJSONObject(it) }.mapIndexed { i, page ->
                    val tiles = page.optJSONArray("tiles") ?: JSONArray()
                    Page(
                        id = page.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = page.optString("name").ifBlank { "Page ${i + 1}" },
                        tiles = (0 until tiles.length()).mapNotNull { t ->
                            tiles.optJSONObject(t)?.let { tileFromJson(it, columns, rows) }
                        },
                    )
                }.ifEmpty { DEFAULT.pages },
            )
        }

        private fun tileFromJson(json: JSONObject, columns: Int, rows: Int): Tile? {
            val type = json.optString("type")
            if (type !in TYPES) return null
            val w = json.optInt("w", 1).coerceIn(1, columns)
            val h = json.optInt("h", 1).coerceIn(1, rows)
            val propsJson = json.optJSONObject("props") ?: JSONObject()
            return Tile(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                type = type,
                x = json.optInt("x").coerceIn(0, columns - w),
                y = json.optInt("y").coerceIn(0, rows - h),
                w = w,
                h = h,
                props = propsJson.keys().asSequence().associateWith { propsJson.optString(it) },
            )
        }

        private fun Tile.toJson(): JSONObject = JSONObject()
            .put("id", id).put("type", type)
            .put("x", x).put("y", y).put("w", w).put("h", h)
            .put("props", JSONObject(props))
    }
}

/**
 * The panel's look: a colour preset (PanelTheme.PRESETS), an optional accent colour "#rrggbb",
 * the font ("inter" or "system") and a text scale.
 */
data class ThemeSettings(
    val preset: String = "dark",
    val accent: String? = null,
    val font: String = "inter",
    /** Scales all text, automatic sizes included: for a panel read from further away. */
    val textScale: Float = 1f,
)

enum class Orientation(val key: String) {
    LANDSCAPE("landscape"),
    PORTRAIT("portrait"),
    REVERSE_LANDSCAPE("reverse_landscape"),
    REVERSE_PORTRAIT("reverse_portrait"),
    AUTO("auto"),
    ;

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: LANDSCAPE
    }
}
