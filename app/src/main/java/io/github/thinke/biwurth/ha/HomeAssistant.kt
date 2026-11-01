package io.github.thinke.biwurth.ha

import io.github.thinke.biwurth.EventLog
import io.github.thinke.biwurth.config.HaSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One Home Assistant connection (a "homeassistant" source; there can be several): live entity
 * states over the WebSocket API, reconnecting with backoff, service calls, and REST for history
 * and calendars. Sources runs it ([run]) while its source is on and [close]s it when it stops.
 */
class HomeAssistant(private val name: String, private val settings: HaSettings) {
    class Entity(val id: String, val state: String, val attributes: JSONObject) {
        val name: String get() = attributes.optString("friendly_name").ifBlank { id }
        val unit: String get() = attributes.optString("unit_of_measurement")
    }

    /** An event of [calendar] (the entity it came from, so a tile of several can tell them apart). */
    data class CalendarEvent(val summary: String, val start: ZonedDateTime, val allDay: Boolean, val calendar: String = "")
    class Forecast(val time: ZonedDateTime, val condition: String, val high: Double?, val low: Double?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()

    private val _entities = MutableStateFlow<Map<String, Entity>>(emptyMap())
    val entities: StateFlow<Map<String, Entity>> = _entities

    private val _status = MutableStateFlow("Not configured")
    val status: StateFlow<String> = _status

    init {
        scope.launch {
            // Starting from the current value: the initial "Not configured" is only the default.
            var last = _status.value
            _status.collect {
                if (it != last && it != "Connecting…") EventLog.i("ha", "$name: $it")
                last = it
            }
        }
    }

    /** Stops everything this connection started; it isn't used again after. */
    fun close() {
        socket?.cancel()
        scope.cancel()
    }

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    @Volatile private var socket: WebSocket? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    /** Connects and stays connected, reconnecting as needed, until cancelled. */
    suspend fun run() {
        cache.clear()
        _entities.value = emptyMap()
        _connected.value = false
        if (!settings.configured) {
            _status.value = "Not configured: set the URL and token"
            awaitCancellation()
        }
        val request = try {
            Request.Builder().url(webSocketUrl(settings.url)).build()
        } catch (e: IllegalArgumentException) {
            _status.value = "Invalid URL"
            awaitCancellation()
        }
        var backoff = 2_000L
        while (true) {
            _status.value = "Connecting…"
            val closed = CompletableDeferred<String>()
            val ws = http.newWebSocket(request, Listener(settings.token, closed) { backoff = 2_000L })
            socket = ws
            try {
                _status.value = closed.await()
            } finally {
                ws.cancel()
                socket = null
                _connected.value = false
                pending.values.forEach { it.cancel() }
                pending.clear()
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000)
        }
    }

    private inner class Listener(
        private val token: String,
        private val closed: CompletableDeferred<String>,
        private val onAuthenticated: () -> Unit,
    ) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = JSONObject(text)
            when (msg.optString("type")) {
                "auth_required" ->
                    webSocket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
                "auth_ok" -> {
                    onAuthenticated()
                    scope.launch { loadStates() }
                }
                "auth_invalid" -> {
                    closed.complete("Token rejected")
                    webSocket.close(1000, null)
                }
                "result" -> pending.remove(msg.optInt("id"))?.complete(msg)
                "event" -> msg.optJSONObject("event")?.optJSONObject("data")?.let(::onStateChanged)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete("Disconnected")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed.complete("Cannot connect: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private suspend fun loadStates() {
        historyStore.clear()
        try {
            // Subscribe first so no change falls between the snapshot and the events.
            request(JSONObject().put("type", "subscribe_events").put("event_type", "state_changed"))
            val states = request(JSONObject().put("type", "get_states")).getJSONArray("result")
            _entities.value = (0 until states.length()).map { entity(states.getJSONObject(it)) }.associateBy { it.id }
            _status.value = "Connected"
            _connected.value = true
        } catch (e: Exception) {
            _status.value = "Error: ${e.message}"
            socket?.cancel()
        }
    }

    private fun onStateChanged(data: JSONObject) {
        val id = data.optString("entity_id")
        val newState = data.optJSONObject("new_state")
        _entities.update { if (newState == null) it - id else it + (id to entity(newState)) }
        val value = newState?.optString("state")?.toFloatOrNull() ?: return
        historyStore.append(id, time(newState) ?: System.currentTimeMillis(), value)
    }

    private fun entity(json: JSONObject) =
        Entity(json.getString("entity_id"), json.optString("state"), json.optJSONObject("attributes") ?: JSONObject())

    private suspend fun request(payload: JSONObject): JSONObject {
        val ws = socket ?: throw IOException("Not connected")
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        ws.send(payload.put("id", id).toString())
        val msg = try {
            withTimeout(15_000) { reply.await() }
        } finally {
            pending.remove(id)
        }
        if (!msg.optBoolean("success")) {
            throw IOException(msg.optJSONObject("error")?.optString("message") ?: "Request failed")
        }
        return msg
    }

    suspend fun callService(
        domain: String,
        service: String,
        entityId: String,
        data: JSONObject? = null,
        returnResponse: Boolean = false,
    ): JSONObject? {
        val msg = JSONObject()
            .put("type", "call_service")
            .put("domain", domain)
            .put("service", service)
            .put("target", JSONObject().put("entity_id", entityId))
        if (data != null) msg.put("service_data", data)
        if (returnResponse) msg.put("return_response", true)
        return request(msg).optJSONObject("result")?.optJSONObject("response")
    }

    /** What a tap on a toggle tile does: run scripts and scenes, press buttons, toggle the rest. */
    fun press(entityId: String) {
        val domain = entityId.substringBefore('.')
        val (serviceDomain, service) = when (domain) {
            "script", "scene" -> domain to "turn_on"
            "button", "input_button" -> domain to "press"
            else -> "homeassistant" to "toggle"
        }
        scope.launch {
            runCatching { callService(serviceDomain, service, entityId) }
                .onFailure { EventLog.e("ha", "Pressing $entityId failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private val historyStore = HistoryStore()

    /** [history] from memory only, for a tile to draw at once; null if not loaded yet. */
    fun cachedHistory(entityIds: List<String>, hours: Int): Map<String, List<Pair<Long, Float>>>? {
        val now = System.currentTimeMillis()
        val start = now - hours * 3_600_000L
        val slices = entityIds.associateWith { historyStore.slice(it, start, now) ?: return null }
        return slices.mapValues { thin(it.value, start, now) }
    }

    /**
     * Numeric history of each of [entityIds] over the last [hours], oldest first, as (epoch ms, value).
     * Fetched from Home Assistant only the first time; after that it comes from [historyStore],
     * which the live state changes keep up to date.
     */
    suspend fun history(entityIds: List<String>, hours: Int): Map<String, List<Pair<Long, Float>>> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val start = now - hours * 3_600_000L
            val missing = historyStore.missing(entityIds, start, now)
            if (missing.isNotEmpty()) {
                val fetched = fetchHistory(missing, Instant.ofEpochMilli(start))
                // Entities with no numeric history still count as loaded, so they aren't fetched again.
                missing.forEach { historyStore.load(it, now - start, fetched[it].orEmpty()) }
            }
            entityIds.associateWith { thin(historyStore.slice(it, start, now).orEmpty(), start, now) }
        }

    private suspend fun fetchHistory(entityIds: List<String>, start: Instant): Map<String, List<Pair<Long, Float>>> {
        val ids = enc(entityIds.joinToString(","))
        val json = JSONArray(get("/api/history/period/$start?filter_entity_id=$ids&minimal_response&no_attributes"))
        // One array per entity; with minimal_response only the first row of each names its entity.
        return (0 until json.length()).map { json.getJSONArray(it) }.filter { it.length() > 0 }.associate { rows ->
            rows.getJSONObject(0).optString("entity_id") to (0 until rows.length()).mapNotNull { i ->
                val row = rows.getJSONObject(i)
                val value = row.optString("state").toFloatOrNull() ?: return@mapNotNull null
                time(row)?.let { it to value }
            }
        }
    }

    private fun time(state: JSONObject): Long? =
        state.optString("last_changed").ifBlank { state.optString("last_updated") }.ifBlank { null }
            ?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    fun cachedCalendar(entityIds: String, days: Int): List<CalendarEvent>? = peek("calendar:$entityIds@$days")

    /** Events in the next [days] from one or more calendars (comma separated), soonest first. */
    suspend fun calendar(entityIds: String, days: Int): List<CalendarEvent> = cached("calendar:$entityIds@$days", 15 * 60_000L) {
        val zone = ZoneId.systemDefault()
        val start = Instant.now()
        val end = start.plus(Duration.ofDays(days.toLong()))
        entityIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { id ->
            val events = JSONArray(get("/api/calendars/${enc(id)}?start=$start&end=$end"))
            (0 until events.length()).map { i ->
                val event = events.getJSONObject(i)
                val eventStart = event.getJSONObject("start")
                val dateTime = eventStart.optString("dateTime")
                if (dateTime.isNotEmpty()) {
                    CalendarEvent(event.optString("summary"), OffsetDateTime.parse(dateTime).atZoneSameInstant(zone), false, id)
                } else {
                    CalendarEvent(event.optString("summary"), LocalDate.parse(eventStart.getString("date")).atStartOfDay(zone), true, id)
                }
            }
        }.sortedBy { it.start }
    }

    fun cachedForecast(entityId: String): List<Forecast>? = peek("forecast:$entityId")

    suspend fun forecast(entityId: String): List<Forecast> = cached("forecast:$entityId", 30 * 60_000L) {
        val response = callService(
            "weather", "get_forecasts", entityId,
            data = JSONObject().put("type", "daily"), returnResponse = true,
        ) ?: return@cached emptyList()
        val list = response.optJSONObject(entityId)?.optJSONArray("forecast") ?: return@cached emptyList()
        val zone = ZoneId.systemDefault()
        (0 until list.length()).map { i ->
            val day = list.getJSONObject(i)
            Forecast(
                OffsetDateTime.parse(day.getString("datetime")).atZoneSameInstant(zone),
                day.optString("condition"),
                day.optDouble("temperature").takeUnless { it.isNaN() },
                day.optDouble("templow").takeUnless { it.isNaN() },
            )
        }
    }

    private val cache = ConcurrentHashMap<String, Pair<Long, Any>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> peek(key: String): T? = cache[key]?.second as T?

    // Loading and parsing run on the IO pool: parsing a day of history on the main thread froze swipes.
    private suspend fun <T : Any> cached(key: String, maxAgeMs: Long, load: suspend () -> T): T {
        val hit = cache[key]
        if (hit != null && System.currentTimeMillis() - hit.first < maxAgeMs) return peek(key)!!
        return withContext(Dispatchers.IO) { load() }.also { cache[key] = System.currentTimeMillis() to it }
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(settings.url.trimEnd('/') + path)
            .header("Authorization", "Bearer ${settings.token}")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: ""
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun webSocketUrl(url: String) =
        url.trim().trimEnd('/').replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws") + "/api/websocket"
}
