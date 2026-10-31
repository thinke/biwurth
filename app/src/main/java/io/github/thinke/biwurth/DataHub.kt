package io.github.thinke.biwurth

import io.github.thinke.biwurth.ha.HomeAssistant
import io.github.thinke.biwurth.sources.Sources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Everything tiles can show: Home Assistant's entities and biwurth's own (`src.<source>.<field>`,
 * from Sources). A Home Assistant id has one dot, ours two, so they never clash. History, taps
 * and the rest go to whichever side owns the entity.
 */
class DataHub(private val ha: HomeAssistant, private val sources: Sources) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val entities: StateFlow<Map<String, HomeAssistant.Entity>> =
        combine(ha.entities, sources.entities) { a, b -> a + b }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Home Assistant is connected (its history, forecasts and calendars can be fetched). */
    val connected: StateFlow<Boolean> get() = ha.connected

    fun isOwn(id: String) = id.startsWith("src.")

    fun cachedHistory(ids: List<String>, hours: Int): Map<String, List<Pair<Long, Float>>>? {
        val (own, theirs) = ids.partition(::isOwn)
        val fromHa = if (theirs.isEmpty()) emptyMap() else ha.cachedHistory(theirs, hours) ?: return null
        return fromHa + own.associateWith { sources.history(it, hours) }
    }

    suspend fun history(ids: List<String>, hours: Int): Map<String, List<Pair<Long, Float>>> {
        val (own, theirs) = ids.partition(::isOwn)
        val fromHa = if (theirs.isNotEmpty() && ha.connected.value) ha.history(theirs, hours) else emptyMap()
        return fromHa + own.associateWith { sources.history(it, hours) }
    }

    /** Whether a graph of [ids] can load history now. */
    fun historyAvailable(ids: List<String>) = ids.all(::isOwn) || ha.connected.value

    fun press(id: String) = if (isOwn(id)) sources.press(id) else ha.press(id)

    fun cachedForecast(id: String) = ha.cachedForecast(id)
    suspend fun forecast(id: String) = ha.forecast(id)
    fun cachedCalendar(ids: String, days: Int) = ha.cachedCalendar(ids, days)
    suspend fun calendar(ids: String, days: Int) = ha.calendar(ids, days)
}
