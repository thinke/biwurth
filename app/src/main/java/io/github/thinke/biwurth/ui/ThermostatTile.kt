package io.github.thinke.biwurth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.thinke.biwurth.DataHub
import io.github.thinke.biwurth.config.Tile
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Heating and cooling: warm orange and cool blue, as on every thermostat.
private val HEAT = Color(0xFFFF8A3D)
private val COOL = Color(0xFF3B9CF5)

/** The thermostat looks: the theme's thermostatStyle, or a tile's own "style". */
val THERMOSTAT_STYLES = listOf("dial", "numbers", "ring", "bar", "pill", "thermometer")

/**
 * Everything a thermostat look draws, and what its controls do. [dual] is a heat_cool mode with
 * a low and a high target ([low], [high]); − and + then change the [selected] one ("low" or
 * "high"), and tapping a number selects it.
 */
private class Thermo(
    val name: String,
    val target: Double?,
    val low: Double?,
    val high: Double?,
    val dual: Boolean,
    val selected: String,
    val current: Double?,
    val min: Double,
    val max: Double,
    val status: String,
    val color: Color,
    val heating: Boolean,
    val enabled: Boolean,
    val change: (Double) -> Unit,
    val select: (String) -> Unit,
    val setTo: (Double) -> Unit,
) {
    fun fraction(t: Double) = ((t - min) / (max - min)).toFloat().coerceIn(0f, 1f)

    /** How many characters the target takes ("21.5°", "20.5°–24°"), to fit its size to a width. */
    val chars: Int get() = if (dual) (low?.let(::format)?.length ?: 1) + 1 + (high?.let(::format)?.length ?: 1) else target?.let(::format)?.length ?: 1
}

// The largest text that fits [width] for [t]'s target, at most [cap].
private fun fit(t: Thermo, width: Dp, cap: Dp): Dp = minOf(cap, width / (t.chars.coerceAtLeast(3) * 0.58f))

/**
 * A climate entity: its target (− and + in the entity's own step, sent once the taps stop) or,
 * in heat_cool, its low and high targets; the room's temperature; whether it's heating or
 * cooling; its modes as chips when there's room. Six looks (THERMOSTAT_STYLES); a look that
 * doesn't fit the tile gives way to one that does.
 */
@Composable
internal fun BoxWithConstraintsScope.ThermostatTile(tile: Tile, ha: DataHub) {
    val theme = LocalPanelTheme.current
    val maxWidth = maxWidth
    val maxHeight = maxHeight
    val id = tile.prop("entity") ?: return Title(pickHint(tile, "Pick a thermostat"), maxHeight)
    val entity = ha.entity(id)
    val attributes = entity?.attributes ?: JSONObject()
    fun number(key: String) = attributes.optDouble(key).takeUnless { it.isNaN() }
    val current = number("current_temperature")
    val target = number("temperature")
    val low = number("target_temp_low")
    val high = number("target_temp_high")
    val min = number("min_temp") ?: 7.0
    val max = number("max_temp") ?: 35.0
    val step = number("target_temp_step") ?: 0.5
    val mode = entity?.state ?: "unavailable"
    val action = attributes.optString("hvac_action")
    val modes = attributes.optJSONArray("hvac_modes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
    val dual = target == null && low != null && high != null

    // Changes show at once and go to Home Assistant when the taps (or a drag) stop; they stay shown
    // until Home Assistant reports them, or a few seconds pass.
    var pending by remember(id) { mutableStateOf<Double?>(null) }
    var pendingLow by remember(id) { mutableStateOf<Double?>(null) }
    var pendingHigh by remember(id) { mutableStateOf<Double?>(null) }
    var selected by remember(id) { mutableStateOf("low") }
    LaunchedEffect(pending, pendingLow, pendingHigh) {
        if (pending == null && pendingLow == null && pendingHigh == null) return@LaunchedEffect
        delay(800)
        val data = if (pending != null) {
            JSONObject().put("temperature", pending)
        } else {
            JSONObject().put("target_temp_low", pendingLow ?: low).put("target_temp_high", pendingHigh ?: high)
        }
        ha.call(id, "climate", "set_temperature", data)
        delay(5_000)
        pending = null
        pendingLow = null
        pendingHigh = null
    }
    LaunchedEffect(target, low, high) {
        fun same(a: Double?, b: Double?) = a != null && b != null && abs(a - b) < step / 2
        if (same(target, pending)) pending = null
        if (same(low, pendingLow)) pendingLow = null
        if (same(high, pendingHigh)) pendingHigh = null
    }
    // Read when a control is used, not when it was drawn: Compose may keep a button's first click
    // handler, and values captured then would make every tap step from the same old target.
    val latest by rememberUpdatedState(Triple(target, low, high))
    fun snap(v: Double) = ((v / step).roundToInt() * step).coerceIn(min, max)
    fun setTo(value: Double, which: String = selected) {
        if (!dual) {
            pending = snap(value)
            return
        }
        val l = pendingLow ?: latest.second ?: return
        val h = pendingHigh ?: latest.third ?: return
        // The two targets keep at least one step between them.
        if (which == "low") pendingLow = snap(value).coerceAtMost(h - step) else pendingHigh = snap(value).coerceAtLeast(l + step)
    }
    fun change(direction: Double) {
        if (!dual) {
            val base = pending ?: latest.first ?: return
            setTo(base + direction * step)
        } else {
            val base = (if (selected == "low") pendingLow ?: latest.second else pendingHigh ?: latest.third) ?: return
            setTo(base + direction * step)
        }
    }
    // Dragging a two-target bar moves whichever end is nearer.
    fun dragTo(value: Double) {
        if (dual) {
            val l = pendingLow ?: latest.second ?: return
            val h = pendingHigh ?: latest.third ?: return
            val which = if (abs(value - l) <= abs(value - h)) "low" else "high"
            selected = which
            setTo(value, which)
        } else {
            setTo(value)
        }
    }

    val color = when {
        action == "heating" -> HEAT
        action == "cooling" -> COOL
        mode == "heat" -> HEAT.copy(alpha = 0.55f)
        mode == "cool" -> COOL.copy(alpha = 0.55f)
        mode == "off" || entity == null -> theme.muted
        else -> theme.accent
    }
    val status = listOfNotNull(
        current?.let { "Now ${format(it)}" },
        when (action) {
            "heating" -> "Heating"
            "cooling" -> "Cooling"
            "idle" -> "Idle"
            "off" -> "Off"
            else -> if (mode == "off") "Off" else null
        },
    ).joinToString(" · ")
    val t = Thermo(
        name = tile.prop("name") ?: entity?.name ?: id,
        target = pending ?: target, low = pendingLow ?: low, high = pendingHigh ?: high, dual = dual, selected = selected,
        current = current, min = min, max = max, status = status, color = color, heating = action == "heating" || mode == "heat",
        enabled = mode != "off" && (target != null || dual),
        change = ::change, select = { selected = it }, setTo = ::dragTo,
    )

    // The look: the tile's own, else the theme's; then one that fits the tile.
    val wanted = tile.prop("style")?.takeIf { it in THERMOSTAT_STYLES } ?: LocalTileStyle.current.thermostatStyle
    val roomy = minOf(maxWidth, maxHeight - 40.dp)
    val look = when {
        maxHeight < 90.dp -> "pill"
        wanted in setOf("dial", "ring") && roomy < 130.dp -> "numbers"
        wanted == "thermometer" && (maxHeight < 150.dp || maxWidth < 140.dp) -> "numbers"
        wanted == "bar" && maxWidth < 170.dp -> "numbers"
        else -> wanted
    }
    if (look == "pill") return Pill(t)

    Column(Modifier.fillMaxSize()) {
        Title(t.name, maxHeight)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (look) {
                "numbers" -> Numbers(t)
                "ring" -> Ring(t)
                "bar" -> Bar(t)
                "thermometer" -> Thermometer(t)
                else -> Dial(t)
            }
        }
        if (modes.size > 1 && maxHeight > 150.dp) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                modes.take(5).forEach { m ->
                    val isOn = m == mode
                    Text(
                        modeLabel(m),
                        color = if (isOn) theme.onAccent else theme.text,
                        fontSize = detail(13.dp), maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (isOn) theme.accent else theme.muted.copy(alpha = 0.18f))
                            .clickable { if (!isOn) ha.call(id, "climate", "set_hvac_mode", JSONObject().put("hvac_mode", m)) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** The target, or the low–high range with the one − and + change underlined; tap a number to pick it. */
@Composable
private fun Target(t: Thermo, size: TextUnit, color: Color = LocalPanelTheme.current.text) {
    if (!t.dual) {
        Text(t.target?.let(::format) ?: "–", color = color, fontSize = size, fontWeight = valueWeight(), maxLines = 1)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf("low" to t.low, "high" to t.high).forEachIndexed { i, (which, value) ->
            if (i == 1) Text("–", color = color.copy(alpha = 0.6f), fontSize = size, fontWeight = FontWeight.Light)
            val chosen = t.selected == which
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { t.select(which) }) {
                Text(value?.let(::format) ?: "–", color = if (chosen) color else color.copy(alpha = 0.6f), fontSize = size, fontWeight = valueWeight(), maxLines = 1)
                Box(Modifier.width(24.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (chosen) (if (which == "low") COOL else HEAT) else Color.Transparent))
            }
        }
    }
}

// ---- dial: an open arc from minimum to maximum ---------------------------------------------

@Composable
private fun Dial(t: Thermo) {
    val theme = LocalPanelTheme.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val maxWidth = maxWidth
        val maxHeight = maxHeight
        val side = minOf(maxWidth, maxHeight)
        val stroke = (side * 0.07f).coerceIn(4.dp, 14.dp)
        Box(Modifier.size(side).arcInput(t), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(stroke / 2)) {
                val width = stroke.toPx()
                val arc = Size(size.minDimension, size.minDimension)
                val topLeft = Offset((size.width - arc.width) / 2, (size.height - arc.height) / 2)
                val r = arc.width / 2
                fun at(f: Float): Offset {
                    val a = Math.toRadians(135.0 + 270.0 * f)
                    return Offset(topLeft.x + r + (r * cos(a)).toFloat(), topLeft.y + r + (r * sin(a)).toFloat())
                }
                drawArc(theme.muted.copy(alpha = 0.22f), 135f, 270f, false, topLeft, arc, style = Stroke(width, cap = StrokeCap.Round))
                if (t.dual && t.low != null && t.high != null) {
                    // The comfortable range between the two targets, cool end to warm end.
                    val a = t.fraction(t.low)
                    val b = t.fraction(t.high)
                    drawArc(t.color, 135f + 270f * a, 270f * (b - a), false, topLeft, arc, style = Stroke(width, cap = StrokeCap.Round))
                    drawCircle(COOL, width * 0.9f, at(a))
                    drawCircle(HEAT, width * 0.9f, at(b))
                } else if (t.target != null) {
                    drawArc(t.color, 135f, 270f * t.fraction(t.target), false, topLeft, arc, style = Stroke(width, cap = StrokeCap.Round))
                }
                t.current?.let { drawCircle(theme.text, width * 0.45f, at(t.fraction(it))) }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Target(t, main(fit(t, side * 0.62f, side * 0.22f)))
                if (t.status.isNotEmpty()) Text(t.status, color = theme.muted, fontSize = detail((side * 0.07f).coerceIn(MIN_TEXT * 0.85f, 18.dp)), maxLines = 1)
                if (t.enabled) {
                    Spacer(Modifier.height(side * 0.04f))
                    Steps(t, side * 0.18f, side * 0.12f)
                }
            }
        }
    }
}

// ---- numbers: a big target between − and + ----------------------------------------------------

@Composable
private fun Numbers(t: Thermo) {
    val theme = LocalPanelTheme.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val maxWidth = maxWidth
        val maxHeight = maxHeight
        val button = minOf(maxHeight * 0.32f, maxWidth * 0.2f).coerceIn(32.dp, 72.dp)
        // The number gets the width between the buttons, so they always fit beside it.
        val between = (maxWidth - button * 2 - 8.dp).coerceAtLeast(40.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (t.enabled) StepButton("−", button) { t.change(-1.0) } else Spacer(Modifier.size(button))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Target(t, main(fit(t, between, maxHeight * 0.4f)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(t.color))
                    Text(" ${t.status}", color = theme.muted, fontSize = detail((maxHeight * 0.09f).coerceIn(MIN_TEXT * 0.85f, 18.dp)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (t.enabled) StepButton("+", button) { t.change(1.0) } else Spacer(Modifier.size(button))
        }
    }
}

// ---- ring: a disc that glows while heating or cooling, ticks lit from the room to the target --

@Composable
private fun Ring(t: Thermo) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val maxWidth = maxWidth
        val maxHeight = maxHeight
        val side = minOf(maxWidth, maxHeight)
        val active = t.status.contains("Heating") || t.status.contains("Cooling")
        val disc = if (active) t.color else Color(0xFF26282C)
        Box(Modifier.size(side).arcInput(t), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                val c = Offset(size.width / 2, size.height / 2)
                drawCircle(Brush.radialGradient(listOf(disc, disc.copy(alpha = 0.75f).compositeOver(Color.Black)), c, r), r, c)
                // 90 ticks over 270°; those between the room and the target (or the two targets) lit.
                val lit = when {
                    t.dual && t.low != null && t.high != null -> t.fraction(t.low)..t.fraction(t.high)
                    t.target != null && t.current != null -> minOf(t.fraction(t.target), t.fraction(t.current))..maxOf(t.fraction(t.target), t.fraction(t.current))
                    else -> 2f..2f
                }
                for (i in 0..90) {
                    val f = i / 90f
                    val a = Math.toRadians(135.0 + 270.0 * f)
                    val on = f in lit
                    val outer = r * 0.94f
                    val inner = r * (if (on) 0.80f else 0.85f)
                    drawLine(
                        Color.White.copy(alpha = if (on) 0.95f else 0.3f),
                        Offset(c.x + (inner * cos(a)).toFloat(), c.y + (inner * sin(a)).toFloat()),
                        Offset(c.x + (outer * cos(a)).toFloat(), c.y + (outer * sin(a)).toFloat()),
                        strokeWidth = if (on) 2.5f * density else 1.5f * density,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val word = t.status.substringAfterLast(" · ", "").uppercase()
                if (word.isNotEmpty()) Text(word, color = Color.White.copy(alpha = 0.8f), fontSize = detail((side * 0.06f).coerceIn(MIN_TEXT * 0.8f, 16.dp)), fontWeight = FontWeight.SemiBold, maxLines = 1)
                Target(t, main(fit(t, side * 0.6f, side * 0.24f)), Color.White)
                t.current?.let { Text("Now ${format(it)}", color = Color.White.copy(alpha = 0.75f), fontSize = detail((side * 0.06f).coerceIn(MIN_TEXT * 0.8f, 16.dp)), maxLines = 1) }
                if (t.enabled) {
                    Spacer(Modifier.height(side * 0.03f))
                    Steps(t, side * 0.15f, side * 0.1f, onDisc = true)
                }
            }
        }
    }
}

// ---- bar: a cold-to-warm track; drag the target, the room marked ---------------------------

@Composable
private fun Bar(t: Thermo) {
    val theme = LocalPanelTheme.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxWidth = maxWidth
        val maxHeight = maxHeight
        val button = (maxHeight * 0.26f).coerceIn(32.dp, 56.dp)
        val track = (maxHeight * 0.1f).coerceIn(8.dp, 18.dp)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            Row(verticalAlignment = Alignment.Bottom) {
                Target(t, main(fit(t, maxWidth * 0.55f, maxHeight * 0.32f)))
                Spacer(Modifier.width(10.dp))
                Text(t.status, color = theme.muted, fontSize = detail((maxHeight * 0.09f).coerceIn(MIN_TEXT * 0.85f, 18.dp)), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.enabled) StepButton("−", button) { t.change(-1.0) }
                Box(
                    Modifier.weight(1f).height(button).padding(horizontal = 10.dp)
                        .pointerInput(t.enabled) {
                            if (!t.enabled) return@pointerInput
                            fun value(x: Float) = t.min + (x / size.width).coerceIn(0f, 1f) * (t.max - t.min)
                            detectHorizontalDragGestures(onDragStart = { t.setTo(value(it.x)) }) { change, _ -> t.setTo(value(change.position.x)) }
                        }
                        .pointerInput(t.enabled) {
                            if (!t.enabled) return@pointerInput
                            detectTapGestures { t.setTo(t.min + (it.x / size.width).coerceIn(0f, 1f) * (t.max - t.min)) }
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Canvas(Modifier.fillMaxWidth().height(button)) {
                        val y = size.height / 2
                        val h = track.toPx()
                        drawRoundRect(Brush.horizontalGradient(listOf(COOL, Color(0xFF9AA0A6), HEAT)), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2), alpha = 0.85f)
                        fun x(v: Double) = size.width * t.fraction(v)
                        t.current?.let {
                            val cx = x(it)
                            drawLine(theme.text, Offset(cx, y - h * 1.4f), Offset(cx, y + h * 1.4f), strokeWidth = 2.5f * density)
                        }
                        val knobs = if (t.dual) listOfNotNull(t.low?.let { it to COOL }, t.high?.let { it to HEAT }) else listOfNotNull(t.target?.let { it to t.color })
                        knobs.forEach { (v, c) ->
                            drawCircle(Color.White, h * 1.15f, Offset(x(v), y))
                            drawCircle(c, h * 0.75f, Offset(x(v), y))
                        }
                    }
                }
                if (t.enabled) StepButton("+", button) { t.change(1.0) }
            }
        }
    }
}

// ---- pill: one row, as Bubble Card's climate: icon, name and status, − target + ----------------

@Composable
private fun BoxWithConstraintsScope.Pill(t: Thermo) {
    val theme = LocalPanelTheme.current
    val maxHeight = maxHeight
    val maxWidth = maxWidth
    val icon = (maxHeight * 0.62f).coerceIn(28.dp, 56.dp)
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(icon).clip(CircleShape).background(t.color.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val px = with(androidx.compose.ui.platform.LocalDensity.current) { (icon * 0.58f).roundToPx() }
            val bitmap = remember(px) { PictureCache.icon(context, "thermometer", px) }
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap.asImageBitmap(), contentDescription = null,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (t.color == theme.muted) theme.muted else t.color), modifier = Modifier.size(icon * 0.58f),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, color = theme.text, fontSize = label((maxHeight * 0.24f).coerceIn(MIN_TEXT, 22.dp), fits = true), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.status.isNotEmpty()) Text(t.status, color = theme.muted, fontSize = detail((maxHeight * 0.17f).coerceIn(MIN_TEXT * 0.8f, 16.dp)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val button = (maxHeight * 0.55f).coerceIn(28.dp, 48.dp)
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(theme.muted.copy(alpha = 0.15f)).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (t.enabled) StepButton("−", button) { t.change(-1.0) }
            Box(Modifier.padding(horizontal = 6.dp)) { Target(t, main(fit(t, maxWidth * 0.3f, maxHeight * 0.36f))) }
            if (t.enabled) StepButton("+", button) { t.change(1.0) }
        }
    }
}

// ---- thermometer: a glass tube filled to the room's temperature, the target on its scale -------

@Composable
private fun Thermometer(t: Thermo) {
    val theme = LocalPanelTheme.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxWidth = maxWidth
        val maxHeight = maxHeight
        val tube = (maxWidth * 0.16f).coerceIn(18.dp, 44.dp)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.width(tube * 2.2f).fillMaxHeight().padding(vertical = 6.dp)) {
                val w = tube.toPx()
                val cx = size.width / 2
                val bulb = w * 0.95f
                val top = w / 2
                val bottom = size.height - bulb * 1.6f
                fun y(v: Double) = bottom - (bottom - top) * t.fraction(v)
                // The glass, then the liquid up to the room's temperature, then the bulb.
                drawRoundRect(theme.muted.copy(alpha = 0.22f), Offset(cx - w / 2, 0f), Size(w, size.height - bulb), CornerRadius(w / 2))
                drawCircle(theme.muted.copy(alpha = 0.22f), bulb, Offset(cx, size.height - bulb))
                val liquid = t.current?.let(::y) ?: bottom
                val fill = if (t.color == theme.muted) theme.muted else t.color
                drawRoundRect(fill, Offset(cx - w * 0.28f, liquid), Size(w * 0.56f, size.height - bulb - liquid), CornerRadius(w * 0.28f))
                drawCircle(fill, bulb * 0.72f, Offset(cx, size.height - bulb))
                // Scale ticks every 5 degrees, the target(s) as notches beside the tube.
                var v = (kotlin.math.ceil(t.min / 5) * 5)
                while (v <= t.max) {
                    drawLine(theme.muted, Offset(cx + w * 0.6f, y(v)), Offset(cx + w * 0.9f, y(v)), strokeWidth = 1.2f * density)
                    v += 5
                }
                val marks = if (t.dual) listOfNotNull(t.low?.let { it to COOL }, t.high?.let { it to HEAT }) else listOfNotNull(t.target?.let { it to theme.text })
                marks.forEach { (m, c) ->
                    drawLine(c, Offset(cx - w * 1.0f, y(m)), Offset(cx + w * 1.0f, y(m)), strokeWidth = 3f * density, cap = StrokeCap.Round)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Target(t, main(fit(t, maxWidth - tube * 2.2f - 16.dp, maxHeight * 0.26f)))
                if (t.status.isNotEmpty()) Text(t.status, color = theme.muted, fontSize = detail((maxHeight * 0.07f).coerceIn(MIN_TEXT * 0.85f, 18.dp)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.enabled) {
                    Spacer(Modifier.height(8.dp))
                    Steps(t, (maxHeight * 0.16f).coerceIn(32.dp, 60.dp), 16.dp)
                }
            }
        }
    }
}

/**
 * Setting the target on the arc itself (dial and ring): a drag along it, or a tap on it, moves
 * the target there (with two targets, the nearer one). Only touches near the edge count, so the
 * − and + in the middle still work. The arc runs 270° from the bottom left round to the bottom right.
 */
private fun Modifier.arcInput(t: Thermo): Modifier = if (!t.enabled) this else this
    .pointerInput(t.enabled) {
        fun onArc(p: Offset) = (p - Offset(size.width / 2f, size.height / 2f)).getDistance() > minOf(size.width, size.height) * 0.32f
        fun value(p: Offset): Double {
            val d = p - Offset(size.width / 2f, size.height / 2f)
            // Angle from the arc's start (135°, bottom left), clockwise, 0..360.
            var a = Math.toDegrees(kotlin.math.atan2(d.y.toDouble(), d.x.toDouble())) - 135.0
            while (a < 0) a += 360.0
            // The gap at the bottom snaps to the nearer end.
            val f = if (a <= 270.0) a / 270.0 else if (a < 315.0) 1.0 else 0.0
            return t.min + f * (t.max - t.min)
        }
        var dragging = false
        detectDragGestures(
            onDragStart = { p -> dragging = onArc(p); if (dragging) t.setTo(value(p)) },
            onDragEnd = { dragging = false },
            onDragCancel = { dragging = false },
        ) { change, _ -> if (dragging) t.setTo(value(change.position)) }
    }
    .pointerInput(t.enabled) {
        detectTapGestures { p ->
            val d = p - Offset(size.width / 2f, size.height / 2f)
            if (d.getDistance() > minOf(size.width, size.height) * 0.32f) {
                var a = Math.toDegrees(kotlin.math.atan2(d.y.toDouble(), d.x.toDouble())) - 135.0
                while (a < 0) a += 360.0
                if (a <= 270.0) t.setTo(t.min + a / 270.0 * (t.max - t.min))
            }
        }
    }

// − and + side by side.
@Composable
private fun Steps(t: Thermo, size: Dp, gap: Dp, onDisc: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
        StepButton("−", size, onDisc) { t.change(-1.0) }
        StepButton("+", size, onDisc) { t.change(1.0) }
    }
}

// A round − or + button: each tap is one step (the direction goes to change).
@Composable
private fun StepButton(symbol: String, size: Dp, onDisc: Boolean = false, onClick: () -> Unit) {
    val theme = LocalPanelTheme.current
    Box(
        Modifier.size(size).clip(CircleShape).background(if (onDisc) Color.White.copy(alpha = 0.18f) else theme.muted.copy(alpha = 0.18f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = if (onDisc) Color.White else theme.text, fontSize = label(size * 0.5f, fits = true), fontWeight = FontWeight.Light)
    }
}

private fun format(value: Double) = if (value % 1.0 == 0.0) "%.0f°".format(value) else "%.1f°".format(value)

private fun modeLabel(mode: String) = when (mode) {
    "heat_cool" -> "Auto"
    "fan_only" -> "Fan"
    else -> mode.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a), blue * a + background.blue * (1 - a), 1f)
}
