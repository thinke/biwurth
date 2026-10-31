package io.github.thinke.biwurth.sources

import io.github.thinke.biwurth.EventLog
import io.github.thinke.biwurth.config.ConfigStore
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

    /** Per source id: "OK, 3 values at 12:00:05" or what went wrong, for the website. */
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status

    val history = SourceHistory(File(filesDir, "history"))

    /** What a tap on a tile showing that entity does (Modbus coil, MQTT command). */
    private val actions = ConcurrentHashMap<String, suspend () -> Unit>()

    fun start() {
        scope.launch { config.sources.collectLatest(::run) }
    }

    private suspend fun run(sources: List<SourceConfig>) = coroutineScope {
        _entities.value = emptyMap()
        _status.value = emptyMap()
        actions.clear()
        for (source in sources.filter { it.enabled }) {
            launch { keepRunning(source) }
        }
    }

    // A source that fails (unreachable, bad reply) waits and starts over, longer each time.
    private suspend fun keepRunning(source: SourceConfig) {
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
                status(source, "Error: ${e.message ?: e.javaClass.simpleName}")
                EventLog.e("source", "${source.name} (${source.type}): ${e.message ?: e.javaClass.simpleName}, retrying in ${backoff / 1000} s")
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(5 * 60_000L)
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
    }

    fun status(source: SourceConfig, text: String) {
        _status.update { it + (source.id to text) }
    }

    fun ok(source: SourceConfig, values: Int) {
        status(source, "OK: $values value${if (values == 1) "" else "s"} at ${java.time.LocalTime.now().withNano(0)}")
    }

    fun setAction(entityId: String, action: suspend () -> Unit) {
        actions[entityId] = action
    }

    fun press(entityId: String) {
        val action = actions[entityId] ?: return
        scope.launch { runCatching { action() } }
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
