package io.github.thinke.biwurth.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * What each tile type is and which props it reads: the source for /api/schema, default sizes when
 * a layout leaves them out, and which tiles suit an entity. Keep in step with ui/Tiles.kt (what the
 * props do) and TYPES/FIELDS in assets/web/app.js (the editor's forms).
 */
object TileTypes {
    /** The built-in icons (assets/icons), for tiles' "icon". */
    val ICON_NAMES = listOf("bed", "bolt", "bulb", "calendar", "coffee", "computer", "drop", "fan", "gauge", "home", "moon", "plug", "power", "snowflake", "sun", "thermometer", "tv")

    class Prop(
        val key: String,
        val kind: String,
        val description: String,
        val options: List<String> = emptyList(),
        val required: Boolean = false,
    )

    class Type(
        val key: String,
        val label: String,
        val description: String,
        /** Size the editor gives a new tile, in grid cells. */
        val size: Pair<Int, Int>,
        /** Smallest size that still reads well on a 12 × 8 grid. */
        val minSize: Pair<Int, Int>,
        val props: List<Prop>,
        /** Home Assistant domains its entity can come from; empty for any or none. */
        val domains: List<String> = emptyList(),
        /** Whether "entity" can list several ids, comma separated. */
        val multi: Boolean = false,
    )

    private val name = Prop("name", "text", "Title on the tile. Default: the entity's friendly name.")
    private val opacity = Prop("opacity", "integer 0-100", "Tile background opacity in %. Default: the theme's tileOpacity.")
    private val textSize = Prop(
        "textSize", "enum",
        "Fixed text size. Default (empty): the largest that fits the tile.",
        listOf("", "small", "medium", "large", "xl"),
    )
    private val background = Prop(
        "background", "enum", "\"none\" draws the tile without its card, straight on the panel's background. A toggle still lights up when on.",
        listOf("", "none"),
    )
    private val icon = Prop(
        "icon", "enum",
        "Icon in the compact layout (theme.tiles.icons): default picks one for the entity; \"none\" for none.",
        listOf("", "none") + ICON_NAMES,
    )
    private val unit = Prop("unit", "text", "Unit shown after the value. Default: the entity's unit_of_measurement.")
    private val decimals = Prop("decimals", "integer", "Decimals shown. Default: as Home Assistant reports it.")

    val ALL: List<Type> = listOf(
        Type(
            "clock", "Clock", "The time, large, with the date under it.",
            size = 4 to 2, minSize = 2 to 1,
            props = listOf(Prop("date", "enum", "\"off\" hides the date.", listOf("", "off")), opacity, textSize),
        ),
        Type(
            "entity", "Entity value", "One entity's current state with its name: a temperature, a power reading, a door.",
            size = 3 to 2, minSize = 2 to 1,
            props = listOf(Prop("entity", "entity id", "The entity shown.", required = true), name, icon, unit, decimals, opacity, background, textSize),
        ),
        Type(
            "toggle", "Toggle / button",
            "Tap to toggle a light, switch, fan, cover…, or run a script or scene. Lit when on. " +
                "Can show a measurement under the name (e.g. the plug's power).",
            size = 2 to 2, minSize = 2 to 1,
            props = listOf(
                Prop("entity", "entity id", "The entity tapping controls.", required = true),
                name,
                icon,
                Prop("measurement", "entity id", "Optional numeric entity shown under the name, e.g. sensor.coffee_maker_power."),
                unit, decimals, opacity, background, textSize,
            ),
            domains = listOf("light", "switch", "fan", "input_boolean", "script", "scene", "button", "input_button", "cover", "media_player", "climate", "lock"),
        ),
        Type(
            "graph", "History graph", "Line graph of numeric entities over the last hours, one line per entity, with the current values.",
            size = 6 to 3, minSize = 3 to 2,
            props = listOf(
                Prop("entity", "entity ids", "One or more numeric entities, comma separated.", required = true),
                name,
                Prop("labels", "text list", "Line labels in the same order as the entities, comma separated. Default: friendly names."),
                Prop("hours", "integer 1-336", "Hours shown. Default 24."),
                unit, decimals, opacity, background, textSize,
            ),
            domains = listOf("sensor", "input_number", "number"), multi = true,
        ),
        Type(
            "weather", "Weather", "Current conditions and a daily forecast from a weather entity. Wider tiles show more days.",
            size = 4 to 3, minSize = 3 to 2,
            props = listOf(Prop("entity", "entity id", "A weather.* entity.", required = true), name, opacity, background, textSize),
            domains = listOf("weather"),
        ),
        Type(
            "calendar", "Calendar", "Upcoming events from one or more calendars, soonest first.",
            size = 4 to 4, minSize = 3 to 2,
            props = listOf(
                Prop("entity", "entity ids", "One or more calendar.* entities, comma separated.", required = true),
                name,
                Prop("view", "enum", "\"\" a list of what's ahead, \"week\" a column per day from today, \"month\" the month as a grid (give it 6×5 or more).", listOf("", "week", "month")),
                Prop("days", "integer 1-60", "Days ahead in the list view. Default 7."),
                opacity, background, textSize,
            ),
            domains = listOf("calendar"), multi = true,
        ),
        Type(
            "thermostat", "Thermostat",
            "A climate entity: current and target temperature, heating or cooling, − and + for the target, and its modes. The theme draws it as a dial or as numbers.",
            size = 3 to 3, minSize = 2 to 2,
            props = listOf(Prop("entity", "entity id", "A climate.* entity.", required = true), name, opacity, background, textSize),
            domains = listOf("climate"),
        ),
        Type(
            "slider", "Slider",
            "Drag to set a light's brightness, a cover's position, a fan's speed, a media player's volume or a number. A tap toggles a light or fan.",
            size = 3 to 1, minSize = 2 to 1,
            props = listOf(Prop("entity", "entity id", "A light, cover, fan, media_player, number or input_number.", required = true), name, icon, opacity, background, textSize),
            domains = listOf("light", "cover", "fan", "media_player", "number", "input_number"),
        ),
        Type(
            "space", "Empty space",
            "Keeps its cells empty: a gap in the layout, or room for a picture (JPEG, PNG, WebP or SVG, drawn sharp at any size). Works down to 1×1.",
            size = 1 to 1, minSize = 1 to 1,
            props = listOf(
                Prop("picture", "picture", "Optional: a file name from PUT /api/picture. Empty leaves the space blank."),
                Prop("fit", "enum", "How the picture fills the tile: default shows all of it, \"cover\" fills it (cropping), \"stretch\" fills it exactly.", listOf("", "cover", "stretch")),
                opacity,
            ),
        ),
        Type(
            "camera", "Camera", "A live camera stream (snapshot while it starts). Tap for full screen.",
            size = 6 to 4, minSize = 3 to 2,
            props = listOf(
                Prop("camera", "text", "A go2rtc stream name (GET /api/cameras lists them) or a full rtsp:// URL.", required = true),
                name,
                Prop("fullStream", "text", "Stream for full screen, e.g. a higher-resolution one. Default: the same."),
                Prop("snapshot", "url", "Snapshot picture URL. Default: from go2rtc."),
                Prop("fit", "enum", "\"contain\" shows the whole picture; default fills the tile.", listOf("", "contain")),
                opacity, textSize,
            ),
        ),
    )

    val BY_KEY: Map<String, Type> = ALL.associateBy { it.key }

    /**
     * An entity's Home Assistant domain: "sensor" for sensor.x and for another Home Assistant's
     * src.<source>.sensor.x; "src" for a field of biwurth's own sources (src.<source>.<field>).
     */
    fun domainOf(entityId: String): String {
        if (!entityId.startsWith("src.")) return entityId.substringBefore('.')
        val rest = entityId.removePrefix("src.").substringAfter('.')
        return if ('.' in rest) rest.substringBefore('.') else "src"
    }

    /** The tile types that can show [entityId], the best fit first. */
    fun suitableFor(entityId: String, numeric: Boolean): List<String> {
        val domain = domainOf(entityId)
        // Own entities (src.<source>.<field>) behave like sensors.
        val sensorLike = domain == "src" || domain in BY_KEY.getValue("graph").domains
        if (domain == "climate") return listOf("thermostat", "entity")
        return buildList {
            if (domain in BY_KEY.getValue("slider").domains) add("slider")
            if (domain in BY_KEY.getValue("toggle").domains) add("toggle")
            if (domain == "weather") add("weather")
            if (domain == "calendar") add("calendar")
            if (sensorLike && numeric) add("graph")
            if (domain !in setOf("weather", "calendar", "script", "scene", "button", "input_button")) add("entity")
        }.sortedBy { if (it == "entity" && sensorLike) -1 else 0 }
    }

    fun toJson(): JSONArray = JSONArray(
        ALL.map { type ->
            JSONObject()
                .put("type", type.key)
                .put("label", type.label)
                .put("description", type.description)
                .put("defaultSize", JSONObject().put("w", type.size.first).put("h", type.size.second))
                .put("minSize", JSONObject().put("w", type.minSize.first).put("h", type.minSize.second))
                .put("domains", JSONArray(type.domains))
                .put("multipleEntities", type.multi)
                .put(
                    "props",
                    JSONArray(
                        type.props.map { prop ->
                            JSONObject().put("key", prop.key).put("kind", prop.kind).put("description", prop.description)
                                .put("required", prop.required)
                                .apply { if (prop.options.isNotEmpty()) put("options", JSONArray(prop.options)) }
                        },
                    ),
                )
        },
    )
}
