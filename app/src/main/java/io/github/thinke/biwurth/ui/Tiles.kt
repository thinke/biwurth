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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.thinke.biwurth.config.Tile
import io.github.thinke.biwurth.ha.HomeAssistant
import io.github.thinke.biwurth.DataHub
import io.github.thinke.biwurth.panel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

// Text follows the tile's size, but never below what's readable from a step away.
private val MIN_TEXT = 14.dp

private val ON_STATES = setOf("on", "open", "opening", "playing", "home", "unlocked", "heat", "cool")

@Composable
fun TileView(tile: Tile) {
    val ha = LocalContext.current.panel.hub
    val opacity = tile.prop("opacity")?.toIntOrNull()?.coerceIn(0, 100)?.div(100f) ?: LocalTileOpacity.current
    CompositionLocalProvider(LocalTileTextSize provides tile.prop("textSize"), LocalTileOpacity provides opacity) {
        TileContent(tile, ha)
    }
}

@Composable
private fun TileContent(tile: Tile, ha: DataHub) {
    when (tile.type) {
        "clock" -> ClockTile(tile)
        "entity" -> Card { EntityTile(tile, ha) }
        "toggle" -> ToggleTile(tile, ha)
        "graph" -> Card { GraphTile(tile, ha) }
        "weather" -> Card { WeatherTile(tile, ha) }
        "calendar" -> Card { CalendarTile(tile, ha) }
        "camera" -> CameraTile(tile)
    }
}

/**
 * One entity's live state. Each tile watches only its own entities, so the many state changes
 * elsewhere in Home Assistant (power sensors every few seconds) don't redraw every tile.
 */
@Composable
private fun DataHub.entity(id: String?): HomeAssistant.Entity? {
    val flow = remember(this, id) { entities.map { all -> id?.let { all[it] } }.distinctUntilChanged() }
    return flow.collectAsState(initial = id?.let { entities.value[it] }).value
}

@Composable
private fun DataHub.entities(ids: List<String>): List<HomeAssistant.Entity?> {
    val flow = remember(this, ids) { entities.map { all -> ids.map { all[it] } }.distinctUntilChanged() }
    return flow.collectAsState(initial = ids.map { entities.value[it] }).value
}

@Composable
internal fun Card(
    color: Color = LocalPanelTheme.current.card,
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = color.alpha * LocalTileOpacity.current)).padding(12.dp),
        content = content,
    )
}

/** Text sizes follow the tile, so a layout looks the same on any screen; the panel's text scale applies. */
@Composable
internal fun Dp.sp(fraction: Float): TextUnit =
    with(LocalDensity.current) { (this@sp * fraction * LocalTextScale.current).toSp() }

/** The panel-wide text scale (Layout → Text scale), for how far away the panel is read from. */
val LocalTextScale = staticCompositionLocalOf { 1f }

/** The panel-wide tile opacity (Layout → Tile opacity); a tile's own "opacity" replaces it. */
val LocalTileOpacity = staticCompositionLocalOf { 1f }

/** The tile's "Text size": null for Auto, else small, medium, large or xl. */
private val LocalTileTextSize = staticCompositionLocalOf<String?> { null }

// Fixed sizes per role: the same on every tile, unlike Auto, which follows the tile's size.
private val MAIN_SIZES = mapOf("small" to 28.dp, "medium" to 40.dp, "large" to 56.dp, "xl" to 80.dp)
private val LABEL_SIZES = mapOf("small" to 16.dp, "medium" to 20.dp, "large" to 26.dp, "xl" to 32.dp)
private val DETAIL_SIZES = mapOf("small" to 12.dp, "medium" to 14.dp, "large" to 17.dp, "xl" to 21.dp)

/**
 * A tile's main text: values, temperatures, the time. [auto] is the largest size that fits the
 * tile, so the text scale may shrink it but never grows it past the tile's edges.
 */
@Composable
private fun main(auto: Dp, fixedFactor: Float = 1f): TextUnit {
    MAIN_SIZES[LocalTileTextSize.current]?.let { return (it * fixedFactor).sp(1f) }
    return fitted(auto)
}

/** Names a tile is about: a button's name, an event, a date. [fits]: [auto] already fills the tile. */
@Composable
private fun label(auto: Dp, fits: Boolean = false): TextUnit {
    LABEL_SIZES[LocalTileTextSize.current]?.let { return it.sp(1f) }
    return if (fits) fitted(auto) else auto.sp(1f)
}

@Composable
private fun fitted(size: Dp): TextUnit = size.sp(LocalTextScale.current.coerceAtMost(1f) / LocalTextScale.current)

/** Small print: titles, states, legends, axes. */
@Composable
internal fun detail(auto: Dp): TextUnit = (DETAIL_SIZES[LocalTileTextSize.current] ?: auto).sp(1f)

// Titles stop growing on big tiles; a 6-row graph doesn't need a huge heading.
@Composable
internal fun Title(text: String, height: Dp) = Text(
    text,
    color = LocalPanelTheme.current.muted,
    fontSize = detail((height * 0.16f).coerceIn(MIN_TEXT, 22.dp)),
    fontWeight = FontWeight.Medium,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)

@Composable
private fun ClockTile(tile: Tile) {
    val theme = LocalPanelTheme.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
        // Fit "HH:mm" in the width as well as the height.
        val size = minOf(maxHeight * 0.6f, maxWidth * 0.3f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(now.format(TIME), color = theme.text, fontSize = main(size, fixedFactor = 2f), fontWeight = FontWeight.ExtraLight, maxLines = 1)
            if (tile.prop("date") != "off") {
                Text(now.format(DATE), color = theme.muted, fontSize = label(size * 0.2f, fits = true), maxLines = 1)
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.EntityTile(tile: Tile, ha: DataHub) {
    val theme = LocalPanelTheme.current
    // Nested layouts can't reach the outer BoxWithConstraints scope; take the tile size along.
    val maxWidth = maxWidth
    val maxHeight = maxHeight
    val entity = ha.entity(tile.prop("entity"))
    Column(Modifier.fillMaxSize()) {
        Title(tile.prop("name") ?: entity?.name ?: tile.prop("entity") ?: "Entity", maxHeight)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            val value = formatState(tile, entity)
            val size = minOf(maxHeight * 0.42f, maxWidth / (value.length.coerceAtLeast(3) * 0.55f))
            Text(value, color = theme.text, fontSize = main(size), fontWeight = FontWeight.Light, maxLines = 1)
        }
    }
}

@Composable
private fun ToggleTile(tile: Tile, ha: DataHub) {
    val theme = LocalPanelTheme.current
    val id = tile.prop("entity")
    val entity = ha.entity(id)
    // Optionally a related number in the middle: a plug's power, a pump's flow.
    val measurement = tile.prop("measurement")?.let { ha.entity(it) }
    val on = entity?.state in ON_STATES
    Card(
        color = if (on) theme.accent else theme.card,
        modifier = Modifier.clickable(enabled = id != null) { id?.let(ha::press) },
    ) {
        val maxWidth = maxWidth
        val maxHeight = maxHeight
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                tile.prop("name") ?: entity?.name ?: id ?: "Button",
                color = if (on) theme.onAccent else theme.text,
                fontSize = label(minOf(maxHeight * 0.2f, maxWidth * 0.14f).coerceAtLeast(MIN_TEXT), fits = true),
                fontWeight = FontWeight.Medium,
                maxLines = if (tile.prop("measurement") != null) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (tile.prop("measurement") != null) {
                val value = formatState(tile, measurement)
                Text(
                    value,
                    color = if (on) theme.onAccent else theme.text,
                    fontSize = main(minOf(maxHeight * 0.32f, maxWidth / (value.length.coerceAtLeast(3) * 0.55f))),
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                )
            }
            Text(
                entity?.let { stateLabel(it.state) } ?: "–",
                color = if (on) theme.onAccent else theme.muted,
                fontSize = detail(minOf(maxHeight * 0.14f, maxWidth * 0.1f).coerceAtLeast(MIN_TEXT * 0.85f)),
            )
        }
    }
}

/**
 * History of one or more sensors (entity prop, comma separated) on a shared scale. One line keeps
 * its value in the header; several get a legend with a colour, label and current value each.
 */
@Composable
private fun BoxWithConstraintsScope.GraphTile(tile: Tile, ha: DataHub) {
    val theme = LocalPanelTheme.current
    val maxWidth = maxWidth
    val maxHeight = maxHeight
    val ids = tile.prop("entity")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    if (ids.isEmpty()) return Title("Pick a sensor", maxHeight)
    val labels = tile.prop("labels")?.split(',')?.map { it.trim() }.orEmpty()
    val hours = tile.intProp("hours", 24).coerceIn(1, 24 * 14)
    val current = ha.entities(ids)
    val connected by ha.connected.collectAsState()
    var history by remember(ids, hours) { mutableStateOf(ha.cachedHistory(ids, hours).orEmpty()) }
    // Reads come from memory (HistoryStore); re-read on each change of these sensors, and now and
    // then anyway so the time window keeps sliding.
    LaunchedEffect(ids, hours, connected, current) {
        while (ha.historyAvailable(ids)) {
            runCatching { history = ha.history(ids, hours) }
            delay(5.minutes)
        }
    }

    val now = System.currentTimeMillis()
    val lines = ids.mapIndexed { i, id ->
        val entity = current[i]
        val live = entity?.state?.toFloatOrNull()
        val points = history[id].orEmpty()
        GraphLine(
            label = labels.getOrNull(i)?.ifEmpty { null } ?: entity?.name ?: id,
            color = if (i == 0) theme.accent else SERIES_COLORS[(i - 1) % SERIES_COLORS.size],
            value = formatState(tile, entity),
            points = if (live != null) points + (now to live) else points,
        )
    }
    val drawn = lines.filter { it.points.size >= 2 }

    Column(Modifier.fillMaxSize()) {
        // Name in the top-left corner like every other tile, the current value opposite it.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) { Title(tile.prop("name") ?: if (lines.size == 1) lines[0].label else "History", maxHeight) }
            if (lines.size == 1) {
                Text(lines[0].value, color = theme.text, fontSize = label(minOf(maxHeight * 0.16f, 40.dp)), fontWeight = FontWeight.Light)
            }
        }
        if (drawn.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (ha.historyAvailable(ids)) "No history yet" else "Waiting for Home Assistant", color = theme.muted)
            }
            return@Column
        }
        val min = drawn.minOf { line -> line.points.minOf { it.second } }
        val max = drawn.maxOf { line -> line.points.maxOf { it.second } }
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            val start = now - hours * 3_600_000L
            val range = (max - min).takeIf { it > 0f } ?: 1f
            fun point(p: Pair<Long, Float>) = Offset(
                size.width * ((p.first - start).coerceAtLeast(0L).toFloat() / (now - start)),
                size.height * (1f - (p.second - min) / range),
            )
            drawn.forEach { graph ->
                // Sensors hold their value until the next change, so draw steps rather than slopes.
                val path = Path()
                graph.points.forEachIndexed { i, p ->
                    val o = point(p)
                    if (i == 0) {
                        path.moveTo(o.x, o.y)
                    } else {
                        path.lineTo(o.x, point(graph.points[i - 1]).y)
                        path.lineTo(o.x, o.y)
                    }
                }
                // Fill under a single line only; several fills would muddy each other.
                if (drawn.size == 1) {
                    val fill = Path().apply {
                        addPath(path)
                        lineTo(size.width, size.height)
                        lineTo(point(graph.points.first()).x, size.height)
                        close()
                    }
                    drawPath(fill, graph.color.copy(alpha = 0.15f))
                }
                drawPath(path, graph.color, style = Stroke(width = 2.dp.toPx()))
            }
        }
        if (lines.size > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                lines.forEach { line ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(line.color))
                        Text(
                            "${line.label} ${line.value}",
                            color = theme.text, fontSize = detail(13.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("min ${formatNumber(min, tile)}", color = theme.muted, fontSize = detail(12.dp), modifier = Modifier.weight(1f))
            Text("max ${formatNumber(max, tile)}  ·  ${hours} h", color = theme.muted, fontSize = detail(12.dp))
        }
    }
}

private class GraphLine(val label: String, val color: Color, val value: String, val points: List<Pair<Long, Float>>)

// Line colours after the theme's accent: distinct on dark and light backgrounds alike.
private val SERIES_COLORS = listOf(
    Color(0xFF5AA9E6), Color(0xFF7BC67E), Color(0xFFE06C75), Color(0xFFC678DD), Color(0xFF56B6C2), Color(0xFFD19A66),
)

@Composable
private fun BoxWithConstraintsScope.WeatherTile(tile: Tile, ha: DataHub) {
    val theme = LocalPanelTheme.current
    val maxWidth = maxWidth
    val maxHeight = maxHeight
    val id = tile.prop("entity") ?: return Title("Pick a weather entity", maxHeight)
    val entity = ha.entity(id)
    val connected by ha.connected.collectAsState()
    var forecast by remember(id) { mutableStateOf(ha.cachedForecast(id).orEmpty()) }
    LaunchedEffect(id, connected) {
        while (connected) {
            runCatching { forecast = ha.forecast(id) }
            delay(30.minutes)
        }
    }

    val unit = entity?.attributes?.optString("temperature_unit").orEmpty()
    val temperature = entity?.attributes?.optDouble("temperature")?.takeUnless { it.isNaN() }
    val days = forecast.filter { !it.time.toLocalDate().isBefore(LocalDate.now()) }
        .take((maxWidth / 72.dp).toInt().coerceIn(1, 7))
    val tall = days.isNotEmpty() && maxHeight > 140.dp

    Column(Modifier.fillMaxSize()) {
        Title(tile.prop("name") ?: entity?.name ?: id, maxHeight)
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val size = (if (tall) maxHeight * 0.3f else maxHeight * 0.5f).coerceAtMost(maxWidth * 0.18f)
            Text(conditionIcon(entity?.state), color = theme.text, fontSize = main(size * 0.9f))
            Text(
                temperature?.let { "${formatTemp(it)}$unit" } ?: "–",
                color = theme.text, fontSize = main(size * 0.8f), fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (tall) {
            Row(Modifier.fillMaxWidth()) {
                days.forEach { day ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(day.time.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), color = theme.muted, fontSize = detail(14.dp))
                        Text(conditionIcon(day.condition), color = theme.text, fontSize = label(24.dp))
                        Text(
                            listOfNotNull(day.high?.let(::formatTemp), day.low?.let(::formatTemp)).joinToString(" / "),
                            color = theme.text, fontSize = detail(14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.CalendarTile(tile: Tile, ha: DataHub) {
    val theme = LocalPanelTheme.current
    val maxWidth = maxWidth
    val maxHeight = maxHeight
    val ids = tile.prop("entity") ?: return Title("Pick a calendar", maxHeight)
    val days = tile.intProp("days", 7).coerceIn(1, 60)
    val connected by ha.connected.collectAsState()
    var events by remember(ids, days) { mutableStateOf(ha.cachedCalendar(ids, days).orEmpty()) }
    LaunchedEffect(ids, days, connected) {
        while (connected) {
            runCatching { events = ha.calendar(ids, days) }
            delay(15.minutes)
        }
    }

    val rowHeight = 40.dp
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Title(tile.prop("name") ?: "Calendar", maxHeight)
        if (events.isEmpty()) Text(if (connected) "Nothing in the next $days days" else "Waiting for Home Assistant", color = theme.muted)
        events.take(((maxHeight - 24.dp) / rowHeight).toInt().coerceAtLeast(1)).forEach { event ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    whenLabel(event),
                    color = theme.accent, fontSize = detail(15.dp), fontWeight = FontWeight.Medium, maxLines = 1,
                    modifier = Modifier.weight(0.38f),
                )
                Text(
                    event.summary,
                    color = theme.text, fontSize = label(17.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.62f),
                )
            }
        }
    }
}

private fun whenLabel(event: HomeAssistant.CalendarEvent): String {
    val date = event.start.toLocalDate()
    val today = LocalDate.now()
    val day = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.format(DateTimeFormatter.ofPattern("EEE d.M."))
    }
    return if (event.allDay) day else "$day ${event.start.format(TIME)}"
}

private fun formatState(tile: Tile, entity: HomeAssistant.Entity?): String {
    if (entity == null || entity.state == "unavailable" || entity.state == "unknown") return "–"
    val number = entity.state.toFloatOrNull() ?: return stateLabel(entity.state)
    val unit = tile.prop("unit") ?: entity.unit
    return "${formatNumber(number, tile)}${if (unit.isEmpty() || unit == "%") unit else " $unit"}"
}

private fun formatNumber(value: Float, tile: Tile): String {
    val decimals = tile.prop("decimals")?.toIntOrNull()?.coerceIn(0, 4)
    return if (decimals != null) "%.${decimals}f".format(value) else value.toString().removeSuffix(".0")
}

private fun formatTemp(value: Double) = "%.0f°".format(value)

private fun stateLabel(state: String) = state.replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun conditionIcon(condition: String?) = when (condition) {
    "sunny" -> "☀️"
    "clear-night" -> "🌙"
    "partlycloudy" -> "⛅"
    "cloudy" -> "☁️"
    "fog" -> "🌫️"
    "rainy" -> "🌧️"
    "pouring" -> "🌧️"
    "lightning", "lightning-rainy" -> "⛈️"
    "snowy", "snowy-rainy" -> "🌨️"
    "hail" -> "🧊"
    "windy", "windy-variant" -> "💨"
    "exceptional" -> "⚠️"
    else -> "–"
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
