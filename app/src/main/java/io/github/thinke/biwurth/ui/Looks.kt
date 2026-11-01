package io.github.thinke.biwurth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thinke.biwurth.DataHub
import io.github.thinke.biwurth.config.Tile
import io.github.thinke.biwurth.config.TileTypes
import io.github.thinke.biwurth.ha.HomeAssistant
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.minutes

// Better and worse, for a value's trend: green and red, as Chakra's Stat and Ant's Statistic.
private val BETTER = Color(0xFF3FB950)
private val WORSE = Color(0xFFF85149)

/** A value tile's looks besides the plain number: see [ValueLook]. */
val VALUE_STYLES = listOf("number", "trend", "gauge", "progress")

/**
 * A value tile drawn another way than as a plain number: "trend" (the value, its change over the
 * last [hours] with an arrow, and a sparkline: Ant Statistic, Chakra Stat), "gauge" (a dashboard
 * arc between min and max: Ant Progress) or "progress" (a bar between min and max: Bootstrap).
 */
@Composable
internal fun BoxWithConstraintsScope.ValueLook(look: String, tile: Tile, ha: DataHub, entity: HomeAssistant.Entity?) {
    when (look) {
        "trend" -> Trend(tile, ha, entity)
        "gauge" -> Gauge(tile, entity)
        else -> Progress(tile, entity)
    }
}

// min and max for gauges and bars: the tile's own, else 0–100.
private fun range(tile: Tile): Pair<Double, Double> {
    val min = tile.prop("min")?.toDoubleOrNull() ?: 0.0
    val max = tile.prop("max")?.toDoubleOrNull()?.takeIf { it > min } ?: (min + 100.0)
    return min to max
}

@Composable
private fun BoxWithConstraintsScope.Trend(tile: Tile, ha: DataHub, entity: HomeAssistant.Entity?) {
    val theme = LocalPanelTheme.current
    val maxWidth = maxWidth
    val maxHeight = maxHeight
    val id = tile.prop("entity") ?: return
    val hours = tile.intProp("hours", 24).coerceIn(1, 24 * 14)
    val ids = remember(id) { listOf(id) }
    val connected by ha.connectedSources.collectAsState()
    var history by remember(id, hours) { mutableStateOf(ha.cachedHistory(ids, hours).orEmpty()) }
    LaunchedEffect(id, hours, connected) {
        while (ha.historyAvailable(ids)) {
            runCatching { history = ha.history(ids, hours) }
            delay(5.minutes)
        }
    }
    val now = entity?.state?.toFloatOrNull()
    val points = history[id].orEmpty().let { p -> if (now != null) p + (System.currentTimeMillis() to now) else p }
    val first = points.firstOrNull()?.second
    val delta = if (first != null && now != null) now - first else null
    val better = tile.prop("betterWhen") ?: "up"
    val color = when {
        delta == null || abs(delta) < 1e-6f || better == "none" -> theme.muted
        (delta > 0) == (better == "up") -> BETTER
        else -> WORSE
    }
    val unit = tile.prop("unit") ?: entity?.unit.orEmpty()
    Column(Modifier.fillMaxSize()) {
        Title(tile.prop("name") ?: entity?.name ?: id, maxHeight)
        val value = animatedState(tile, entity)
        Text(value, color = theme.text, fontSize = main(minOf(maxHeight * 0.3f, maxWidth / (value.length.coerceAtLeast(3) * 0.55f))), fontWeight = valueWeight(), maxLines = 1)
        if (delta != null) {
            val decimals = tile.prop("decimals")?.toIntOrNull() ?: 1
            val arrow = if (delta > 0) "▲" else if (delta < 0) "▼" else "■"
            Text(
                "$arrow ${"%+.${decimals}f".format(delta)}${if (unit.isEmpty()) "" else " $unit"}  ·  ${hours} h",
                color = color, fontSize = detail((maxHeight * 0.1f).coerceIn(MIN_TEXT * 0.85f, 18.dp)), fontWeight = FontWeight.Medium, maxLines = 1,
            )
        }
        if (points.size >= 2 && maxHeight > 110.dp) {
            Spacer(Modifier.height(6.dp))
            Sparkline(points, color.takeIf { it != theme.muted } ?: seriesColor(0), Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** A line with a soft fill under it, no axes: the shape of the last hours at a glance. */
@Composable
internal fun Sparkline(points: List<Pair<Long, Float>>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val start = points.first().first
        val end = points.last().first
        val thin = points.thinned(start, end, size.width.toInt().coerceAtLeast(16))
        val low = thin.minOf { it.second }
        val range = (thin.maxOf { it.second } - low).takeIf { it > 0f } ?: 1f
        fun x(t: Long) = size.width * (t - start).toFloat() / (end - start).coerceAtLeast(1L)
        fun y(v: Float) = size.height * (1f - (v - low) / range) * 0.9f + size.height * 0.05f
        val line = Path().apply {
            thin.forEachIndexed { i, (t, v) -> if (i == 0) moveTo(x(t), y(v)) else lineTo(x(t), y(v)) }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, color.copy(alpha = 0.15f))
        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun BoxWithConstraintsScope.Gauge(tile: Tile, entity: HomeAssistant.Entity?) {
    val theme = LocalPanelTheme.current
    val tileHeight = maxHeight
    val (min, max) = range(tile)
    val v = entity?.state?.toDoubleOrNull()
    val f = v?.let { ((it - min) / (max - min)).toFloat().coerceIn(0f, 1f) } ?: 0f
    Column(Modifier.fillMaxSize()) {
        Title(tile.prop("name") ?: entity?.name ?: tile.prop("entity").orEmpty(), tileHeight)
        // Sized by the space under the title, not by the whole tile.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight * 1.15f)
            val stroke = (side * 0.09f).coerceIn(6.dp, 18.dp)
            val fillColor = seriesColor(0)
            Box(Modifier.size(side), contentAlignment = Alignment.Center) {
                // Ant's dashboard: a 285° arc open at the bottom.
                Canvas(Modifier.fillMaxSize().padding(stroke / 2)) {
                    val arc = Size(size.minDimension, size.minDimension)
                    val topLeft = Offset((size.width - arc.width) / 2, (size.height - arc.height) / 2)
                    drawArc(theme.muted.copy(alpha = 0.22f), 127.5f, 285f, false, topLeft, arc, style = Stroke(stroke.toPx(), cap = StrokeCap.Round))
                    if (v != null) drawArc(fillColor, 127.5f, 285f * f, false, topLeft, arc, style = Stroke(stroke.toPx(), cap = StrokeCap.Round))
                }
                val value = animatedState(tile, entity)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(value, color = theme.text, fontSize = main(minOf(side * 0.2f, side * 0.7f / (value.length.coerceAtLeast(3) * 0.55f))), fontWeight = valueWeight(), maxLines = 1)
                    Text("${fmt(min)} – ${fmt(max)}", color = theme.muted, fontSize = detail((side * 0.07f).coerceIn(MIN_TEXT * 0.8f, 16.dp)), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.Progress(tile: Tile, entity: HomeAssistant.Entity?) {
    val theme = LocalPanelTheme.current
    val maxWidth = maxWidth
    val maxHeight = maxHeight
    val (min, max) = range(tile)
    val v = entity?.state?.toDoubleOrNull()
    val f = v?.let { ((it - min) / (max - min)).toFloat().coerceIn(0f, 1f) } ?: 0f
    val bar = (maxHeight * 0.12f).coerceIn(8.dp, 20.dp)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Title(tile.prop("name") ?: entity?.name ?: tile.prop("entity").orEmpty(), maxHeight)
        val value = animatedState(tile, entity)
        Text(value, color = theme.text, fontSize = main(minOf(maxHeight * 0.3f, maxWidth / (value.length.coerceAtLeast(3) * 0.55f))), fontWeight = valueWeight(), maxLines = 1)
        Column {
            Box(Modifier.fillMaxWidth().height(bar).clip(RoundedCornerShape(50)).background(theme.muted.copy(alpha = 0.22f))) {
                Box(Modifier.fillMaxWidth(f).height(bar).clip(RoundedCornerShape(50)).background(seriesColor(0)))
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(fmt(min), color = theme.muted, fontSize = detail(MIN_TEXT * 0.8f), modifier = Modifier.weight(1f))
                Text(fmt(max), color = theme.muted, fontSize = detail(MIN_TEXT * 0.8f))
            }
        }
    }
}

private fun fmt(v: Double) = if (v % 1.0 == 0.0) "%.0f".format(v) else "%.1f".format(v)

// ---- Clock: analog ----------------------------------------------------------------------------

/** A clock face: twelve marks, hour and minute hands, a thin second hand in the accent. */
@Composable
internal fun BoxWithConstraintsScope.AnalogClock(now: LocalDateTime, showDate: Boolean, date: String) {
    val theme = LocalPanelTheme.current
    val maxWidth = maxWidth
    val maxHeight = maxHeight
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val side = minOf(maxWidth, maxHeight * (if (showDate) 0.82f else 1f))
        Canvas(Modifier.size(side)) {
            val r = size.minDimension / 2
            val c = Offset(size.width / 2, size.height / 2)
            fun at(turn: Double, length: Float) = Offset(c.x + (length * sin(turn * 2 * Math.PI)).toFloat(), c.y - (length * cos(turn * 2 * Math.PI)).toFloat())
            drawCircle(theme.muted.copy(alpha = 0.12f), r, c)
            for (i in 0 until 60) {
                val major = i % 5 == 0
                drawLine(
                    if (major) theme.text else theme.muted.copy(alpha = 0.5f),
                    at(i / 60.0, r * 0.94f), at(i / 60.0, r * (if (major) 0.8f else 0.88f)),
                    strokeWidth = (if (major) 3f else 1.2f) * density, cap = StrokeCap.Round,
                )
            }
            val h = (now.hour % 12 + now.minute / 60.0) / 12.0
            val m = (now.minute + now.second / 60.0) / 60.0
            drawLine(theme.text, c, at(h, r * 0.5f), strokeWidth = 6f * density, cap = StrokeCap.Round)
            drawLine(theme.text, c, at(m, r * 0.75f), strokeWidth = 4f * density, cap = StrokeCap.Round)
            drawLine(theme.accent, at(now.second / 60.0 + 0.5, r * 0.15f), at(now.second / 60.0, r * 0.82f), strokeWidth = 1.6f * density, cap = StrokeCap.Round)
            drawCircle(theme.accent, 4f * density, c)
        }
        if (showDate) Text(date, color = theme.muted, fontSize = label(side * 0.07f, fits = true), maxLines = 1)
    }
}

// ---- Chips (Mushroom's chips card) ----------------------------------------------------------

/**
 * A row of small rounded chips, one per entity: its icon and value. Lights, switches and the
 * like light up when on and toggle on a tap. For a status strip across a page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxWithConstraintsScope.ChipsTile(tile: Tile, ha: DataHub) {
    val theme = LocalPanelTheme.current
    val maxHeight = maxHeight
    val ids = tile.prop("entity")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    if (ids.isEmpty()) return Title(pickHint(tile, "Pick entities"), maxHeight)
    val chip = minOf(maxHeight, 48.dp).coerceAtLeast(28.dp)
    FlowRow(
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        ids.forEach { id ->
            val entity = ha.entity(id)
            val toggles = ha.canPress(id)
            val on = entity?.state in setOf("on", "open", "playing", "home", "heat", "cool")
            val tint = if (!toggles || on) seriesColor(0).takeIf { !toggles } ?: theme.accent else theme.muted
            // As Mushroom's chips: a switch says its state, a reading its value; short either way.
            val text = if (toggles) entity?.state?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "–" else chipValue(tile, entity)
            Row(
                Modifier.height(chip).clip(RoundedCornerShape(50)).background(theme.card)
                    .then(if (toggles) Modifier.clickable { ha.press(id) } else Modifier)
                    .padding(horizontal = chip * 0.3f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                iconFor(Tile(id, "entity", 0, 0, 1, 1, mapOf("entity" to id)), entity)?.let { ChipIcon(it, tint, chip * 0.5f) }
                Spacer(Modifier.width(6.dp))
                Text(text, color = theme.text, fontSize = detail(chip * 0.34f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun chipValue(tile: Tile, entity: HomeAssistant.Entity?): String {
    if (entity == null) return "–"
    val v = entity.state.toFloatOrNull() ?: return entity.state.replace('_', ' ').replaceFirstChar { it.uppercase() }
    val decimals = tile.prop("decimals")?.toIntOrNull()
    val number = if (decimals != null) "%.${decimals}f".format(v) else if (v % 1f == 0f) "%.0f".format(v) else "%.1f".format(v)
    return if (entity.unit.isEmpty() || entity.unit == "%") "$number${entity.unit}" else "$number ${entity.unit}"
}

@Composable
private fun ChipIcon(name: String, tint: Color, size: Dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap = remember(name, px) { PictureCache.icon(context, name, px) } ?: return
    Image(bitmap.asImageBitmap(), contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(size))
}

// ---- Heading (Mushroom's title, Bubble Card's separator) -------------------------------------

/**
 * A section title on the panel's background: big text with a subtitle (Mushroom's title card), or
 * with "style": "separator" the title with a line running beside it (Bubble Card's separator).
 */
@Composable
internal fun BoxWithConstraintsScope.HeadingTile(tile: Tile) {
    val theme = LocalPanelTheme.current
    val maxHeight = maxHeight
    val title = tile.prop("name") ?: "Heading"
    val subtitle = tile.prop("subtitle")
    val size = (maxHeight * (if (subtitle != null) 0.42f else 0.55f)).coerceIn(MIN_TEXT, 40.dp)
    if (tile.prop("style") == "separator") {
        Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = theme.text, fontSize = label(size * 0.7f, fits = true), fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).height(2.dp).clip(RoundedCornerShape(1.dp)).background(theme.muted.copy(alpha = 0.35f)))
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalArrangement = Arrangement.Center) {
        Text(title, color = theme.text, fontSize = label(size, fits = true), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, color = theme.muted, fontSize = detail(size * 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
