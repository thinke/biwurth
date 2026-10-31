package io.github.thinke.biwurth.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * What each tile type is and which props it reads: the source for /api/schema, default sizes when
 * a layout leaves them out, and which tiles suit an entity. Keep in step with ui/Tiles.kt (what the
 * props do) and TYPES/FIELDS in assets/web/app.js (the editor's forms).
 */
object TileTypes {
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
            props = listOf(Prop("entity", "entity id", "The entity shown.", required = true), name, unit, decimals, opacity, textSize),
        ),
        Type(
            "toggle", "Toggle / button",
            "Tap to toggle a light, switch, fan, cover…, or run a script or scene. Lit when on. " +
                "Can show a measurement under the name (e.g. the plug's power).",
            size = 2 to 2, minSize = 2 to 1,
            props = listOf(
                Prop("entity", "entity id", "The entity tapping controls.", required = true),
                name,
                Prop("measurement", "entity id", "Optional numeric entity shown under the name, e.g. sensor.coffee_maker_power."),
                unit, decimals, opacity, textSize,
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
                unit, decimals, opacity, textSize,
            ),
            domains = listOf("sensor", "input_number", "number"), multi = true,
        ),
        Type(
            "weather", "Weather", "Current conditions and a daily forecast from a weather entity. Wider tiles show more days.",
            size = 4 to 3, minSize = 3 to 2,
            props = listOf(Prop("entity", "entity id", "A weather.* entity.", required = true), name, opacity, textSize),
            domains = listOf("weather"),
        ),
        Type(
            "calendar", "Calendar", "Upcoming events from one or more calendars, soonest first.",
            size = 4 to 4, minSize = 3 to 2,
            props = listOf(
                Prop("entity", "entity ids", "One or more calendar.* entities, comma separated.", required = true),
                name,
                Prop("days", "integer 1-60", "Days ahead. Default 7."),
                opacity, textSize,
            ),
            domains = listOf("calendar"), multi = true,
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
        return buildList {
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
