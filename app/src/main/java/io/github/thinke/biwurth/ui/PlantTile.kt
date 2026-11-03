package io.github.thinke.biwurth.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.thinke.biwurth.DataHub
import io.github.thinke.biwurth.config.Diagram
import io.github.thinke.biwurth.config.Tile
import io.github.thinke.biwurth.ha.HomeAssistant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// Hot pipes orange, cold blue, as on every building automation screen; heating and cooling
// in the thermostat's colours.
private val HOT = Color(0xFFFF6B3D)
private val COLD = Color(0xFF3B9CF5)
private val WARM = Color(0xFFE8A33D)
private val REFRIGERANT = Color(0xFFB07CF0)
private val HEAT = Color(0xFFFF8A3D)
private val COOL = Color(0xFF3B9CF5)
private val FLAME_CORE = Color(0xFFFFD166)
private val AIR = Color(0xFFFFB070)
private val FLUE = Color(0xFF8A8F98)

private fun pipeColor(kind: String) = when (kind) {
    "cold" -> COLD
    "warm" -> WARM
    "refrigerant" -> REFRIGERANT
    "air" -> AIR
    "flue" -> FLUE
    else -> HOT
}

/**
 * A heating plant drawn free-form (config/Diagram): heat sources of any kind and number, pipes
 * between them, radiators, floor heating, tanks, pumps, valves, AC units and sensor readings,
 * where the diagram puts them. Water moves in a pipe while its "flow" entity runs.
 */
@Composable
internal fun BoxWithConstraintsScope.PlantTile(tile: Tile, ha: DataHub) {
    val theme = LocalPanelTheme.current
    val maxHeight = maxHeight
    val diagram = remember(tile.prop("diagram")) { Diagram.of(tile.prop("diagram")) }
    if (diagram == null || (diagram.parts.isEmpty() && diagram.pipes.isEmpty())) {
        return Title(pickHint(tile, "Draw the plant: Edit diagram on the website"), maxHeight)
    }
    val ids = remember(diagram) { diagram.entityIds() }
    val values = ha.entities(ids)
    val byId = ids.zip(values).toMap()
    fun of(id: String?) = id?.let(byId::get)

    // One clock for everything that moves; each motion repeats once per cycle, so the loop has
    // no seam. Nothing runs while all is still.
    val moving = LocalAnimations.current && (
        diagram.pipes.any { running(of(it.flow)) } ||
            diagram.parts.any { it.kind in setOf("outdoor", "indoor") && acState(of(it.prop("ac")), theme.muted).running } ||
            diagram.parts.any { it.kind == "airheater" && running(of(it.prop("fan"))) }
        )
    val phase: State<Float> = if (moving) {
        rememberInfiniteTransition(label = "plant").animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "flow")
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val base = LocalTextStyle.current
    val textScale = LocalTextScale.current
    val weight = valueWeight()
    val measurer = rememberTextMeasurer(cacheSize = 64)

    Column(Modifier.fillMaxSize()) {
        tile.prop("name")?.let { Title(it, maxHeight) }
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = if (tile.prop("name") != null) 6.dp else 0.dp)) {
            val u = minOf(size.width / diagram.w, size.height / diagram.h)
            val origin = Offset((size.width - u * diagram.w) / 2, (size.height - u * diagram.h) / 2)
            val d = Draw(this, measurer, u, origin, phase.value, theme, base, textScale, weight)
            for (pipe in diagram.pipes) d.pipe(pipe, running(of(pipe.flow)))
            for (part in diagram.parts) d.part(part, ::of)
        }
    }
}

/** Draws a diagram's pipes and parts at [u] pixels per grid unit, from [origin]. */
private class Draw(
    val scope: DrawScope,
    val measurer: TextMeasurer,
    val u: Float,
    val origin: Offset,
    val t: Float,
    val theme: PanelTheme,
    base: TextStyle,
    private val textScale: Float,
    weight: FontWeight,
) {
    private val minText = with(scope) { 11.dp.toPx() }
    private fun px(v: Float) = with(scope) { (v.coerceAtLeast(minText) * textScale).toSp() }
    val small = base.merge(TextStyle(color = theme.muted, fontSize = px(u * 1.2f)))
    val reading = small.copy(color = theme.text)
    val valueBase = base.merge(TextStyle(color = theme.text, fontWeight = weight))
    val line = theme.muted.copy(alpha = 0.6f)
    val box = theme.background.copy(alpha = 0.3f)
    val stroke = with(scope) { 1.5.dp.toPx() }
    val pipeW = with(scope) { (u * 0.55f).coerceIn(2.dp.toPx(), 8.dp.toPx()) }
    val smallH = measurer.measure("Ag", small).size.height.toFloat()

    fun value(sizePx: Float) = valueBase.copy(fontSize = px(sizePx))

    fun at(x: Float, y: Float) = Offset(origin.x + x * u, origin.y + y * u)

    /** Draws [s] anchored at [x], [y] by [ax], [ay]; [fit] shrinks it (to 60 % at most) before cutting it short. */
    fun text(s: String, style: TextStyle, x: Float, y: Float, ax: Float = 0f, ay: Float = 0f, maxW: Float = scope.size.width, fit: Boolean = false) {
        if (s.isEmpty()) return
        var style = style
        if (fit) {
            val width = measurer.measure(s, style).size.width
            if (width > maxW) style = style.copy(fontSize = style.fontSize * (maxW / width * 0.97f).coerceAtLeast(0.6f))
        }
        val m = measurer.measure(s, style, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxW.toInt().coerceAtLeast(1)))
        scope.drawText(m, topLeft = Offset(x - m.size.width * ax, y - m.size.height * ay))
    }

    /** "Burner 48%" where it fits in [maxW], else "48%". */
    fun named(name: String, value: String, style: TextStyle, maxW: Float) =
        "$name $value".takeIf { measurer.measure(it, style).size.width <= maxW } ?: value

    fun pipe(pipe: Diagram.Pipe, flows: Boolean) = with(scope) {
        val color = pipeColor(pipe.kind)
        val points = pipe.points.map { at(it.first, it.second) }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (p in points.drop(1)) lineTo(p.x, p.y)
        }
        // An air duct is wide and soft, a flue grey; water and refrigerant pipes solid.
        val width = when (pipe.kind) {
            "air" -> pipeW * 2.4f
            "flue" -> pipeW * 1.6f
            else -> pipeW
        }
        drawPath(path, color.copy(alpha = if (pipe.kind == "air") 0.45f else 0.9f), style = Stroke(width, cap = StrokeCap.Butt, join = StrokeJoin.Round))
        if (!flows) return
        // Dashes along it, drawn one by one: older Androids' hardware canvas ignores dash effects.
        val dash = pipeW * 1.2f
        val period = pipeW * 4f
        var start = period * t - period
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len <= 0f) continue
            var s = start
            while (s < len) {
                val s0 = s.coerceAtLeast(0f)
                val s1 = (s + dash).coerceAtMost(len)
                if (s1 > s0) {
                    drawLine(
                        Color.White.copy(alpha = 0.6f),
                        Offset(a.x + (b.x - a.x) * s0 / len, a.y + (b.y - a.y) * s0 / len),
                        Offset(a.x + (b.x - a.x) * s1 / len, a.y + (b.y - a.y) * s1 / len),
                        pipeW * 0.45f, StrokeCap.Round,
                    )
                }
                s += period
            }
            // Carry the pattern on round the corner.
            start = s - len - period
        }
    }

    fun part(part: Diagram.Part, of: (String?) -> HomeAssistant.Entity?) {
        val r = Rect(at(part.x, part.y), Size(part.w * u, part.h * u))
        when (part.kind) {
            "source" -> source(part, r, of)
            "airheater" -> airHeater(part, r, of)
            "radiator" -> radiator(part, r, of, floor = false)
            "floor" -> radiator(part, r, of, floor = true)
            "tank" -> tank(part, r, of)
            "pump" -> pump(part, r, of)
            "valve" -> valve(part, r, of)
            "outdoor" -> outdoor(part, r, of)
            "indoor" -> indoor(part, r, of)
            "sensor" -> sensor(part, r, of)
            "text" -> text(part.prop("text").orEmpty(), small.copy(fontSize = px(r.height * 0.6f)), r.left, r.center.y, ay = 0.5f, maxW = r.width)
        }
    }

    private fun card(r: Rect, corner: Float = 8f, border: Color = line) = with(scope) {
        val c = CornerRadius(corner.dp.toPx().coerceAtMost(minOf(r.width, r.height) / 2))
        drawRoundRect(box, r.topLeft, r.size, c)
        drawRoundRect(border, r.topLeft, r.size, c, style = Stroke(stroke))
    }

    // A part's name above it, its temperature under it: outside the shape, where the diagram leaves room.
    private fun above(s: String?, r: Rect, style: TextStyle = small) = s?.let { text(it, style, r.left, r.top - stroke, ay = 1f, maxW = maxOf(r.width * 2, u * 12)) }
    private fun below(s: String?, r: Rect, style: TextStyle = reading) = s?.let { text(it, style, r.left, r.bottom + stroke, maxW = maxOf(r.width * 2, u * 12)) }

    private fun source(part: Diagram.Part, r: Rect, of: (String?) -> HomeAssistant.Entity?) = with(scope) {
        val type = part.prop("type")?.takeIf { it in Diagram.SOURCES } ?: "oil"
        val state = of(part.prop("state"))
        val on = running(state)
        card(r, border = if (on) HEAT.copy(alpha = 0.8f) else line)
        val pad = u * 0.6f
        val inner = r.width - 2 * pad
        text(part.prop("name") ?: Diagram.SOURCES.getValue(type), small, r.center.x, r.top + pad, ax = 0.5f, maxW = inner, fit = true)
        var bottom = r.bottom - pad
        if (part.prop("state") != null) {
            val status = when {
                state == null || state.state in setOf("unavailable", "unknown") -> "–"
                on && state.state.toDoubleOrNull() != null -> "%.0f%%".format(state.state.toDouble())
                on -> "On"
                else -> "Off"
            }
            val label = when (type) {
                "wood" -> "Fire"
                "electric" -> "Heater"
                "heatpump" -> "Compressor"
                "district", "solar" -> "Heat"
                else -> "Burner"
            }
            val style = small.copy(color = if (on) HEAT else theme.muted)
            text(named(label, status, style, inner), style, r.center.x, bottom, ax = 0.5f, ay = 1f, maxW = inner)
            bottom -= smallH
        }
        if (part.prop("temp") != null) {
            val style = value(minOf(r.height * 0.16f, inner * 0.28f))
            text(deg(of(part.prop("temp"))), style, r.center.x, bottom, ax = 0.5f, ay = 1f, maxW = inner)
            bottom -= measurer.measure("Ag", style).size.height
        }
        val top = r.top + pad + smallH
        val s = minOf(inner * 0.55f, (bottom - top) * 0.8f)
        if (s < u) return
        val c = Offset(r.center.x, (top + bottom) / 2)
        val color = if (on) HEAT else theme.muted.copy(alpha = 0.5f)
        when (type) {
            "electric" -> drawPath(
                Path().apply {
                    moveTo(c.x + s * 0.1f, c.y - s / 2); lineTo(c.x - s * 0.3f, c.y + s * 0.08f); lineTo(c.x - s * 0.02f, c.y + s * 0.08f)
                    lineTo(c.x - s * 0.1f, c.y + s / 2); lineTo(c.x + s * 0.3f, c.y - s * 0.08f); lineTo(c.x + s * 0.02f, c.y - s * 0.08f); close()
                },
                color,
            )
            "heatpump", "district" -> for (k in -1..1) {
                val y = c.y + k * s * 0.3f
                drawPath(
                    Path().apply {
                        moveTo(c.x - s / 2, y)
                        for (i in 1..24) lineTo(c.x - s / 2 + s * i / 24f, y + sin(i / 24f * 2 * PI).toFloat() * s * 0.08f)
                    },
                    color, style = Stroke(s * 0.09f, cap = StrokeCap.Round),
                )
            }
            "solar" -> {
                drawCircle(color, s * 0.22f, c)
                for (k in 0 until 8) {
                    val a = k * PI / 4
                    val from = Offset(c.x + cos(a).toFloat() * s * 0.32f, c.y + sin(a).toFloat() * s * 0.32f)
                    val to = Offset(c.x + cos(a).toFloat() * s * 0.48f, c.y + sin(a).toFloat() * s * 0.48f)
                    drawLine(color, from, to, s * 0.07f, StrokeCap.Round)
                }
            }
            else -> {
                drawPath(flame(c, s), color)
                if (on) drawPath(flame(Offset(c.x, c.y + s * 0.18f), s * 0.55f), FLAME_CORE)
            }
        }
    }

    /**
     * A wood-fired air heater: the firebox with its fire, the air-to-air heat exchanger (its core
     * warm while the fire burns), the fan blowing room air across it, and the flue out of the top.
     * Air in, out and its ΔT beside it.
     */
    private fun airHeater(part: Diagram.Part, r: Rect, of: (String?) -> HomeAssistant.Entity?) = with(scope) {
        val fire = of(part.prop("state"))
        // Without a fire entity, a core warmer than 40° says it burns.
        val burning = if (part.prop("state") != null) running(fire) else (of(part.prop("core"))?.state?.toDoubleOrNull() ?: 0.0) > 40.0
        val fanOn = running(of(part.prop("fan")))
        // The flue: a stub out of the top right, its gas temperature beside it; the name on the left.
        val fx = r.left + r.width * 0.88f
        val flueW = maxOf(u * 1.2f, stroke * 3)
        drawRect(FLUE.copy(alpha = 0.9f), Offset(fx - flueW / 2, r.top - u * 1.6f), Size(flueW, u * 1.6f))
        if (part.prop("flue") != null) text("Flue ${deg(of(part.prop("flue")))}", reading, fx + flueW, r.top - u * 0.8f, ay = 0.5f, maxW = r.width * 0.8f)
        card(r, border = if (burning) HEAT.copy(alpha = 0.8f) else line)
        val pad = u * 0.5f
        val third = r.width / 3
        // The firebox.
        val fb = Rect(r.left + pad, r.top + pad, r.left + third, r.bottom - pad)
        drawRoundRect(theme.background.copy(alpha = 0.5f), fb.topLeft, fb.size, CornerRadius(u * 0.4f))
        val s = minOf(fb.width * 0.7f, fb.height * 0.5f)
        val fc = Offset(fb.center.x, fb.top + fb.height * 0.45f)
        drawPath(flame(fc, s), if (burning) HEAT else theme.muted.copy(alpha = 0.5f))
        if (burning) drawPath(flame(Offset(fc.x, fc.y + s * 0.18f), s * 0.55f), FLAME_CORE)
        if (part.prop("state") != null) {
            text(named("Fire", if (burning) "On" else "Off", small, fb.width), small.copy(color = if (burning) HEAT else theme.muted), fb.center.x, fb.bottom - stroke, ax = 0.5f, ay = 1f, maxW = fb.width)
        }
        // The heat exchanger: plates the air passes between, glowing with the core's heat.
        val core = of(part.prop("core"))?.state?.toFloatOrNull()
        val warmth = core?.let { ((it - 20f) / 100f).coerceIn(0f, 1f) } ?: if (burning) 0.8f else 0f
        val plateColor = lerp(theme.muted.copy(alpha = 0.6f), HOT, warmth)
        val hx0 = r.left + third + pad
        val hx1 = r.left + 2 * third - pad / 2
        val plates = ((hx1 - hx0) / (u * 0.8f)).toInt().coerceIn(3, 10)
        for (k in 0 until plates) {
            val x = hx0 + (hx1 - hx0) * (k + 0.5f) / plates
            drawLine(plateColor, Offset(x, r.top + r.height * 0.18f), Offset(x, r.bottom - r.height * 0.28f), stroke * 1.4f, StrokeCap.Round)
        }
        if (part.prop("core") != null) text(deg(of(part.prop("core"))), reading, (hx0 + hx1) / 2, r.bottom - pad, ax = 0.5f, ay = 1f, maxW = third, fit = true)
        // The fan.
        val fanC = Offset(r.left + 2.5f * third, r.center.y)
        fan(fanC, minOf(third, r.height) * 0.34f, AcState("", AIR, fanOn))
        text(part.prop("name") ?: "Wood air heater", small, r.left, r.top - stroke, ay = 1f, maxW = (fx - flueW - u * 0.5f - r.left).coerceAtLeast(u), fit = true)
        // Air in and out beside it, and how much it warms the air.
        val readingH = measurer.measure("Ag", reading).size.height
        val rows = buildList {
            if (part.prop("in") != null) add("In ${deg(of(part.prop("in")))}" to reading)
            if (part.prop("out") != null) add("Out ${deg(of(part.prop("out")))}" to reading.copy(color = if (fanOn) HEAT else theme.text))
            delta(of(part.prop("in")), of(part.prop("out")))?.let { add("Δ $it" to reading.copy(color = if (fanOn) HEAT else theme.muted)) }
        }
        val x = r.right + u * 0.6f
        rows.forEachIndexed { i, (t, style) ->
            text(t, style, x, r.center.y + (i - (rows.size - 1) / 2f) * readingH, ay = 0.5f, maxW = (size.width - x).coerceAtLeast(1f))
        }
    }

    private fun radiator(part: Diagram.Part, r: Rect, of: (String?) -> HomeAssistant.Entity?, floor: Boolean) = with(scope) {
        // Warm while its "active" entity runs; without one, always.
        val warm = part.prop("active")?.let { running(of(it)) } ?: true
        val color = if (warm) lerp(theme.muted, HOT, 0.7f) else theme.muted.copy(alpha = 0.6f)
        card(r, corner = 4f)
        if (floor) {
            // Floor heating: a pipe winding to and fro.
            val loops = (r.height / (u * 1.2f)).toInt().coerceIn(2, 10)
            val pad = u * 0.6f
            val path = Path().apply {
                for (i in 0 until loops) {
                    val y = r.top + pad + (r.height - 2 * pad) * i / (loops - 1)
                    val (from, to) = if (i % 2 == 0) r.left + pad to r.right - pad else r.right - pad to r.left + pad
                    if (i == 0) moveTo(from, y) else lineTo(from, y)
                    lineTo(to, y)
                }
            }
            drawPath(path, color, style = Stroke(stroke * 1.6f, join = StrokeJoin.Round, cap = StrokeCap.Round))
        } else {
            val fins = (r.width / (u * 1.1f)).toInt().coerceIn(3, 14)
            for (f in 0 until fins) {
                val fx = r.left + r.width * (f + 0.5f) / fins
                drawLine(color, Offset(fx, r.top + r.height * 0.14f), Offset(fx, r.bottom - r.height * 0.14f), r.width / fins * 0.45f, StrokeCap.Round)
            }
        }
        above(part.prop("name") ?: if (floor) "Floor heating" else "Radiators", r)
        if (part.prop("temp") != null) below(deg(of(part.prop("temp"))), r)
    }

    private fun tank(part: Diagram.Part, r: Rect, of: (String?) -> HomeAssistant.Entity?) = with(scope) {
        val temp = of(part.prop("temp"))
        val warmth = (((temp?.state?.toFloatOrNull() ?: 15f) - 15f) / 50f).coerceIn(0f, 1f)
        val c = CornerRadius(minOf(r.width, r.height) * 0.3f)
        drawRoundRect(HOT.copy(alpha = 0.12f + 0.33f * warmth), r.topLeft, r.size, c)
        val charging = part.prop("active")?.let { running(of(it)) } ?: false
        drawRoundRect(if (charging) HEAT else line, r.topLeft, r.size, c, style = Stroke(stroke * if (charging) 1.6f else 1f))
        text(deg(temp), value(minOf(r.width * 0.28f, r.height * 0.2f)), r.center.x, r.center.y, ax = 0.5f, ay = 0.5f, maxW = r.width)
        above(part.prop("name") ?: "Hot water", r)
    }

    private fun pump(part: Diagram.Part, r: Rect, of: (String?) -> HomeAssistant.Entity?) = with(scope) {
        val on = running(of(part.prop("state")))
        val radius = minOf(r.width, r.height) / 2
        val c = r.center
        drawCircle(theme.card, radius, c)
        drawCircle(line, radius, c, style = Stroke(stroke))
        val angle = when (part.prop("dir")) {
            "left" -> 180f
            "up" -> 270f
            "down" -> 90f
            else -> 0f
        }
        rotate(angle, c) {
            drawPath(
                Path().apply { moveTo(c.x - radius * 0.45f, c.y - radius * 0.55f); lineTo(c.x + radius * 0.6f, c.y); lineTo(c.x - radius * 0.45f, c.y + radius * 0.55f); close() },
                if (on) HOT else theme.muted.copy(alpha = 0.6f),
            )
        }
    }

    private fun valve(part: Diagram.Part, r: Rect, of: (String?) -> HomeAssistant.Entity?) = with(scope) {
        val c = r.center
        val vertical = part.prop("dir") in setOf("up", "down", "vertical")
        val a = minOf(r.width, r.height) / 2
        val tie = Path().apply {
            if (vertical) {
                moveTo(c.x - a * 0.7f, c.y - a); lineTo(c.x + a * 0.7f, c.y + a); lineTo(c.x - a * 0.7f, c.y + a); lineTo(c.x + a * 0.7f, c.y - a)
            } else {
                moveTo(c.x - a, c.y - a * 0.7f); lineTo(c.x + a, c.y + a * 0.7f); lineTo(c.x + a, c.y - a * 0.7f); lineTo(c.x - a, c.y + a * 0.7f)
            }
            close()
        }
        drawPath(tie, theme.card)
        drawPath(tie, line, style = Stroke(stroke))
        if (part.prop("position") == null) return
        val label = named("Valve", percent(of(part.prop("position"))), reading, u * 10)
        if (vertical) text(label, reading, r.right + u * 0.4f, c.y, ay = 0.5f) else text(label, reading, c.x, r.top - stroke, ax = 0.5f, ay = 1f)
    }

    private fun outdoor(part: Diagram.Part, r: Rect, of: (String?) -> HomeAssistant.Entity?) = with(scope) {
        val ac = acState(of(part.prop("ac")), theme.muted)
        card(r)
        val pad = u * 0.5f
        val hasIn = part.prop("in") != null
        val hasOut = part.prop("out") != null
        if (hasIn) text("In ${deg(of(part.prop("in")))}", reading, r.center.x, r.top + pad, ax = 0.5f, maxW = r.width - pad)
        if (hasOut) text("Out ${deg(of(part.prop("out")))}", reading.copy(color = if (ac.running) ac.color else theme.text), r.center.x, r.bottom - pad, ax = 0.5f, ay = 1f, maxW = r.width - pad)
        val readingH = measurer.measure("Ag", reading).size.height
        val top = r.top + if (hasIn) readingH + pad else 0f
        val bottom = r.bottom - if (hasOut) readingH + pad else 0f
        fan(Offset(r.center.x, (top + bottom) / 2), minOf(r.width, bottom - top) * 0.36f, ac)
        above(part.prop("name"), r)
        // Its ΔT: how much heat it takes from (or gives to) the outside air.
        delta(of(part.prop("in")), of(part.prop("out")))?.let { below("Δ $it", r, reading.copy(color = if (ac.running) ac.color else theme.muted)) }
    }

    private fun fan(c: Offset, r: Float, ac: AcState) = with(scope) {
        if (r <= 0f) return
        val color = if (ac.running) ac.color else theme.muted.copy(alpha = 0.6f)
        drawCircle(line, r, c, style = Stroke(stroke))
        rotate(if (ac.running) t * 120f else 0f, c) {
            for (k in 0 until 3) rotate(k * 120f, c) {
                drawOval(color, Offset(c.x - r * 0.2f, c.y - r * 0.88f), Size(r * 0.4f, r * 0.8f))
            }
        }
        drawCircle(theme.card, r * 0.14f, c)
    }

    private fun indoor(part: Diagram.Part, r: Rect, of: (String?) -> HomeAssistant.Entity?) = with(scope) {
        val entity = of(part.prop("ac"))
        val ac = acState(entity, theme.muted)
        val color = if (ac.running) ac.color else theme.muted.copy(alpha = 0.6f)
        card(r, corner = 10f)
        drawLine(color, Offset(r.left + r.width * 0.15f, r.top + r.height * 0.8f), Offset(r.right - r.width * 0.15f, r.top + r.height * 0.8f), stroke * 1.5f, StrokeCap.Round)
        text(ac.status, value(minOf(r.height * 0.34f, r.width * 0.16f)).copy(color = ac.color), r.center.x, r.top + r.height * 0.42f, ax = 0.5f, ay = 0.5f, maxW = r.width - u)
        above(part.prop("name") ?: entity?.attributes?.optString("friendly_name")?.ifBlank { null }, r)
        // The room and its target, under it.
        val current = entity?.attributes?.optDouble("current_temperature")?.takeUnless { it.isNaN() } ?: of(part.prop("room"))?.state?.toDoubleOrNull()
        val target = entity?.attributes?.optDouble("temperature")?.takeUnless { it.isNaN() }
        when {
            current != null && target != null -> "${deg(current)} → ${deg(target)}"
            current != null -> "Room ${deg(current)}"
            target != null -> "Set ${deg(target)}"
            else -> null
        }?.let { below(it, r, small) }
        // Air in, air out and its ΔT (how much it heats or cools the room's air), beside it.
        val readingH = measurer.measure("Ag", reading).size.height
        val rows = buildList {
            if (part.prop("in") != null) add("In ${deg(of(part.prop("in")))}" to reading)
            if (part.prop("out") != null) add("Out ${deg(of(part.prop("out")))}" to reading.copy(color = if (ac.running) ac.color else theme.text))
            delta(of(part.prop("in")), of(part.prop("out")))?.let { add("Δ $it" to reading.copy(color = color)) }
        }
        val x = r.right + u * 0.6f
        rows.forEachIndexed { i, (s, style) ->
            text(s, style, x, r.center.y + (i - (rows.size - 1) / 2f) * readingH, ay = 0.5f, maxW = (size.width - x).coerceAtLeast(1f))
        }
    }

    /**
     * A reading: [label] and the entity's value, or with "from" the difference between the two
     * ("Δ 31.2°"). With "point" ("x,y") a leader line runs to a probe dot there: where it's measured.
     */
    private fun sensor(part: Diagram.Part, r: Rect, of: (String?) -> HomeAssistant.Entity?) = with(scope) {
        val entity = of(part.prop("entity"))
        val n = entity?.state?.toDoubleOrNull()
        val unit = part.prop("unit") ?: entity?.unit.orEmpty()
        val shown = when {
            part.prop("from") != null -> delta(entity, of(part.prop("from")))?.let { "Δ $it" } ?: "–"
            entity == null || entity.state in setOf("unavailable", "unknown") -> "–"
            n != null && (unit.isEmpty() || unit.startsWith("°")) -> deg(n)
            n != null -> "${"%.1f".format(n).removeSuffix(".0")}${if (unit == "%") "%" else " $unit"}"
            else -> entity.state.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        part.prop("point")?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size == 2 }?.let { (px, py) ->
            val p = at(px, py)
            // From the label's nearest edge, so the line doesn't cross its text.
            val from = Offset(p.x.coerceIn(r.left, r.right), if (p.y < r.top) r.top else if (p.y > r.bottom) r.bottom else r.center.y)
            drawLine(theme.muted, from, p, stroke)
            drawCircle(theme.card, u * 0.45f, p)
            drawCircle(theme.text, u * 0.45f, p, style = Stroke(stroke))
        }
        val size = r.height * 0.7f
        val label = part.prop("label")
        val labelStyle = small.copy(fontSize = px(size * 0.8f))
        var x = r.left
        if (label != null) {
            text(label, labelStyle, x, r.center.y, ay = 0.5f, maxW = r.width)
            x += measurer.measure("$label ", labelStyle).size.width
        }
        text(shown, reading.copy(fontSize = px(size)), x, r.center.y, ay = 0.5f, maxW = (r.right - x).coerceAtLeast(u * 4))
    }
}

private class AcState(val status: String, val color: Color, val running: Boolean)

/** An AC: a climate entity (its hvac_action, else its mode), or any entity whose state says heating, cooling, idle or off. */
private fun acState(entity: HomeAssistant.Entity?, muted: Color): AcState {
    val action = (entity?.attributes?.optString("hvac_action")?.ifBlank { null } ?: entity?.state ?: "unavailable").lowercase()
    return when (action) {
        "heating", "heat" -> AcState("Heating", HEAT, true)
        "cooling", "cool" -> AcState("Cooling", COOL, true)
        "drying", "dry" -> AcState("Drying", COOL, true)
        "fan", "fan_only" -> AcState("Fan", muted, true)
        "idle" -> AcState("Idle", muted, false)
        "off", "0", "false" -> AcState("Off", muted, false)
        "unavailable", "unknown" -> AcState("–", muted, false)
        else -> AcState(action.replace('_', ' ').replaceFirstChar { it.uppercase() }, muted, false)
    }
}

private fun flame(c: Offset, s: Float) = Path().apply {
    moveTo(c.x, c.y - s / 2)
    cubicTo(c.x + s * 0.12f, c.y - s * 0.2f, c.x + s * 0.42f, c.y - s * 0.05f, c.x + s * 0.36f, c.y + s * 0.2f)
    cubicTo(c.x + s * 0.3f, c.y + s * 0.45f, c.x + s * 0.12f, c.y + s / 2, c.x, c.y + s / 2)
    cubicTo(c.x - s * 0.12f, c.y + s / 2, c.x - s * 0.3f, c.y + s * 0.45f, c.x - s * 0.36f, c.y + s * 0.2f)
    cubicTo(c.x - s * 0.42f, c.y - s * 0.05f, c.x - s * 0.12f, c.y - s * 0.2f, c.x, c.y - s / 2)
    close()
}

private val RUNNING = setOf("on", "open", "opening", "heat", "heating", "cool", "cooling", "drying", "fan_only", "running", "true", "active")

// Running: on, heating and the like (a climate entity by its hvac_action), or a number above zero
// (a burner's modulation, a pump's speed).
private fun running(entity: HomeAssistant.Entity?): Boolean {
    entity ?: return false
    entity.attributes.optString("hvac_action").ifBlank { null }?.let { return it.lowercase() in RUNNING }
    val state = entity.state.lowercase()
    state.toDoubleOrNull()?.let { return it > 0 }
    return state in RUNNING
}

private fun deg(value: Double) = if (value % 1.0 == 0.0) "%.0f°".format(value) else "%.1f°".format(value)

private fun deg(entity: HomeAssistant.Entity?) = entity?.state?.toDoubleOrNull()?.let(::deg) ?: "–"

private fun percent(entity: HomeAssistant.Entity?) = entity?.state?.toDoubleOrNull()?.let { "%.0f%%".format(it) } ?: "–"

/** The difference between two temperatures, "9.0°", or null without both. */
private fun delta(a: HomeAssistant.Entity?, b: HomeAssistant.Entity?): String? {
    val x = a?.state?.toDoubleOrNull() ?: return null
    val y = b?.state?.toDoubleOrNull() ?: return null
    return "%.1f°".format(abs(x - y))
}
