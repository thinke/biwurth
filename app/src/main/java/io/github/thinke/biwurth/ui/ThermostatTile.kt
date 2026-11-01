package io.github.thinke.biwurth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thinke.biwurth.DataHub
import io.github.thinke.biwurth.config.Tile
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Heating and cooling: warm orange and cool blue, as on every thermostat.
private val HEAT = Color(0xFFFF8A3D)
private val COOL = Color(0xFF3B9CF5)

/**
 * A climate entity: the target temperature (− and + in the entity's own step, sent once the taps
 * stop), the room's current one, whether it's heating or cooling, and its modes as chips when
 * there's room. The theme's thermostatStyle draws it as a dial (an arc from minimum to maximum,
 * the current temperature marked on it) or as numbers.
 */
@Composable
internal fun BoxWithConstraintsScope.ThermostatTile(tile: Tile, ha: DataHub) {
    val theme = LocalPanelTheme.current
    val style = LocalTileStyle.current
    val maxWidth = maxWidth
    val maxHeight = maxHeight
    val id = tile.prop("entity") ?: return Title(pickHint(tile, "Pick a thermostat"), maxHeight)
    val entity = ha.entity(id)
    val attributes = entity?.attributes ?: JSONObject()
    fun number(key: String) = attributes.optDouble(key).takeUnless { it.isNaN() }
    val current = number("current_temperature")
    val target = number("temperature")
    val min = number("min_temp") ?: 7.0
    val max = number("max_temp") ?: 35.0
    val step = number("target_temp_step") ?: 0.5
    val mode = entity?.state ?: "unavailable"
    val action = attributes.optString("hvac_action")
    val modes = attributes.optJSONArray("hvac_modes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()

    // A change shows at once and goes to Home Assistant when the taps stop; it stays shown until
    // Home Assistant reports it (or gives up after a few seconds).
    var pending by remember(id) { mutableStateOf<Double?>(null) }
    LaunchedEffect(pending) {
        val value = pending ?: return@LaunchedEffect
        delay(800)
        ha.call(id, "climate", "set_temperature", JSONObject().put("temperature", value))
        delay(5_000)
        pending = null
    }
    LaunchedEffect(target) { if (pending != null && target != null && kotlin.math.abs(target - pending!!) < step / 2) pending = null }
    val shown = pending ?: target
    // Read when a button is tapped, not when it was drawn: Compose may keep a button's first click
    // handler, and a value captured then would make every tap step from the same old target.
    val latestTarget by rememberUpdatedState(target)
    // One step of the entity's own target_temp_step up (+1) or down (-1), kept within its range.
    fun change(direction: Double) {
        val base = pending ?: latestTarget ?: return
        pending = (((base + direction * step) / step).roundToInt() * step).coerceIn(min, max)
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

    Column(Modifier.fillMaxSize()) {
        Title(tile.prop("name") ?: entity?.name ?: id, maxHeight)
        val chipsFit = modes.size > 1 && maxHeight > 150.dp
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // A dial needs room for its arc, number and buttons; smaller tiles show numbers.
            if (style.thermostatStyle == "numbers" || minOf(maxWidth, maxHeight - 40.dp) < 130.dp) {
                Numbers(shown, status, color, maxWidth, maxHeight, enabled = shown != null && mode != "off", onChange = ::change)
            } else {
                Dial(shown, current, min, max, status, color, enabled = shown != null && mode != "off", onChange = ::change)
            }
        }
        if (chipsFit) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                modes.take(5).forEach { m ->
                    val selected = m == mode
                    Text(
                        modeLabel(m),
                        color = if (selected) theme.onAccent else theme.text,
                        fontSize = detail(13.dp), maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) theme.accent else theme.muted.copy(alpha = 0.18f))
                            .clickable { if (!selected) ha.call(id, "climate", "set_hvac_mode", JSONObject().put("hvac_mode", m)) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Dial(
    target: Double?,
    current: Double?,
    min: Double,
    max: Double,
    status: String,
    color: Color,
    enabled: Boolean,
    onChange: (Double) -> Unit,
) {
    val theme = LocalPanelTheme.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    val side = minOf(maxWidth, maxHeight)
    val stroke = (side * 0.07f).coerceIn(4.dp, 14.dp)
    fun fraction(t: Double) = ((t - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    Box(Modifier.size(side), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(stroke / 2)) {
            val width = stroke.toPx()
            val arc = Size(size.minDimension, size.minDimension)
            val topLeft = Offset((size.width - arc.width) / 2, (size.height - arc.height) / 2)
            // 270° from bottom-left round to bottom-right; the target fills it, the room is a dot.
            drawArc(theme.muted.copy(alpha = 0.22f), 135f, 270f, false, topLeft, arc, style = Stroke(width, cap = StrokeCap.Round))
            if (target != null) drawArc(color, 135f, 270f * fraction(target), false, topLeft, arc, style = Stroke(width, cap = StrokeCap.Round))
            if (current != null) {
                val angle = Math.toRadians(135.0 + 270.0 * fraction(current))
                val r = arc.width / 2
                val c = Offset(topLeft.x + r + (r * cos(angle)).toFloat(), topLeft.y + r + (r * sin(angle)).toFloat())
                drawCircle(theme.text, radius = width * 0.45f, center = c)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(target?.let(::format) ?: "–", color = theme.text, fontSize = main(side * 0.22f), fontWeight = valueWeight(), maxLines = 1)
            if (status.isNotEmpty()) Text(status, color = theme.muted, fontSize = detail((side * 0.07f).coerceIn(MIN_TEXT * 0.85f, 18.dp)), maxLines = 1)
            if (enabled) {
                Spacer(Modifier.height(side * 0.04f))
                Row(horizontalArrangement = Arrangement.spacedBy(side * 0.12f)) {
                    StepButton("−", side * 0.18f) { onChange(-1.0) }
                    StepButton("+", side * 0.18f) { onChange(1.0) }
                }
            }
        }
    }
    }
}

@Composable
private fun Numbers(target: Double?, status: String, color: Color, maxWidth: Dp, maxHeight: Dp, enabled: Boolean, onChange: (Double) -> Unit) {
    val theme = LocalPanelTheme.current
    val button = minOf(maxHeight * 0.32f, maxWidth * 0.2f).coerceIn(32.dp, 72.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        if (enabled) StepButton("−", button) { onChange(-1.0) } else Spacer(Modifier.size(button))
        // The number gets the width between the buttons, so they always fit beside it.
        val between = (maxWidth - button * 2 - 8.dp).coerceAtLeast(40.dp)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(target?.let(::format) ?: "–", color = theme.text, fontSize = main(minOf(maxHeight * 0.4f, between * 0.42f)), fontWeight = valueWeight(), maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(
                    " $status", color = theme.muted, fontSize = detail((maxHeight * 0.09f).coerceIn(MIN_TEXT * 0.85f, 18.dp)),
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        if (enabled) StepButton("+", button) { onChange(1.0) } else Spacer(Modifier.size(button))
    }
}

// A round − or + button: each tap is one step (the direction goes to onChange).
@Composable
private fun StepButton(symbol: String, size: Dp, onClick: () -> Unit) {
    val theme = LocalPanelTheme.current
    Box(
        Modifier.size(size).clip(CircleShape).background(theme.muted.copy(alpha = 0.18f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = theme.text, fontSize = label(size * 0.5f, fits = true), fontWeight = FontWeight.Light)
    }
}

private fun format(value: Double) = if (value % 1.0 == 0.0) "%.0f°".format(value) else "%.1f°".format(value)

private fun modeLabel(mode: String) = when (mode) {
    "heat_cool" -> "Auto"
    "fan_only" -> "Fan"
    else -> mode.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
