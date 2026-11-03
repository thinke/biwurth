package io.github.thinke.biwurth.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * A heating plant drawn free-form, as a building automation screen: parts placed on a grid of
 * [w] × [h] units and pipes drawn between them. A plant tile keeps it as JSON in its "diagram"
 * prop; the tile scales the grid to fit, keeping its proportions.
 */
data class Diagram(val w: Int, val h: Int, val parts: List<Part>, val pipes: List<Pipe>) {
    /** A part at [x], [y] (its top left) of [w] × [h] units; [props] as Kind.props says. */
    data class Part(val id: String, val kind: String, val x: Float, val y: Float, val w: Float, val h: Float, val props: Map<String, String>) {
        fun prop(key: String): String? = props[key]?.takeIf { it.isNotBlank() }
    }

    /**
     * A pipe through [points] (grid units), drawn in [kind]'s colour. Water (or refrigerant)
     * moves from the first point to the last while [flow] runs: a pump, a burner, an AC.
     */
    data class Pipe(val id: String, val kind: String, val points: List<Pair<Float, Float>>, val flow: String?)

    /** Every entity the diagram reads. */
    fun entityIds(): List<String> =
        (parts.flatMap { part -> (KINDS[part.kind]?.entities.orEmpty()).mapNotNull(part::prop) } + pipes.mapNotNull { it.flow }).distinct()

    /** A box in grid units that a part draws: its [what] is "shape", "text", "name", "reading", "readings" or "flue". */
    data class Box(val x: Float, val y: Float, val w: Float, val h: Float, val what: String)

    /**
     * Where [part] draws on the panel (ui/PlantTile.kt): its shape and the text it puts round it,
     * names above, readings below and beside. Keep in step with footprints() in web/diagram.js.
     */
    fun footprints(part: Part): List<Box> {
        val (x, y, w, h) = listOf(part.x, part.y, part.w, part.h)
        fun has(key: String) = part.prop(key) != null
        val out = mutableListOf(Box(x, y, w, h, if (part.kind in setOf("sensor", "text")) "text" else "shape"))
        fun above(text: String, width: Float = textWidth(text)) { out += Box(x, y - 1.6f, minOf(width, maxOf(w * 2, 12f)), 1.6f, "name") }
        fun below(width: Float) { out += Box(x, y + h, width, 1.6f, "reading") }
        fun beside(rows: Int) { if (rows > 0) out += Box(x + w + 0.6f, y + h / 2 - rows * 0.8f, 6.5f, rows * 1.6f, "readings") }
        val airRows = listOf(has("in"), has("out"), has("in") && has("out")).count { it }
        when (part.kind) {
            "radiator", "floor" -> { above(nameOf(part)); if (has("temp")) below(4f) }
            "tank" -> above(nameOf(part))
            "outdoor" -> { part.prop("name")?.let { above(it) }; if (has("in") && has("out")) below(5f) }
            "indoor" -> {
                if (has("name") || has("ac")) above(part.prop("name") ?: "Air conditioner")
                if (has("room") || has("ac")) below(8f)
                beside(airRows)
            }
            "airheater" -> {
                above(nameOf(part), w * 0.88f - 1.2f)
                if (has("flue")) out += Box(x + w * 0.88f + 0.6f, y - 1.6f, 8f, 1.6f, "flue")
                beside(airRows)
            }
            "valve" -> if (has("position")) {
                out += if (part.prop("dir") == "vertical") Box(x + w + 0.4f, y + h / 2 - 0.8f, 6f, 1.6f, "reading") else Box(x + w / 2 - 3f, y - 1.6f, 6f, 1.6f, "reading")
            }
        }
        return out
    }

    /** What overlaps: parts and their text on each other, and pipes across text, as sentences. */
    fun overlaps(): List<String> {
        fun label(p: Part) = nameOf(p).ifEmpty { p.prop("label") ?: p.prop("text") ?: KINDS[p.kind]?.label ?: p.kind }
        fun piece(b: Box) = mapOf("name" to "’s name", "reading" to "’s reading", "readings" to "’s readings", "flue" to "’s flue")[b.what].orEmpty()
        val prints = parts.map { it to footprints(it) }
        val found = mutableListOf<String>()
        for (i in prints.indices) for (j in i + 1 until prints.size) {
            val hit = prints[i].second.firstNotNullOfOrNull { a -> prints[j].second.firstOrNull { b -> intersects(a, b) }?.let { a to it } } ?: continue
            found += "${label(prints[i].first)}${piece(hit.first)} and ${label(prints[j].first)}${piece(hit.second)} overlap"
        }
        for (pipe in pipes) for ((part, boxes) in prints) {
            val crossed = boxes.filter { it.what != "shape" }.firstOrNull { b -> pipe.points.zipWithNext().any { (p, q) -> crosses(p, q, b) } } ?: continue
            found += "A ${pipe.kind} pipe runs across ${label(part)}${piece(crossed).ifEmpty { "’s text" }}"
        }
        return found
    }

    /** A part kind: what it's called and which of its props are entities (the rest are text or choices). */
    class Kind(val label: String, val entities: List<String>, val text: List<String> = emptyList(), val size: Pair<Int, Int>)

    companion object {
        // Roughly how wide the panel draws [text] in grid units: small text is 1.2 units high.
        private fun textWidth(text: String, size: Float = 1.2f) = text.length * size * 0.56f

        fun nameOf(part: Part): String = when (part.kind) {
            "source" -> part.prop("name") ?: SOURCES[part.prop("type") ?: "oil"].orEmpty()
            else -> part.prop("name") ?: mapOf("radiator" to "Radiators", "floor" to "Floor heating", "tank" to "Hot water", "airheater" to "Wood air heater")[part.kind].orEmpty()
        }

        // Text needs a little clear space round it to read; shapes may touch.
        private fun intersects(a: Box, b: Box): Boolean {
            val gap = if (a.what == "shape" && b.what == "shape") -0.05f else 0.3f
            return a.x < b.x + b.w + gap && b.x < a.x + a.w + gap && a.y < b.y + b.h + gap && b.y < a.y + a.h + gap
        }

        // A pipe segment through a box (Liang–Barsky clipping).
        private fun crosses(p: Pair<Float, Float>, q: Pair<Float, Float>, r: Box): Boolean {
            var t0 = 0f
            var t1 = 1f
            val dx = q.first - p.first
            val dy = q.second - p.second
            for ((a, b) in listOf(-dx to p.first - r.x, dx to r.x + r.w - p.first, -dy to p.second - r.y, dy to r.y + r.h - p.second)) {
                if (a == 0f) { if (b < 0) return false; continue }
                val t = b / a
                if (a < 0) t0 = maxOf(t0, t) else t1 = minOf(t1, t)
                if (t0 > t1) return false
            }
            return true
        }

        val PIPE_KINDS = listOf("hot", "cold", "warm", "refrigerant", "air", "flue")

        /** Heat sources a "source" part can be ("type"), with the name it gets. */
        val SOURCES = linkedMapOf(
            "oil" to "Oil boiler", "gas" to "Gas boiler", "pellet" to "Pellet boiler", "wood" to "Wood stove",
            "electric" to "Electric heater", "heatpump" to "Heat pump", "district" to "District heat", "solar" to "Solar collector",
        )

        val KINDS: Map<String, Kind> = linkedMapOf(
            "source" to Kind("Heat source", entities = listOf("state", "temp"), text = listOf("name", "type"), size = 8 to 10),
            "airheater" to Kind("Wood air heater", entities = listOf("state", "fan", "core", "in", "out", "flue"), text = listOf("name"), size = 11 to 8),
            "radiator" to Kind("Radiator", entities = listOf("active", "temp"), text = listOf("name"), size = 8 to 5),
            "floor" to Kind("Floor heating", entities = listOf("active", "temp"), text = listOf("name"), size = 8 to 5),
            "tank" to Kind("Hot-water tank", entities = listOf("temp", "active"), text = listOf("name"), size = 6 to 9),
            "pump" to Kind("Pump", entities = listOf("state"), text = listOf("dir"), size = 3 to 3),
            "valve" to Kind("Mixing valve", entities = listOf("position"), text = listOf("dir"), size = 3 to 3),
            "outdoor" to Kind("AC outdoor unit", entities = listOf("ac", "in", "out"), text = listOf("name"), size = 7 to 8),
            "indoor" to Kind("AC indoor unit", entities = listOf("ac", "in", "out", "room"), text = listOf("name"), size = 9 to 4),
            "sensor" to Kind("Sensor value", entities = listOf("entity", "from"), text = listOf("label", "unit", "point"), size = 6 to 2),
            "text" to Kind("Text", entities = emptyList(), text = listOf("text"), size = 8 to 2),
        )

        /** The diagram in a tile's "diagram" prop, or null for none or JSON that isn't one. */
        fun of(json: String?): Diagram? {
            if (json.isNullOrBlank()) return null
            return runCatching { fromJson(JSONObject(json)) }.getOrNull()
        }

        fun fromJson(json: JSONObject): Diagram {
            val parts = json.optJSONArray("parts") ?: JSONArray()
            val pipes = json.optJSONArray("pipes") ?: JSONArray()
            return Diagram(
                w = json.optInt("w", 48).coerceIn(8, 200),
                h = json.optInt("h", 30).coerceIn(8, 200),
                parts = (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }.mapIndexedNotNull { i, p ->
                    val kind = p.optString("kind").takeIf { it in KINDS } ?: return@mapIndexedNotNull null
                    val size = KINDS.getValue(kind).size
                    val props = p.optJSONObject("props") ?: JSONObject()
                    Part(
                        id = p.optString("id").ifBlank { "part$i" }, kind = kind,
                        x = p.optDouble("x", 0.0).toFloat(), y = p.optDouble("y", 0.0).toFloat(),
                        w = p.optDouble("w", size.first.toDouble()).toFloat().coerceAtLeast(1f),
                        h = p.optDouble("h", size.second.toDouble()).toFloat().coerceAtLeast(1f),
                        props = props.keys().asSequence().associateWith { props.optString(it) },
                    )
                },
                pipes = (0 until pipes.length()).mapNotNull { pipes.optJSONObject(it) }.mapIndexedNotNull { i, p ->
                    val points = p.optJSONArray("points") ?: return@mapIndexedNotNull null
                    val list = (0 until points.length()).mapNotNull { points.optJSONArray(it) }
                        .map { it.optDouble(0).toFloat() to it.optDouble(1).toFloat() }
                        .filter { !it.first.isNaN() && !it.second.isNaN() }
                    if (list.size < 2) return@mapIndexedNotNull null
                    Pipe(
                        id = p.optString("id").ifBlank { "pipe$i" },
                        kind = p.optString("kind").takeIf { it in PIPE_KINDS } ?: "hot",
                        points = list,
                        flow = p.optString("flow").ifBlank { null },
                    )
                },
            )
        }
    }
}
