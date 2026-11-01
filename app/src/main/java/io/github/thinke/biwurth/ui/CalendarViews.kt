package io.github.thinke.biwurth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thinke.biwurth.ha.HomeAssistant
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * What a calendar view fetches: from when (null: from now) and for how many days. A week is
 * the seven days from today; a month is its whole grid, from the first day of the week the
 * month starts in (the locale's first weekday) to the end of the week it ends in.
 */
internal fun calendarRange(view: String, days: Int): Pair<LocalDate?, Int> {
    val today = LocalDate.now()
    return when (view) {
        "week" -> today to 7
        "month" -> {
            val first = today.withDayOfMonth(1)
            val weekStart = WeekFields.of(Locale.getDefault()).firstDayOfWeek
            val lead = (first.dayOfWeek.value - weekStart.value + 7) % 7
            val start = first.minusDays(lead.toLong())
            val cells = lead + first.lengthOfMonth()
            start to ((cells + 6) / 7) * 7
        }
        else -> null to days
    }
}

// Events by the days they're on (a three-day event is on each), all-day ones first.
private fun byDay(events: List<HomeAssistant.CalendarEvent>): Map<LocalDate, List<HomeAssistant.CalendarEvent>> {
    val map = HashMap<LocalDate, MutableList<HomeAssistant.CalendarEvent>>()
    events.forEach { event -> event.days().forEach { map.getOrPut(it) { mutableListOf() } += event } }
    return map.mapValues { (_, list) -> list.sortedWith(compareBy({ !it.allDay }, { it.start })) }
}

@Composable
private fun calendarColor(event: HomeAssistant.CalendarEvent, calendars: List<String>): Color =
    seriesColor(calendars.indexOf(event.calendar).coerceAtLeast(0))

/**
 * A column for each of the days from today (seven, or as many as fit, ~90 units each): the
 * weekday and date over that day's events, all-day ones as tinted chips in their calendar's
 * colour, timed ones with their time. Today's head is in the accent.
 */
@Composable
internal fun BoxWithConstraintsScope.CalendarWeek(name: String?, events: List<HomeAssistant.CalendarEvent>, calendars: List<String>) {
    val theme = LocalPanelTheme.current
    val style = LocalTileStyle.current
    val maxWidth = maxWidth
    val maxHeight = maxHeight
    val locale = LocalLocale.current.platformLocale
    val today = LocalDate.now()
    val count = (maxWidth / 90.dp).toInt().coerceIn(1, 7)
    val days = byDay(events)
    val text = (maxWidth / count * 0.11f).coerceIn(MIN_TEXT * 0.85f, 18.dp)
    Column(Modifier.fillMaxSize()) {
        Title(name ?: "This week", maxHeight)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            repeat(count) { i ->
                val day = today.plusDays(i.toLong())
                if (i > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(borderColor().copy(alpha = if (style.listDividers) 1f else 0.4f)))
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp)) {
                    val isToday = day == today
                    Text(
                        "${day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} ${day.dayOfMonth}",
                        color = if (isToday) theme.accent else theme.muted,
                        fontSize = detail(text), fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    val lines = ((maxHeight - text * 5) / (text * 2.6f)).toInt().coerceAtLeast(1)
                    val list = days[day].orEmpty()
                    list.take(lines).forEach { event ->
                        val color = calendarColor(event, calendars)
                        if (event.allDay) {
                            Text(
                                event.summary, color = theme.text, fontSize = detail(text), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 3.dp).fillMaxWidth().clip(RoundedCornerShape(4.dp))
                                    .background(color.copy(alpha = 0.28f)).padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        } else {
                            Row(Modifier.padding(bottom = 3.dp), verticalAlignment = Alignment.Top) {
                                Box(Modifier.padding(top = 2.dp, end = 4.dp).size(width = 3.dp, height = text * 2f).background(color))
                                Column {
                                    Text(event.start.format(TIME), color = theme.muted, fontSize = detail(text * 0.85f), maxLines = 1)
                                    Text(event.summary, color = theme.text, fontSize = detail(text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (list.size > lines) Text("+${list.size - lines}", color = theme.muted, fontSize = detail(text * 0.85f))
                }
            }
        }
    }
}

/**
 * The month as a grid of weeks: weekday heads, each day's number (today in an accent circle,
 * days of the next and last month faint) and its events in their calendar's colour, as short
 * labels when the cells are wide enough and as dots when they aren't.
 */
@Composable
internal fun BoxWithConstraintsScope.CalendarMonth(name: String?, events: List<HomeAssistant.CalendarEvent>, calendars: List<String>, start: LocalDate) {
    val theme = LocalPanelTheme.current
    val style = LocalTileStyle.current
    val maxWidth = maxWidth
    val maxHeight = maxHeight
    val locale = LocalLocale.current.platformLocale
    val today = LocalDate.now()
    val month = today.month
    val weeks = calendarRange("month", 0).second / 7
    val days = byDay(events)
    val cell = maxWidth / 7
    val text = (cell * 0.16f).coerceIn(MIN_TEXT * 0.8f, 18.dp)
    val labels = cell >= 70.dp
    Column(Modifier.fillMaxSize()) {
        Title(name ?: "${month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }} ${today.year}", maxHeight)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { i ->
                Text(
                    start.plusDays(i.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                    color = theme.muted, fontSize = detail(text * 0.9f), textAlign = TextAlign.Center, maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        repeat(weeks) { w ->
            if (style.listDividers || w > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(borderColor().copy(alpha = if (style.listDividers) 1f else 0.35f)))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                repeat(7) { d ->
                    val day = start.plusDays((w * 7 + d).toLong())
                    val list = days[day].orEmpty()
                    Column(Modifier.weight(1f).fillMaxHeight().padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        DayNumber(day, today, day.month == month, text)
                        if (labels) {
                            // Under the day number (text × 1.7), each label takes about text × 1.25.
                            val cellHeight = (maxHeight - text * 4) / weeks
                            val lines = ((cellHeight - text * 1.7f - 4.dp) / (text * 1.25f)).toInt().coerceAtLeast(0)
                            list.take(lines).forEach { event ->
                                Text(
                                    event.summary, color = theme.text, fontSize = detail(text * 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().padding(top = 1.dp).clip(RoundedCornerShape(3.dp))
                                        .background(calendarColor(event, calendars).copy(alpha = 0.3f)).padding(horizontal = 3.dp),
                                )
                            }
                            if (list.size > lines && lines > 0) Text("+${list.size - lines}", color = theme.muted, fontSize = detail(text * 0.75f))
                        } else if (list.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                list.take(4).forEach { Box(Modifier.size(5.dp).clip(CircleShape).background(calendarColor(it, calendars))) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayNumber(day: LocalDate, today: LocalDate, inMonth: Boolean, text: Dp) {
    val theme = LocalPanelTheme.current
    val isToday = day == today
    Box(
        Modifier.size(text * 1.7f).clip(CircleShape).background(if (isToday) theme.accent else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${day.dayOfMonth}",
            color = when {
                isToday -> theme.onAccent
                inMonth -> theme.text
                else -> theme.muted.copy(alpha = 0.5f)
            },
            fontSize = detail(text), fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
        )
    }
}
