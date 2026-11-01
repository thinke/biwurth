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
    val background: Background = Background(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("columns", columns)
        .put("rows", rows)
        .put("orientation", orientation.key)
        .put("background", background.toJson())
        .put("theme", theme.toJson())
        .put(
            "pages",
            JSONArray(
                pages.map { page ->
                    JSONObject().put("id", page.id).put("name", page.name).put("tiles", JSONArray(page.tiles.map { it.toJson() }))
                },
            ),
        )

    companion object {
        val TYPES: Set<String> = TileTypes.BY_KEY.keys
        val DEFAULT = Layout(pages = listOf(Page("home", "Home", listOf(Tile("clock", "clock", 0, 0, 12, 8, emptyMap())))))

        /**
         * Parses and cleans up a layout: unknown tile types are dropped, tiles are kept on the grid
         * and there is always at least one page. A tile without "w"/"h" gets its type's default size,
         * and one without "x"/"y" goes in the first free spot (left to right, top to bottom) after
         * the placed tiles, in list order: whoever writes the layout can give only order and size.
         */
        fun fromJson(json: JSONObject): Layout {
            val columns = json.optInt("columns", 12).coerceIn(1, 48)
            val rows = json.optInt("rows", 8).coerceIn(1, 48)
            val pages = json.optJSONArray("pages") ?: JSONArray()
            return Layout(
                columns = columns,
                rows = rows,
                orientation = Orientation.fromKey(json.optString("orientation")),
                background = Background.fromJson(json.optJSONObject("background") ?: JSONObject()),
                theme = json.optJSONObject("theme").let { theme ->
                    ThemeSettings(
                        preset = theme?.optString("preset")?.ifBlank { null } ?: "dark",
                        accent = theme?.optString("accent")?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) },
                        font = if (theme?.optString("font") == "system") "system" else "inter",
                        textScale = (theme?.optDouble("textScale", 1.0) ?: 1.0).toFloat().coerceIn(0.5f, 2f),
                        tileOpacity = (theme?.optInt("tileOpacity", 100) ?: 100).coerceIn(0, 100),
                        gap = (theme?.optInt("gap", 8) ?: 8).coerceIn(0, 32),
                        animations = theme?.optBoolean("animations", true) ?: true,
                        cards = theme?.optBoolean("cards", true) ?: true,
                        tiles = TileStyle.fromJson(theme?.optJSONObject("tiles")),
                    )
                },
                pages = (0 until pages.length()).mapNotNull { pages.optJSONObject(it) }.mapIndexed { i, page ->
                    val tiles = page.optJSONArray("tiles") ?: JSONArray()
                    Page(
                        id = page.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = page.optString("name").ifBlank { "Page ${i + 1}" },
                        tiles = tilesFromJson((0 until tiles.length()).mapNotNull { tiles.optJSONObject(it) }, columns, rows),
                    )
                }.ifEmpty { DEFAULT.pages },
            )
        }

        /** Parses a page's tiles, placing those without a position after the ones with one. */
        fun tilesFromJson(tiles: List<JSONObject>, columns: Int, rows: Int): List<Tile> {
            val parsed = tiles.mapNotNull { json -> tileFromJson(json, columns, rows)?.let { it to (json.has("x") && json.has("y")) } }
            val result = parsed.map { it.first }.toMutableList()
            val placed = parsed.filter { it.second }.map { it.first }.toMutableList()
            for (i in parsed.indices) {
                if (parsed[i].second) continue
                val tile = parsed[i].first
                // No room: leave it at the top left, where the compiler reports the overlap.
                val spot = freeSpot(placed, tile.w, tile.h, columns, rows)
                result[i] = if (spot != null) tile.copy(x = spot.first, y = spot.second) else tile
                placed += result[i]
            }
            return result
        }

        /** The first top-left cell, row by row, where a [w] × [h] tile fits without overlapping [tiles]. */
        fun freeSpot(tiles: List<Tile>, w: Int, h: Int, columns: Int, rows: Int): Pair<Int, Int>? {
            for (y in 0..rows - h) {
                for (x in 0..columns - w) {
                    if (tiles.none { x < it.x + it.w && it.x < x + w && y < it.y + it.h && it.y < y + h }) return x to y
                }
            }
            return null
        }

        fun tileFromJson(json: JSONObject, columns: Int, rows: Int): Tile? {
            val type = json.optString("type")
            val default = TileTypes.BY_KEY[type] ?: return null
            val w = json.optInt("w", default.size.first).coerceIn(1, columns)
            val h = json.optInt("h", default.size.second).coerceIn(1, rows)
            val propsJson = json.optJSONObject("props") ?: JSONObject()
            return Tile(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                type = type,
                x = json.optInt("x").coerceIn(0, columns - w),
                y = json.optInt("y").coerceIn(0, rows - h),
                w = w,
                h = h,
                props = propsJson.keys().asSequence().associateWith { propsJson.optString(it) },
            )
        }

        fun Tile.toJson(): JSONObject = JSONObject()
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
    /** How opaque tile backgrounds are, 0–100 %; a tile's own "opacity" overrides it. */
    val tileOpacity: Int = 100,
    /** Space between cards, and around the page's edge, in dp. */
    val gap: Int = 8,
    /** Graphs drawing in, values rolling to new ones, live dots pulsing; off for a still panel. */
    val animations: Boolean = true,
    /** Whether tiles have a card behind them unless a tile says otherwise (its "background"). */
    val cards: Boolean = true,
    /** How tiles are drawn: corners, padding, border, graph lines, value weight. */
    val tiles: TileStyle = TileStyle(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("preset", preset).put("accent", accent ?: "").put("font", font).put("textScale", textScale.toDouble())
        .put("tileOpacity", tileOpacity).put("gap", gap).put("animations", animations).put("cards", cards).put("tiles", tiles.toJson())
}

/**
 * The look of tiles, from a theme or set on the layout (theme.tiles). Colours are "#rrggbb";
 * an empty [borderColor] borrows the theme's muted colour, and empty [graphColors] the theme's
 * accent followed by a built-in set.
 */
data class TileStyle(
    val radius: Int = 16,
    val padding: Int = 12,
    val borderWidth: Int = 0,
    val borderColor: String = "",
    val graphLineWidth: Int = 2,
    val graphFill: Boolean = true,
    val graphColors: List<String> = emptyList(),
    /** "light", "regular" or "bold": the big numbers' weight. */
    val valueWeight: String = "light",
    /** Card shadow in dp; 0 for none. */
    val shadow: Int = 0,
    /** Tile titles: "regular", "medium" or "bold"; "muted" or "text" colour; a line under them. */
    val titleWeight: String = "medium",
    val titleColor: String = "muted",
    val titleDivider: Boolean = false,
    /** "fill": a toggle lights up in the accent when on; "switch": it shows a switch; "icon": only its icon lights up. */
    val toggleStyle: String = "fill",
    /** The clock's weight: "thin", "light" or "regular". */
    val clockWeight: String = "thin",
    /** Dashed guide lines across graphs. */
    val graphGrid: Boolean = false,
    /** Lines between the rows of lists (calendar events). */
    val listDividers: Boolean = false,
    /** A tile of several calendars marks each event with its calendar's colour (graphColors). */
    val calendarColors: Boolean = true,
    /** Value and toggle tiles: "stacked" (name over a big number) or "compact" (icon beside name and state). */
    val tileLayout: String = "stacked",
    /** Icons on value and toggle tiles: "none", "badge" (tinted circle) or "bubble" (solid circle). */
    val icons: String = "none",
    /** Value and toggle tiles: "card" (the radius above) or "pill" (fully rounded ends). */
    val buttonShape: String = "card",
    /**
     * Thermostats: "dial" (an open arc), "numbers" (a big target with − and +), "ring" (a disc that
     * glows, ticks lit), "bar" (a cold-to-warm track), "pill" (one row) or "thermometer".
     */
    val thermostatStyle: String = "dial",
    /** Sliders: "bar" (a track under the name) or "fill" (the whole tile fills to the level). */
    val sliderStyle: String = "bar",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("radius", radius).put("padding", padding)
        .put("borderWidth", borderWidth).put("borderColor", borderColor)
        .put("graphLineWidth", graphLineWidth).put("graphFill", graphFill).put("graphColors", JSONArray(graphColors))
        .put("valueWeight", valueWeight)
        .put("shadow", shadow).put("titleWeight", titleWeight).put("titleColor", titleColor).put("titleDivider", titleDivider)
        .put("toggleStyle", toggleStyle).put("clockWeight", clockWeight).put("graphGrid", graphGrid)
        .put("listDividers", listDividers).put("calendarColors", calendarColors)
        .put("tileLayout", tileLayout).put("icons", icons).put("buttonShape", buttonShape)
        .put("thermostatStyle", thermostatStyle).put("sliderStyle", sliderStyle)

    companion object {
        private val HEX = Regex("#[0-9a-fA-F]{6}")
        val WEIGHTS = setOf("light", "regular", "bold")
        val THERMOSTAT_STYLES = setOf("dial", "numbers", "ring", "bar", "pill", "thermometer")

        fun fromJson(json: JSONObject?): TileStyle {
            if (json == null) return TileStyle()
            return TileStyle(
                radius = json.optInt("radius", 16).coerceIn(0, 48),
                padding = json.optInt("padding", 12).coerceIn(0, 32),
                borderWidth = json.optInt("borderWidth", 0).coerceIn(0, 8),
                borderColor = json.optString("borderColor").takeIf { it.matches(HEX) }.orEmpty(),
                graphLineWidth = json.optInt("graphLineWidth", 2).coerceIn(1, 8),
                graphFill = json.optBoolean("graphFill", true),
                graphColors = json.optJSONArray("graphColors")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                    ?.filter { it.matches(HEX) }?.take(12).orEmpty(),
                valueWeight = json.optString("valueWeight").takeIf { it in WEIGHTS } ?: "light",
                shadow = json.optInt("shadow", 0).coerceIn(0, 24),
                titleWeight = json.optString("titleWeight").takeIf { it in setOf("regular", "medium", "bold") } ?: "medium",
                titleColor = json.optString("titleColor").takeIf { it in setOf("muted", "text") } ?: "muted",
                titleDivider = json.optBoolean("titleDivider", false),
                toggleStyle = json.optString("toggleStyle").takeIf { it in setOf("fill", "switch", "icon") } ?: "fill",
                clockWeight = json.optString("clockWeight").takeIf { it in setOf("thin", "light", "regular") } ?: "thin",
                graphGrid = json.optBoolean("graphGrid", false),
                listDividers = json.optBoolean("listDividers", false),
                calendarColors = json.optBoolean("calendarColors", true),
                tileLayout = json.optString("tileLayout").takeIf { it in setOf("stacked", "compact") } ?: "stacked",
                icons = json.optString("icons").takeIf { it in setOf("none", "badge", "bubble") } ?: "none",
                buttonShape = json.optString("buttonShape").takeIf { it in setOf("card", "pill") } ?: "card",
                thermostatStyle = json.optString("thermostatStyle").takeIf { it in THERMOSTAT_STYLES } ?: "dial",
                sliderStyle = json.optString("sliderStyle").takeIf { it in setOf("bar", "fill") } ?: "bar",
            )
        }
    }
}

/**
 * Behind all pages: the theme's colour, one colour, a gradient (optionally slowly turning) or a
 * picture stored on the tablet (BackgroundStore), shown by [mode] and darkened by [dim] percent.
 */
data class Background(
    val type: String = "theme",
    val color: String = "#202020",
    val colors: List<String> = listOf("#1d2b3a", "#3a1d2e"),
    val angle: Int = 135,
    val animate: Boolean = false,
    val image: String? = null,
    val mode: String = "fill",
    val dim: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type).put("color", color).put("colors", JSONArray(colors))
        .put("angle", angle).put("animate", animate).put("image", image ?: "")
        .put("mode", mode).put("dim", dim)

    companion object {
        val TYPES = setOf("theme", "color", "gradient", "image")
        val MODES = setOf("fill", "fit", "stretch", "tile", "center")
        private val HEX = Regex("#[0-9a-fA-F]{6}")

        fun fromJson(json: JSONObject): Background {
            val colors = json.optJSONArray("colors")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?.filter { it.matches(HEX) }?.takeIf { it.size >= 2 }
            return Background(
                type = json.optString("type").takeIf { it in TYPES } ?: "theme",
                color = json.optString("color").takeIf { it.matches(HEX) } ?: "#202020",
                colors = colors?.take(3) ?: Background().colors,
                angle = json.optInt("angle", 135).mod(360),
                animate = json.optBoolean("animate"),
                // Only a bare file name from BackgroundStore, never a path.
                image = json.optString("image").takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) },
                mode = json.optString("mode").takeIf { it in MODES } ?: "fill",
                dim = json.optInt("dim").coerceIn(0, 90),
            )
        }
    }
}

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
