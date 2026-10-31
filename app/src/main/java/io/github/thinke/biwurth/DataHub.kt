package io.github.thinke.biwurth

import io.github.thinke.biwurth.ha.HomeAssistant
import io.github.thinke.biwurth.sources.Sources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Everything tiles can show, from every source, under one set of ids:
 * - the primary Home Assistant's entities as they are: `sensor.x` (one dot);
 * - other Home Assistants' as `src.<source>.sensor.x`;
 * - biwurth's own sources' fields as `src.<source>.<field>`.
 * History, taps, forecasts and calendars go to whichever source owns the entity.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataHub(private val sources: Sources) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val haEntities = sources.homeAssistants.flatMapLatest { connections ->
        if (connections.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(connections.map { (id, ha) -> ha.entities.map { id to it } }) { it.toList() }
        }
    }

    val entities: StateFlow<Map<String, HomeAssistant.Entity>> =
        combine(sources.entities, haEntities, sources.primaryHa) { own, fromHa, primary ->
            val all = HashMap(own)
            for ((source, entities) in fromHa) {
                if (source == primary) {
                    all.putAll(entities)
                } else {
                    entities.forEach { (id, e) -> "src.$source.$id".let { all[it] = HomeAssistant.Entity(it, e.state, e.attributes) } }
                }
            }
            all as Map<String, HomeAssistant.Entity>
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Which Home Assistant sources are connected; tiles watch it to load history and forecasts. */
    val connectedSources: StateFlow<Set<String>> = sources.homeAssistants.flatMapLatest { connections ->
        if (connections.isEmpty()) {
            flowOf(emptySet())
        } else {
            combine(connections.map { (id, ha) -> ha.connected.map { id to it } }) { pairs -> pairs.filter { it.second }.map { it.first }.toSet() }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** The Home Assistant an entity belongs to, and its id there; null for biwurth's own fields. */
    private fun owner(id: String): Pair<HomeAssistant, String>? {
        val connections = sources.homeAssistants.value
        if (id.startsWith("src.")) {
            val source = id.removePrefix("src.").substringBefore('.')
            val ha = connections[source] ?: return null
            return ha to id.removePrefix("src.$source.")
        }
        return sources.primaryHa.value?.let(connections::get)?.let { it to id }
    }

    /** One of biwurth's own fields (not a Home Assistant entity). */
    fun isOwn(id: String) = id.startsWith("src.") && sources.homeAssistants.value[id.removePrefix("src.").substringBefore('.')] == null

    /** Whether a Home Assistant entity's connection is up (own fields are always available). */
    fun canFetch(id: String) = isOwn(id) || owner(id)?.first?.connected?.value == true

    fun historyAvailable(ids: List<String>) = ids.all(::canFetch)

    fun cachedHistory(ids: List<String>, hours: Int): Map<String, List<Pair<Long, Float>>>? {
        val result = HashMap<String, List<Pair<Long, Float>>>()
        for ((ha, group) in groupByOwner(ids)) {
            if (ha == null) {
                group.forEach { (id, _) -> result[id] = sources.history(id, hours) }
            } else {
                val fetched = ha.cachedHistory(group.map { it.second }, hours) ?: return null
                group.forEach { (id, local) -> result[id] = fetched[local].orEmpty() }
            }
        }
        return result
    }

    suspend fun history(ids: List<String>, hours: Int): Map<String, List<Pair<Long, Float>>> {
        val result = HashMap<String, List<Pair<Long, Float>>>()
        for ((ha, group) in groupByOwner(ids)) {
            if (ha == null) {
                group.forEach { (id, _) -> result[id] = sources.history(id, hours) }
            } else if (ha.connected.value) {
                val fetched = ha.history(group.map { it.second }, hours)
                group.forEach { (id, local) -> result[id] = fetched[local].orEmpty() }
            }
        }
        return result
    }

    // (owner or null for own fields) → [(id as tiles know it, id at the owner)]
    private fun groupByOwner(ids: List<String>): Map<HomeAssistant?, List<Pair<String, String>>> =
        ids.groupBy({ owner(it)?.first }, { id -> id to (owner(id)?.second ?: id) })

    fun press(id: String) {
        val owner = owner(id)
        if (owner != null) owner.first.press(owner.second) else sources.press(id)
    }

    fun cachedForecast(id: String) = owner(id)?.let { (ha, local) -> ha.cachedForecast(local) }
    suspend fun forecast(id: String) = owner(id)?.let { (ha, local) -> ha.forecast(local) }.orEmpty()

    fun cachedCalendar(ids: String, days: Int): List<HomeAssistant.CalendarEvent>? =
        calendarGroups(ids).flatMap { (ha, locals) -> ha.cachedCalendar(locals.joinToString(","), days) ?: return null }.sortedBy { it.start }

    suspend fun calendar(ids: String, days: Int): List<HomeAssistant.CalendarEvent> =
        calendarGroups(ids).flatMap { (ha, locals) -> ha.calendar(locals.joinToString(","), days) }.sortedBy { it.start }

    private fun calendarGroups(ids: String) =
        ids.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull(::owner)
            .groupBy({ it.first }, { it.second }).toList()
}
