package io.github.thinke.biwurth.config

import android.content.Context
import android.net.Uri
import io.github.thinke.biwurth.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A Home Assistant connection's address and long-lived access token (a "homeassistant" source). */
data class HaSettings(val url: String = "", val token: String = "") {
    val configured get() = url.isNotBlank() && token.isNotBlank()
}

data class MqttSettings(
    val host: String = "",
    val port: Int = 1883,
    val username: String = "",
    val password: String = "",
    val tls: Boolean = false,
    val deviceName: String = "Wall panel",
) {
    val configured get() = host.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("host", host).put("port", port).put("username", username).put("password", password)
        .put("tls", tls).put("deviceName", deviceName)

    companion object {
        fun fromJson(json: JSONObject) = MqttSettings(
            host = json.optString("host").trim(),
            port = json.optInt("port", 1883).coerceIn(1, 65535),
            username = json.optString("username"),
            password = json.optString("password"),
            tls = json.optBoolean("tls"),
            deviceName = json.optString("deviceName").trim().ifEmpty { "Wall panel" },
        )
    }
}

/**
 * Where camera tiles get video. A tile names a go2rtc stream (Frigate has go2rtc built in) or
 * gives a full rtsp:// or http(s):// URL, which needs no go2rtc at all.
 */
data class CameraSettings(
    /** go2rtc's API, e.g. http://frigate:1984; empty when tiles use full URLs only. */
    val go2rtcUrl: String = "",
    val rtspPort: Int = 8554,
    /** Keep streams running while the backlight is off, so a touch shows live video at once. */
    val keepLiveAsleep: Boolean = false,
    /** At most this many cameras stream at once; the others show snapshots. */
    val maxLive: Int = 4,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("go2rtcUrl", go2rtcUrl).put("rtspPort", rtspPort)
        .put("keepLiveAsleep", keepLiveAsleep).put("maxLive", maxLive)

    /** The stream for a tile's camera: a full URL as is, or go2rtc's RTSP restream of that name. */
    fun streamUrl(camera: String): String? {
        if ("://" in camera) return camera
        val host = Uri.parse(go2rtcUrl.trim()).host ?: return null
        return "rtsp://$host:$rtspPort/${Uri.encode(camera)}"
    }

    /** go2rtc's still frame of a named stream; full-URL cameras need a snapshot URL of their own. */
    fun snapshotUrl(camera: String): String? {
        if ("://" in camera || go2rtcUrl.isBlank()) return null
        return "${go2rtcUrl.trim().trimEnd('/')}/api/frame.jpeg?src=${Uri.encode(camera)}"
    }

    companion object {
        fun fromJson(json: JSONObject) = CameraSettings(
            go2rtcUrl = json.optString("go2rtcUrl").trim(),
            rtspPort = json.optInt("rtspPort", 8554).coerceIn(1, 65535),
            keepLiveAsleep = json.optBoolean("keepLiveAsleep"),
            maxLive = json.optInt("maxLive", 4).coerceIn(0, 8),
        )
    }
}

/**
 * The panel's configuration, edited from the website: the layout, the Home Assistant connection, the
 * MQTT broker, cameras and biwurth's own data sources. Kept as JSON files in app storage; the token and password never leave the tablet.
 */
class ConfigStore(context: Context) {
    private val layoutFile = File(context.filesDir, "layout.json")
    private val haFile = File(context.filesDir, "ha.json")
    private val mqttFile = File(context.filesDir, "mqtt.json")
    private val camerasFile = File(context.filesDir, "cameras.json")
    private val sourcesFile = File(context.filesDir, "sources.json")

    private val _layout = MutableStateFlow(read(layoutFile)?.let(Layout::fromJson) ?: Layout.DEFAULT)
    val layout: StateFlow<Layout> = _layout


    private val _mqtt = MutableStateFlow(read(mqttFile)?.let(MqttSettings::fromJson) ?: MqttSettings())
    val mqtt: StateFlow<MqttSettings> = _mqtt

    private val _cameras = MutableStateFlow(read(camerasFile)?.let(CameraSettings::fromJson) ?: CameraSettings())
    val cameras: StateFlow<CameraSettings> = _cameras

    private val _sources = MutableStateFlow(
        runCatching { if (sourcesFile.exists()) JSONArray(sourcesFile.readText()) else null }.getOrNull()
            ?.let(SourceConfig::listFromJson).orEmpty(),
    )
    val sources: StateFlow<List<SourceConfig>> = _sources

    init {
        migrateHomeAssistant()
    }

    // Before several Home Assistants, the one connection lived in ha.json: make it a source once.
    private fun migrateHomeAssistant() {
        val old = read(haFile) ?: return
        if (_sources.value.none { it.isHomeAssistant }) {
            val settings = JSONObject()
                .put("url", old.optString("url")).put("token", old.optString("token")).put("primary", true)
            val source = SourceConfig("home_assistant", "Home Assistant", "homeassistant", old.optBoolean("enabled", true), settings, emptyList())
            saveSources(listOf(source) + _sources.value)
        }
        haFile.renameTo(File(haFile.parentFile, "ha.json.migrated"))
    }

    // Earlier layouts, newest last, so a bad save can be undone: layout-history/<millis>.json.
    private val historyDir = File(context.filesDir, "layout-history")

    /** Saves [layout], keeping the one it replaces in the history unless this is an [undo]. */
    @Synchronized
    fun saveLayout(layout: Layout, remember: Boolean = true) {
        val previous = _layout.value
        if (remember && previous != layout) {
            historyDir.mkdirs()
            write(File(historyDir, "${System.currentTimeMillis()}.json"), previous.toJson())
            historyFiles().dropLast(HISTORY_SIZE).forEach { it.delete() }
        }
        write(layoutFile, layout.toJson())
        _layout.value = layout
    }

    /** The saved earlier layouts, newest first: when each was replaced, and the layout. */
    @Synchronized
    fun layoutHistory(): List<Pair<Long, Layout>> = historyFiles().reversed().mapNotNull { file ->
        read(file)?.let { file.nameWithoutExtension.toLong() to Layout.fromJson(it) }
    }

    /** Puts back the layout from before the last save and forgets it; null when there is none. */
    @Synchronized
    fun undoLayout(): Layout? {
        val file = historyFiles().lastOrNull() ?: return null
        val layout = read(file)?.let(Layout::fromJson)
        file.delete()
        return layout?.also { saveLayout(it, remember = false) } ?: undoLayout()
    }

    private fun historyFiles(): List<File> =
        historyDir.listFiles { f -> f.name.matches(Regex("\\d+\\.json")) }.orEmpty().sortedBy { it.nameWithoutExtension.toLong() }

    @Synchronized
    fun saveMqtt(settings: MqttSettings) {
        write(mqttFile, settings.toJson())
        _mqtt.value = settings
    }

    @Synchronized
    fun saveCameras(settings: CameraSettings) {
        write(camerasFile, settings.toJson())
        _cameras.value = settings
    }

    @Synchronized
    fun saveSources(sources: List<SourceConfig>) {
        val tmp = File(sourcesFile.parentFile, "${sourcesFile.name}.tmp")
        tmp.writeText(JSONArray(sources.map { it.toJson() }).toString(2))
        tmp.renameTo(sourcesFile)
        _sources.value = sources
    }

    private fun read(file: File): JSONObject? =
        runCatching { if (file.exists()) JSONObject(file.readText()) else null }
            .onFailure { EventLog.w("config", "Ignoring unreadable ${file.name}", it) }
            .getOrNull()

    // Write next to the file and rename over it, so a power cut never leaves half a config.
    private fun write(file: File, json: JSONObject) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.toString(2))
        tmp.renameTo(file)
    }

    private companion object {
        const val TAG = "ConfigStore"
        const val HISTORY_SIZE = 20
    }
}
