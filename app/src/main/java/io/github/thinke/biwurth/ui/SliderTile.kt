package io.github.thinke.biwurth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thinke.biwurth.DataHub
import io.github.thinke.biwurth.config.Tile
import io.github.thinke.biwurth.config.TileTypes
import io.github.thinke.biwurth.ha.HomeAssistant
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Drag to set a level: a light's brightness, a cover's position, a fan's speed, a media player's
 * volume or a number's value. The new level shows while dragging and goes to Home Assistant when
 * the finger lifts; a tap toggles a light or fan, or sets the level where tapped for the others.
 * The theme's sliderStyle draws a track under the name ("bar") or fills the tile ("fill").
 */
@Composable
internal fun BoxWithConstraintsScope.SliderTile(tile: Tile, ha: DataHub) {
    val theme = LocalPanelTheme.current
    val style = LocalTileStyle.current
    val maxWidth = maxWidth
    val maxHeight = maxHeight
    val id = tile.prop("entity") ?: return Title(pickHint(tile, "Pick a light or other level"), maxHeight)
    val entity = ha.entity(id)
    val level = Level.of(id, entity)
    var dragging by remember(id) { mutableStateOf<Float?>(null) }
    var sent by remember(id) { mutableStateOf<Float?>(null) }
    // Keep showing what was sent until Home Assistant reports it, or a few seconds pass.
    LaunchedEffect(sent) {
        if (sent != null) {
            delay(4_000)
            sent = null
        }
    }
    LaunchedEffect(level.fraction) { if (sent != null && kotlin.math.abs(level.fraction - sent!!) < 0.02f) sent = null }
    val shown = dragging ?: sent ?: level.fraction
    val current by rememberUpdatedState(level)

    fun send(fraction: Float) {
        sent = fraction
        current.send(ha, id, fraction)
    }

    val name = tile.prop("name") ?: entity?.name ?: id
    val label = current.label(shown, dragging != null || sent != null)
    val on = shown > 0f && level.available
    val icon = iconFor(tile, entity)?.takeIf { style.icons != "none" }
    val gestures = Modifier
        .pointerInput(id) {
            detectHorizontalDragGestures(
                onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                onHorizontalDrag = { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) },
                onDragEnd = { dragging?.let(::send); dragging = null },
                onDragCancel = { dragging = null },
            )
        }
        .pointerInput(id) {
            detectTapGestures { offset ->
                if (current.toggles) ha.press(id) else send((offset.x / size.width).coerceIn(0f, 1f))
            }
        }

    if (style.sliderStyle == "fill") {
        // The whole tile is the slider (Bubble Card): filled to the level, name and value on top.
        Box(Modifier.fillMaxSize().then(gestures)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown.coerceIn(0f, 1f)).clip(RoundedCornerShape(50)).background(theme.accent.copy(alpha = if (on) 0.85f else 0.25f)))
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    TileIconSmall(icon, if (on) theme.onAccent else theme.muted, (maxHeight * 0.6f).coerceIn(24.dp, 56.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(name, color = theme.text, fontSize = label((maxHeight * 0.24f).coerceIn(MIN_TEXT, 24.dp), fits = true), fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(label, color = theme.text, fontSize = label((maxHeight * 0.24f).coerceIn(MIN_TEXT, 24.dp)), fontWeight = valueWeight(), maxLines = 1)
            }
        }
        return
    }

    // A track under the name, the level filled in the accent, a knob at its end.
    val track = (maxHeight * 0.14f).coerceIn(6.dp, 16.dp)
    Column(Modifier.fillMaxSize().then(gestures)) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                TileIconSmall(icon, if (on) theme.accent else theme.muted, (maxHeight * 0.4f).coerceIn(20.dp, 44.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(name, color = theme.text, fontSize = label((maxHeight * 0.2f).coerceIn(MIN_TEXT, 22.dp), fits = true), fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(label, color = if (on) theme.text else theme.muted, fontSize = label((maxHeight * 0.2f).coerceIn(MIN_TEXT, 22.dp)), fontWeight = valueWeight(), maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(track * 2), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(track).clip(RoundedCornerShape(50)).background(theme.muted.copy(alpha = 0.22f)))
            Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).height(track).clip(RoundedCornerShape(50)).background(if (on) theme.accent else theme.muted.copy(alpha = 0.5f)))
            // White with a rim in the level's colour: visible on light cards as well as dark ones.
            Box(
                Modifier.offset(x = (maxWidth - track * 2) * shown.coerceIn(0f, 1f)).size(track * 2).clip(CircleShape)
                    .background(Color.White).border(2.dp, if (on) theme.accent else theme.muted, CircleShape),
            )
        }
    }
}

// A built-in icon without a circle, tinted: in sliders the level itself is the colour.
@Composable
private fun TileIconSmall(name: String, tint: Color, size: androidx.compose.ui.unit.Dp) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pixels = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bitmap = remember(name, pixels) { PictureCache.icon(context, name, pixels) } ?: return
    androidx.compose.foundation.Image(
        bitmap.asImageBitmap(), contentDescription = null,
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint), modifier = Modifier.size(size),
    )
}

/** What a slider controls, read from an entity and written back with the right action. */
private class Level(
    val fraction: Float,
    val available: Boolean,
    val toggles: Boolean,
    private val describe: (Float) -> String,
    private val write: (DataHub, String, Float) -> Unit,
) {
    fun label(fraction: Float, moving: Boolean) = if (!available && !moving) "–" else describe(fraction)
    fun send(ha: DataHub, id: String, fraction: Float) = write(ha, id, fraction)

    companion object {
        private fun percent(f: Float) = "${(f * 100).roundToInt()} %"

        fun of(id: String, entity: HomeAssistant.Entity?): Level {
            val a = entity?.attributes ?: JSONObject()
            val available = entity != null && entity.state != "unavailable" && entity.state != "unknown"
            return when (TileTypes.domainOf(id)) {
                "light" -> Level(
                    fraction = if (entity?.state == "on") (a.optDouble("brightness", 255.0).toFloat() / 255f).coerceIn(0.01f, 1f) else 0f,
                    available = available, toggles = true,
                    describe = { if (it <= 0f) "Off" else percent(it) },
                    write = { ha, id, f ->
                        if (f < 0.01f) ha.call(id, "light", "turn_off")
                        else ha.call(id, "light", "turn_on", JSONObject().put("brightness_pct", (f * 100).roundToInt().coerceIn(1, 100)))
                    },
                )
                "fan" -> Level(
                    fraction = if (entity?.state == "on") (a.optDouble("percentage", 100.0).toFloat() / 100f) else 0f,
                    available = available, toggles = true,
                    describe = { if (it <= 0f) "Off" else percent(it) },
                    write = { ha, id, f -> ha.call(id, "fan", "set_percentage", JSONObject().put("percentage", (f * 100).roundToInt())) },
                )
                "cover" -> Level(
                    fraction = a.optDouble("current_position", if (entity?.state == "open") 100.0 else 0.0).toFloat() / 100f,
                    available = available, toggles = false,
                    describe = { when { it <= 0f -> "Closed"; it >= 1f -> "Open"; else -> percent(it) } },
                    write = { ha, id, f -> ha.call(id, "cover", "set_cover_position", JSONObject().put("position", (f * 100).roundToInt())) },
                )
                "media_player" -> Level(
                    fraction = a.optDouble("volume_level", 0.0).toFloat(),
                    available = available && entity?.state != "off", toggles = false,
                    describe = ::percent,
                    write = { ha, id, f -> ha.call(id, "media_player", "volume_set", JSONObject().put("volume_level", f.toDouble())) },
                )
                else -> {
                    // number and input_number: min, max and step from the entity.
                    val min = a.optDouble("min", 0.0)
                    val max = a.optDouble("max", 100.0).takeIf { it > min } ?: (min + 100.0)
                    val step = a.optDouble("step", 1.0).takeIf { it > 0 } ?: 1.0
                    val unit = a.optString("unit_of_measurement")
                    fun value(f: Float) = ((min + f * (max - min)) / step).roundToInt() * step
                    fun text(v: Double) = (if (step % 1.0 == 0.0) "%.0f" else "%.1f").format(v) + if (unit.isEmpty()) "" else " $unit"
                    val v = entity?.state?.toDoubleOrNull()
                    Level(
                        fraction = v?.let { ((it - min) / (max - min)).toFloat().coerceIn(0f, 1f) } ?: 0f,
                        available = available && v != null, toggles = false,
                        describe = { text(value(it)) },
                        write = { ha, id, f -> ha.call(id, TileTypes.domainOf(id), "set_value", JSONObject().put("value", value(f))) },
                    )
                }
            }
        }
    }
}
