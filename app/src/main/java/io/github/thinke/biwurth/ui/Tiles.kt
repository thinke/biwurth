package io.github.thinke.biwurth.ui

import android.annotation.SuppressLint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.thinke.biwurth.config.Tile
import io.github.thinke.biwurth.config.TileStyle
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
import kotlin.time.Duration.Companion.minutes

// Text follows the tile's size, but never below what's readable from a step away.
private val MIN_TEXT = 14.dp

private val ON_STATES = setOf("on", "open", "opening", "playing", "home", "unlocked", "heat", "cool")

@Composable
fun TileView(tile: Tile) {
    val ha = LocalContext.current.panel.hub
    val opacity = tile.prop("opacity")?.toIntOrNull()?.coerceIn(0, 100)?.div(100f) ?: LocalTileOpacity.current
    CompositionLocalProvider(
        LocalTileTextSize provides tile.prop("textSize"),
        LocalTileOpacity provides opacity,
        LocalTileBackground provides when (tile.prop("background")) {
            "none" -> false
            "card" -> true
            else -> LocalCards.current
        },
    ) {
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
        "space" -> SpaceTile(tile)
    }
}

/**
 * One entity's live state. Each tile watches only its own entities, so the many state changes
 * elsewhere in Home Assistant (power sensors every few seconds) don't redraw every tile.
 */
// Reading .value once as the starting state, so a tile doesn't flash empty before the first collect.
@SuppressLint("StateFlowValueCalledInComposition")
@Composable
private fun DataHub.entity(id: String?): HomeAssistant.Entity? = key(id) {
    val flow = remember(this) { entities.map { all -> id?.let { all[it] } }.distinctUntilChanged() }
    flow.collectAsState(initial = id?.let { entities.value[it] }).value
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
// Keyed by the ids: collectAsState keeps its last value when its flow changes, so without the key a
// tile given more entities would read the old, shorter list for a frame (and index past its end).
private fun DataHub.entities(ids: List<String>): List<HomeAssistant.Entity?> = key(ids) {
    val flow = remember(this) { entities.map { all -> ids.map { all[it] } }.distinctUntilChanged() }
    flow.collectAsState(initial = ids.map { entities.value[it] }).value
}

@Composable
internal fun Card(
    color: Color = LocalPanelTheme.current.card,
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    val style = LocalTileStyle.current
    val shape = RoundedCornerShape(style.radius.dp)
    // "No background" drops the card colour only: a lit toggle keeps its fill, which shows it's on.
    val fill = if (!LocalTileBackground.current && color == LocalPanelTheme.current.card) {
        Color.Transparent
    } else {
        color.copy(alpha = color.alpha * LocalTileOpacity.current)
    }
    BoxWithConstraints(
        modifier.fillMaxSize()
            .then(if (style.shadow > 0 && fill.alpha > 0f) Modifier.shadow(style.shadow.dp, shape, clip = false) else Modifier)
            .clip(shape).background(fill)
            .then(if (style.borderWidth > 0) Modifier.border(style.borderWidth.dp, borderColor(), shape) else Modifier)
            .padding(style.padding.dp),
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

/** False for a tile set to Background: None; its card is drawn without a colour. */
private val LocalTileBackground = staticCompositionLocalOf { true }

/** How tiles are drawn (theme.tiles): corners, padding, border, graph lines, value weight. */
val LocalTileStyle = staticCompositionLocalOf { TileStyle() }

@Composable
private fun valueWeight() = when (LocalTileStyle.current.valueWeight) {
    "regular" -> FontWeight.Normal
    "bold" -> FontWeight.SemiBold
    else -> FontWeight.Light
}

@Composable
private fun borderColor(): Color = LocalTileStyle.current.borderColor.let(::hexColor) ?: LocalPanelTheme.current.muted.copy(alpha = 0.4f)

/** A graph's [i]th line colour: the style's own list, else the accent and then a built-in set. */
@Composable
private fun seriesColor(i: Int): Color {
    val own = LocalTileStyle.current.graphColors.mapNotNull(::hexColor)
    if (own.isNotEmpty()) return own[i % own.size]
    return if (i == 0) LocalPanelTheme.current.accent else SERIES_COLORS[(i - 1) % SERIES_COLORS.size]
}

private fun hexColor(hex: String): Color? =
    hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

/** The theme's default for tiles' cards; a tile's own "background" overrides it. */
val LocalCards = staticCompositionLocalOf { true }

/** Layout → Animations: graphs drawing in, values rolling, live dots pulsing. */
val LocalAnimations = staticCompositionLocalOf { true }

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
internal fun Title(text: String, height: Dp) {
    val style = LocalTileStyle.current
    val theme = LocalPanelTheme.current
    Column {
        Text(
            text,
            color = if (style.titleColor == "text") theme.text else theme.muted,
            fontSize = detail((height * 0.16f).coerceIn(MIN_TEXT, 22.dp)),
            fontWeight = when (style.titleWeight) {
                "regular" -> FontWeight.Normal
                "bold" -> FontWeight.SemiBold
                else -> FontWeight.Medium
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // A card head's line, full width (out to the card's padding).
        if (style.titleDivider) {
            Box(
                Modifier.padding(top = (style.padding / 2).coerceAtLeast(4).dp).fillMaxWidth().height(1.dp)
                    .background(borderColor()),
            )
        }
    }
}

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
    BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(LocalTileStyle.current.radius.dp)), contentAlignment = Alignment.Center) {
        // Fit "HH:mm" in the width as well as the height.
        val size = minOf(maxHeight * 0.6f, maxWidth * 0.3f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(now.format(TIME), color = theme.text, fontSize = main(size, fixedFactor = 2f), fontWeight = when (LocalTileStyle.current.clockWeight) {
                "regular" -> FontWeight.Normal
                "light" -> FontWeight.Light
                else -> FontWeight.ExtraLight
            }, maxLines = 1)
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
            val value = animatedState(tile, entity)
            val size = minOf(maxHeight * 0.42f, maxWidth / (value.length.coerceAtLeast(3) * 0.55f))
            Text(value, color = theme.text, fontSize = main(size), fontWeight = valueWeight(), maxLines = 1)
        }
    }
}

@Composable
private fun ToggleTile(tile: Tile, ha: DataHub) {
    val theme = LocalPanelTheme.current
    val id = tile.prop("entity")
    val entity = ha.entity(id)
    // Optionally a related number: a plug's power, a pump's flow.
    val measured = tile.prop("measurement") != null
    val measurement = tile.prop("measurement")?.let { ha.entity(it) }
    val on = entity?.state in ON_STATES
    // A tap shows at once, and further taps wait until the new state comes back (at most 5 s):
    // otherwise the round trip to the device feels like nothing happened, and each extra tap
    // switches it again.
    var pendingFrom by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingFrom, entity?.state) {
        if (pendingFrom != null && entity?.state != pendingFrom) pendingFrom = null
    }
    LaunchedEffect(pendingFrom) {
        if (pendingFrom != null) {
            delay(5_000)
            pendingFrom = null
        }
    }
    val pending = pendingFrom != null
    // "switch" style: the card stays as it is and a switch shows on or off; "fill": the card lights up.
    val switchStyle = LocalTileStyle.current.toggleStyle == "switch"
    val lit = on && !switchStyle
    Card(
        color = if (lit) theme.accent else theme.card,
        modifier = Modifier
            .clickable(enabled = id != null && !pending) {
                pendingFrom = entity?.state.orEmpty()
                id?.let(ha::press)
            }
            .graphicsLayer { alpha = if (pending) 0.65f else 1f },
    ) {
        val maxWidth = maxWidth
        val maxHeight = maxHeight
        val textColor = if (lit) theme.onAccent else theme.text
        val stateColor = if (lit) theme.onAccent else theme.muted
        val name = tile.prop("name") ?: entity?.name ?: id ?: "Button"
        val state = if (pending) "Switching…" else entity?.let { stateLabel(it.state) } ?: "–"
        val value = animatedState(tile, measurement)
        // A one-row button has no room for three lines: name and state left, the number right.
        if (measured && maxHeight < 90.dp) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, color = textColor, fontSize = label((maxHeight * 0.32f).coerceAtLeast(MIN_TEXT), fits = true),
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state, color = stateColor, fontSize = detail(MIN_TEXT * 0.85f), maxLines = 1)
                }
                Text(
                    value, color = textColor, fontWeight = valueWeight(), maxLines = 1,
                    fontSize = main(minOf(maxHeight * 0.6f, maxWidth * 0.5f / (value.length.coerceAtLeast(3) * 0.55f))),
                )
                if (switchStyle) Switch(on, Modifier.padding(start = 10.dp))
            }
            return@Card
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    name,
                    color = textColor,
                    fontSize = label(minOf(maxHeight * 0.2f, maxWidth * 0.14f).coerceAtLeast(MIN_TEXT), fits = true),
                    fontWeight = FontWeight.Medium,
                    maxLines = if (measured) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (switchStyle) Switch(on, Modifier.padding(start = 8.dp))
            }
            if (measured) {
                Text(
                    value,
                    color = textColor,
                    fontSize = main(minOf(maxHeight * 0.32f, maxWidth / (value.length.coerceAtLeast(3) * 0.55f))),
                    fontWeight = valueWeight(),
                    maxLines = 1,
                )
            }
            Text(
                state,
                color = stateColor,
                fontSize = detail(minOf(maxHeight * 0.14f, maxWidth * 0.1f).coerceAtLeast(MIN_TEXT * 0.85f)),
            )
        }
    }
}

/** A switch showing on or off (the tile itself takes the tap): accent track when on, the knob sliding. */
@Composable
private fun Switch(on: Boolean, modifier: Modifier = Modifier) {
    val theme = LocalPanelTheme.current
    val knob by animateDpAsState(if (on) 20.dp else 2.dp, if (LocalAnimations.current) tween(200) else snap(), label = "switch")
    Box(
        modifier.size(width = 44.dp, height = 24.dp).clip(RoundedCornerShape(12.dp))
            .background(if (on) theme.accent else theme.muted.copy(alpha = 0.35f)),
    ) {
        Box(Modifier.offset(x = knob, y = 2.dp).size(20.dp).clip(CircleShape).background(Color.White))
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
    if (ids.isEmpty()) return Title(pickHint(tile, "Pick a sensor"), maxHeight)
    val labels = tile.prop("labels")?.split(',')?.map { it.trim() }.orEmpty()
    val hours = tile.intProp("hours", 24).coerceIn(1, 24 * 14)
    val current = ha.entities(ids)
    val connected by ha.connectedSources.collectAsState()
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
        val entity = current.getOrNull(i)
        val live = entity?.state?.toFloatOrNull()
        val points = history[id].orEmpty()
        GraphLine(
            label = labels.getOrNull(i)?.ifEmpty { null } ?: entity?.name ?: id,
            color = seriesColor(i),
            value = formatState(tile, entity),
            points = if (live != null) points + (now to live) else points,
        )
    }
    val drawn = lines.filter { it.points.size >= 2 }
    val style = LocalTileStyle.current

    // Animations: the lines draw in from the left when the graph first has data, the scale glides
    // to a new range instead of jumping, and a dot pulses at each line's live end.
    val animations = LocalAnimations.current
    val scaleSpec: AnimationSpec<Float> = if (animations) tween(900, easing = FastOutSlowInEasing) else snap()
    val minTarget = drawn.minOfOrNull { line -> line.points.minOf { it.second } } ?: 0f
    val maxTarget = drawn.maxOfOrNull { line -> line.points.maxOf { it.second } } ?: 0f
    val shownMin = animateFloatAsState(minTarget, scaleSpec, label = "graph min")
    val shownMax = animateFloatAsState(maxTarget, scaleSpec, label = "graph max")
    val reveal = remember(ids) { Animatable(if (animations) 0f else 1f) }
    LaunchedEffect(ids, drawn.isNotEmpty()) {
        if (drawn.isNotEmpty()) reveal.animateTo(1f, tween(GRAPH_REVEAL_MS, easing = FastOutSlowInEasing))
    }
    val pulse = if (animations) {
        rememberInfiniteTransition(label = "live").animateFloat(0f, 1f, infiniteRepeatable(tween(1_800), RepeatMode.Restart), label = "pulse")
    } else {
        null
    }

    Column(Modifier.fillMaxSize()) {
        // Name in the top-left corner like every other tile, the current value opposite it.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) { Title(tile.prop("name") ?: if (lines.size == 1) lines[0].label else "History", maxHeight) }
            if (lines.size == 1) {
                Text(lines[0].value, color = theme.text, fontSize = label(minOf(maxHeight * 0.16f, 40.dp)), fontWeight = valueWeight())
            }
        }
        if (drawn.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (ha.historyAvailable(ids)) "No history yet" else "Waiting for Home Assistant", color = theme.muted)
            }
            return@Column
        }
        val min = minTarget
        val max = maxTarget
        val start = now - hours * 3_600_000L
        val columns = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtLeast(16)
        // At most a few points per pixel column (spikes kept): a busy sensor's thousands of readings
        // would otherwise go into every path. Redone when the data or the tile's size changes.
        val prepared = remember(drawn, start, columns) { drawn.map { it.points.thinned(start, now, columns) } }
        val fill = drawn.size == 1 && style.graphFill
        val gridColor = borderColor()
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            // The lines: paths built once per data, size or scale; drawing in only clips them.
            Spacer(
                Modifier.fillMaxSize().drawWithCache {
                    val low = shownMin.value
                    val range = (shownMax.value - low).takeIf { it > 0f } ?: 1f
                    val map = GraphScale(start, now, low, range, size.width, size.height)
                    val paths = prepared.map { stepPath(it, map) }
                    val fills = if (fill) {
                        paths.map { line ->
                            Path().apply {
                                addPath(line)
                                lineTo(size.width, size.height)
                                lineTo(map.x(prepared[0].first().first), size.height)
                                close()
                            }
                        }
                    } else {
                        emptyList()
                    }
                    val stroke = Stroke(width = style.graphLineWidth.dp.toPx())
                    val grid = style.graphGrid
                    val gridColor = gridColor
                    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                    onDrawBehind {
                        if (grid) {
                            for (k in 0..3) {
                                val y = size.height * k / 3f
                                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                            }
                        }
                        clipRect(right = size.width * reveal.value) {
                            fills.forEachIndexed { i, path -> drawPath(path, drawn[i].color.copy(alpha = 0.15f)) }
                            paths.forEachIndexed { i, path -> drawPath(path, drawn[i].color, style = stroke) }
                        }
                    }
                },
            )
            // The live end of each line, on its own layer: the pulse redraws a few circles, not the lines.
            Canvas(Modifier.fillMaxSize()) {
                if (reveal.value < 1f) return@Canvas
                val low = shownMin.value
                val map = GraphScale(start, now, low, (shownMax.value - low).takeIf { it > 0f } ?: 1f, size.width, size.height)
                val t = pulse?.value
                drawn.forEach { graph ->
                    val end = graph.points.last().let { Offset(map.x(it.first), map.y(it.second)) }
                    if (t != null) drawCircle(graph.color.copy(alpha = 0.45f * (1f - t)), radius = 3.dp.toPx() + 9.dp.toPx() * t, center = end)
                    drawCircle(graph.color, radius = 3.dp.toPx(), center = end)
                }
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

private const val GRAPH_REVEAL_MS = 1_400

private data class GraphLine(val label: String, val color: Color, val value: String, val points: List<Pair<Long, Float>>)

/** Time and value to pixels in a graph [width] × [height]. */
private class GraphScale(val start: Long, val end: Long, val low: Float, val range: Float, val width: Float, val height: Float) {
    fun x(time: Long) = width * ((time - start).coerceAtLeast(0L).toFloat() / (end - start).coerceAtLeast(1L))
    fun y(value: Float) = height * (1f - (value - low) / range)
}

// Sensors hold their value until the next change, so steps rather than slopes.
private fun stepPath(points: List<Pair<Long, Float>>, map: GraphScale): Path = Path().apply {
    var lastY = 0f
    points.forEachIndexed { i, (time, value) ->
        val x = map.x(time)
        val y = map.y(value)
        if (i == 0) moveTo(x, y) else {
            lineTo(x, lastY)
            lineTo(x, y)
        }
        lastY = y
    }
}

/**
 * At most the first, lowest, highest and last reading of each of [columns] slices of the time from
 * [start] to [end], in time order: what a step line [columns] pixels wide can show, spikes and all.
 */
internal fun List<Pair<Long, Float>>.thinned(start: Long, end: Long, columns: Int): List<Pair<Long, Float>> {
    if (size <= columns * 4) return this
    val span = (end - start).coerceAtLeast(1L)
    val out = ArrayList<Pair<Long, Float>>(columns * 4)
    var i = 0
    while (i < size) {
        val column = ((this[i].first - start) * columns / span).coerceIn(0L, columns - 1L)
        var j = i
        var low = i
        var high = i
        while (j + 1 < size && ((this[j + 1].first - start) * columns / span).coerceIn(0L, columns - 1L) == column) {
            j++
            if (this[j].second < this[low].second) low = j
            if (this[j].second > this[high].second) high = j
        }
        sortedSetOf(i, low, high, j).forEach { out += this[it] }
        i = j + 1
    }
    return out
}

// Line colours after the theme's accent: distinct on dark and light backgrounds alike.
private val SERIES_COLORS = listOf(
    Color(0xFF5AA9E6), Color(0xFF7BC67E), Color(0xFFE06C75), Color(0xFFC678DD), Color(0xFF56B6C2), Color(0xFFD19A66),
)

@Composable
private fun BoxWithConstraintsScope.WeatherTile(tile: Tile, ha: DataHub) {
    val theme = LocalPanelTheme.current
    val maxWidth = maxWidth
    val maxHeight = maxHeight
    val id = tile.prop("entity") ?: return Title(pickHint(tile, "Pick a weather entity"), maxHeight)
    val entity = ha.entity(id)
    val connected by ha.connectedSources.collectAsState()
    var forecast by remember(id) { mutableStateOf(ha.cachedForecast(id).orEmpty()) }
    LaunchedEffect(id, connected) {
        while (ha.canFetch(id)) {
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
                // The unit already has its degree sign ("°C"); formatTemp's is for the bare forecast numbers.
                temperature?.let { "%.0f%s".format(it, unit.ifEmpty { "°" }) } ?: "–",
                color = theme.text, fontSize = main(size * 0.8f), fontWeight = valueWeight(), modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (tall) {
            Row(Modifier.fillMaxWidth()) {
                days.forEach { day ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(day.time.dayOfWeek.getDisplayName(TextStyle.SHORT, LocalLocale.current.platformLocale), color = theme.muted, fontSize = detail(14.dp))
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
    val ids = tile.prop("entity") ?: return Title(pickHint(tile, "Pick a calendar"), maxHeight)
    val days = tile.intProp("days", 7).coerceIn(1, 60)
    val connected by ha.connectedSources.collectAsState()
    var events by remember(ids, days) { mutableStateOf(ha.cachedCalendar(ids, days).orEmpty()) }
    LaunchedEffect(ids, days, connected) {
        while (ha.canFetch(ids.substringBefore(',').trim())) {
            runCatching { events = ha.calendar(ids, days) }
            delay(15.minutes)
        }
    }

    val rowHeight = 40.dp
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Title(tile.prop("name") ?: "Calendar", maxHeight)
        if (events.isEmpty()) {
            Text(if (ha.canFetch(ids.substringBefore(',').trim())) "Nothing in the next $days days" else "Waiting for Home Assistant", color = theme.muted)
        }
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

/**
 * [formatState], with a number rolling from its old value to the new one rather than jumping, as
 * many decimals as the entity reports throughout (or the tile's Decimals).
 */
@Composable
private fun animatedState(tile: Tile, entity: HomeAssistant.Entity?): String {
    val target = entity?.state?.toFloatOrNull()
    val shown = animateFloatAsState(
        target ?: 0f,
        animationSpec = if (LocalAnimations.current) tween(VALUE_ROLL_MS, easing = FastOutSlowInEasing) else snap(),
        label = "value",
    ).value
    if (entity == null || target == null) return formatState(tile, entity)
    val decimals = tile.prop("decimals")?.toIntOrNull()?.coerceIn(0, 4)
        ?: entity.state.substringAfter('.', "").length.coerceAtMost(4)
    val unit = tile.prop("unit") ?: entity.unit
    return "${"%.${decimals}f".format(shown)}${if (unit.isEmpty() || unit == "%") unit else " $unit"}"
}

private const val VALUE_ROLL_MS = 800

// An empty tile keeps its name (a template's "Temperature, 24 h") and says what it still needs.
private fun pickHint(tile: Tile, hint: String) = tile.prop("name")?.let { "$it: ${hint.replaceFirstChar(Char::lowercase)}" } ?: hint

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
