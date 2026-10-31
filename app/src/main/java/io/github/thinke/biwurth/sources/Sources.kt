package io.github.thinke.biwurth.sources

import io.github.thinke.biwurth.EventLog
import io.github.thinke.biwurth.config.ConfigStore
import io.github.thinke.biwurth.config.HaSettings
import io.github.thinke.biwurth.config.SourceConfig
import io.github.thinke.biwurth.ha.HomeAssistant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * biwurth's own data, without Home Assistant: runs every enabled source and publishes its values
 * as entities `src.<source>.<field>`, which every tile can show like a Home Assistant entity.
 * Numeric values go to [history] for graphs. Sources restart whenever their settings change.
 */
class Sources(private val config: ConfigStore, filesDir: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _entities = MutableStateFlow<Map<String, HomeAssistant.Entity>>(emptyMap())
    val entities: StateFlow<Map<String, HomeAssistant.Entity>> = _entities

    /** The running Home Assistant connections, by source id. */
    private val _homeAssistants = MutableStateFlow<Map<String, HomeAssistant>>(emptyMap())
    val homeAssistants: StateFlow<Map<String, HomeAssistant>> = _homeAssistants

    /**
     * The Home Assistant whose entities keep their plain ids (`sensor.x`): the one marked primary,
     * else the first in the list. Others' entities are `src.<source>.sensor.x`.
     */
    private val _primaryHa = MutableStateFlow<String?>(null)
    val primaryHa: StateFlow<String?> = _primaryHa

    /** Per source id: "OK, 3 values at 12:00:05" or what went wrong, for the website. */
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status

    val history = SourceHistory(File(filesDir, "history"))
    val telemetry = Telemetry(File(filesDir, "telemetry"))

    /** What a tap on a tile showing that entity does (Modbus coil, MQTT command), and whose it is. */
    private val actions = ConcurrentHashMap<String, Pair<SourceConfig, suspend () -> Unit>>()

    fun start() {
        scope.launch { config.sources.collectLatest(::run) }
    }

    private suspend fun run(sources: List<SourceConfig>) = coroutineScope {
        _entities.value = emptyMap()
        _status.value = emptyMap()
        actions.clear()
        // Chosen among all Home Assistants, on or off, so ids never move to another one when the
        // primary is switched off: its entities just go away until it's back.
        val homeAssistants = sources.filter { it.isHomeAssistant }
        _primaryHa.value = (homeAssistants.firstOrNull { it.settings.optBoolean("primary") } ?: homeAssistants.firstOrNull())?.id
        for (source in sources.filter { it.enabled }) {
            launch { keepRunning(source) }
        }
    }

    // A source that fails (unreachable, bad reply) waits and starts over, longer each time.
    private suspend fun keepRunning(source: SourceConfig) {
        if (source.isHomeAssistant) return runHomeAssistant(source)
        var backoff = 5_000L
        while (true) {
            try {
                status(source, "Starting…")
                when (source.type) {
                    "http" -> HttpSource(source, this).run()
                    "mqtt" -> MqttSource(source, this, config.mqtt.value).run()
                    "status" -> StatusSource(source, this).run()
                    "modbus" -> ModbusSource(source, this).run()
                }
                backoff = 5_000L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = describe(e)
                status(source, "Error: $reason")
                EventLog.e("source", "${source.name} (${source.type}): $reason, retrying in ${backoff / 1000} s")
                telemetry.event(source, "Error: $reason")
                failing.add(source.id)
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(5 * 60_000L)
        }
    }

    // Java's names for the usual network failures, in words.
    private fun describe(e: Exception) = when (e) {
        is java.io.EOFException -> "the device closed the connection"
        is java.net.SocketTimeoutException -> "no answer in time"
        else -> e.message ?: e.javaClass.simpleName
    }

    // A Home Assistant connection reconnects by itself; its status is the source's status.
    private suspend fun runHomeAssistant(source: SourceConfig) {
        val ha = HomeAssistant(source.name, HaSettings(source.settings.optString("url").trim(), source.settings.optString("token").trim()))
        _homeAssistants.update { it + (source.id to ha) }
        try {
            coroutineScope {
                launch { ha.status.collect { status(source, it) } }
                ha.run()
            }
        } finally {
            _homeAssistants.update { it - source.id }
            ha.close()
        }
    }

    fun entityId(source: SourceConfig, field: JSONObject) = "src.${source.id}.${source.fieldId(field)}"

    /** A field's new value; numbers also go to the history. */
    fun publish(source: SourceConfig, field: JSONObject, value: String, attributes: JSONObject = JSONObject()) {
        val id = entityId(source, field)
        attributes.put("friendly_name", field.optString("name"))
        field.optString("unit").takeIf { it.isNotEmpty() }?.let { attributes.put("unit_of_measurement", it) }
        _entities.update { it + (id to HomeAssistant.Entity(id, value, attributes)) }
        value.toFloatOrNull()?.let { history.record(id, System.currentTimeMillis(), it) }
        telemetry.reading(source, id.substringAfterLast('.'), value)
    }

    fun status(source: SourceConfig, text: String) {
        _status.update { it + (source.id to text) }
    }

    // Sources that failed since they last worked, for the "Working again" event.
    private val failing = ConcurrentHashMap.newKeySet<String>()

    fun ok(source: SourceConfig, values: Int) {
        if (failing.remove(source.id)) telemetry.event(source, "Working again")
        status(source, "OK: $values value${if (values == 1) "" else "s"} at ${java.time.LocalTime.now().withNano(0)}")
    }

    fun setAction(source: SourceConfig, entityId: String, action: suspend () -> Unit) {
        actions[entityId] = source to action
    }

    /** A tap from the panel; telemetry records it, and whether it worked. */
    fun press(entityId: String) {
        val (source, action) = actions[entityId] ?: return
        val field = entityId.substringAfterLast('.')
        val before = _entities.value[entityId]?.state
        scope.launch {
            runCatching { action() }
                .onSuccess { telemetry.event(source, "Pressed $field on the panel (was ${before ?: "unknown"})") }
                .onFailure { telemetry.event(source, "Pressing $field failed: ${it.message}") }
        }
    }

    fun history(entityId: String, hours: Int) = history.slice(entityId, hours)
}

/**
 * Values out of JSON by path: `current.temperature_2m`, `data[0].value`, `[2]`; an empty path is
 * the whole reply.
 */
object JsonPath {
    fun select(root: Any?, path: String): Any? {
        var node = root
        for (token in Regex("""[^.\[\]]+|\[\d+]""").findAll(path.trim()).map { it.value }) {
            node = when {
                token.startsWith("[") -> (node as? org.json.JSONArray)?.opt(token.trim('[', ']').toInt())
                else -> (node as? JSONObject)?.opt(token)
            } ?: return null
        }
        return node
    }

    fun parse(text: String): Any? = runCatching {
        val t = text.trim()
        when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> org.json.JSONArray(t)
            else -> t
        }
    }.getOrNull()

    /** A selected value as an entity state: numbers as written, booleans as on/off. */
    fun stateOf(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is Boolean -> if (value) "on" else "off"
        is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
        else -> value.toString()
    }
}
