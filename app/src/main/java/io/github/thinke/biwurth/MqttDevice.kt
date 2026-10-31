package io.github.thinke.biwurth

import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.github.thinke.biwurth.config.MqttSettings
import io.github.thinke.biwurth.update.UpdateState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.time.Instant
import kotlin.math.roundToInt

/**
 * The tablet as a Home Assistant device over MQTT (discovery): screen switch, brightness, sleep
 * timeout and page select, battery, charging, last touch, Wi-Fi signal and IP sensors, and an
 * update entity that installs new releases.
 * State is one retained JSON message, sent on every change and once a minute.
 */
class MqttDevice(private val app: PanelApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow("Not configured")
    val status: StateFlow<String> = _status

    // ANDROID_ID is stable for this app on this tablet, so Home Assistant keeps the same device.
    @SuppressLint("HardwareIds")
    private val deviceId = "biwurth_" + Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
    private val base = "biwurth/$deviceId"

    fun start() {
        scope.launch { app.config.mqtt.collectLatest(::run) }
    }

    private suspend fun run(settings: MqttSettings) {
        if (!settings.configured) {
            _status.value = "Not configured"
            return
        }
        var backoff = 2_000L
        while (true) {
            _status.value = "Connecting…"
            val client = MqttClient(
                "${if (settings.tls) "ssl" else "tcp"}://${settings.host}:${settings.port}",
                deviceId,
                MemoryPersistence(),
            )
            val lost = CompletableDeferred<Throwable?>()
            try {
                client.setCallback(Callback(lost))
                runInterruptible { client.connect(options(settings)) }
                backoff = 2_000L
                _status.value = "Connected"
                online(client, settings, lost)
            } catch (e: Exception) {
                _status.value = "Cannot connect: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                runCatching { if (client.isConnected) client.disconnectForcibly(1_000) }
                runCatching { client.close() }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000)
        }
    }

    private fun options(settings: MqttSettings) = MqttConnectOptions().apply {
        isCleanSession = true
        keepAliveInterval = 30
        connectionTimeout = 10
        if (settings.username.isNotEmpty()) userName = settings.username
        if (settings.password.isNotEmpty()) password = settings.password.toCharArray()
        // Home Assistant shows the device unavailable as soon as the broker notices we're gone.
        setWill("$base/availability", "offline".toByteArray(), 1, true)
    }

    @OptIn(FlowPreview::class)
    private suspend fun online(client: MqttClient, settings: MqttSettings, lost: CompletableDeferred<Throwable?>) {
        client.subscribe(arrayOf("$base/+/set", "homeassistant/status"), intArrayOf(1, 1))
        publish(client, "$base/availability", "online")
        publishDiscovery(client, settings)
        publishState(client)
        coroutineScope {
            val jobs = listOf(
                launch {
                    val screen = app.screen
                    merge(screen.state, screen.page, screen.brightness, screen.lastTouch)
                        .debounce(500)
                        .collect { publishState(client) }
                },
                launch {
                    while (true) {
                        delay(60_000)
                        publishState(client)
                    }
                },
                // The page select lists the pages, so it changes with the layout.
                launch { app.config.layout.drop(1).collect { publishDiscovery(client, settings) } },
                launch { app.updater.state.collect { publishUpdate(client) } },
            )
            val cause = lost.await()
            jobs.forEach { it.cancel() }
            _status.value = "Disconnected: ${cause?.message ?: "connection lost"}"
        }
    }

    private inner class Callback(private val lost: CompletableDeferred<Throwable?>) : MqttCallback {
        override fun connectionLost(cause: Throwable?) {
            lost.complete(cause)
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) {}

        override fun messageArrived(topic: String, message: MqttMessage) {
            val payload = message.toString().trim()
            if (topic == "homeassistant/status") {
                // Home Assistant restarted: tell it about us again.
                if (payload == "online") scope.launch { republish() }
                return
            }
            val screen = app.screen
            when (topic.removePrefix("$base/").removeSuffix("/set")) {
                "screen" -> main.post { if (payload == "ON") screen.wake() else screen.sleep() }
                "brightness" -> payload.toFloatOrNull()?.let { value ->
                    main.post { screen.setBrightness(value.roundToInt().coerceIn(0, 100)) }
                }
                "sleep_after" -> payload.toFloatOrNull()?.let { value ->
                    app.prefs.idleSeconds = value.roundToInt().coerceIn(0, 86_400)
                    main.post(screen::restartIdleTimer)
                    scope.launch { republish() }
                }
                "update" -> if (payload == "install") {
                    (app.updater.state.value as? UpdateState.Available)?.release?.let(app.updater::install)
                }
                "page" -> {
                    val index = app.config.layout.value.pages.indexOfFirst { it.name == payload }
                    if (index >= 0) main.post { screen.page.value = index }
                }
            }
        }
    }

    // Set by online() so callbacks on Paho's thread can reach the live client.
    @Volatile private var current: Pair<MqttClient, MqttSettings>? = null

    private fun republish() {
        val (client, settings) = current ?: return
        runCatching {
            publish(client, "$base/availability", "online")
            publishDiscovery(client, settings)
            publishState(client)
            publishUpdate(client)
        }
    }

    private fun publishDiscovery(client: MqttClient, settings: MqttSettings) {
        current = client to settings
        val pages = app.config.layout.value.pages.map { it.name }
        val components = JSONObject()
            .put(
                "screen",
                component("switch", "Screen", "mdi:tablet-dashboard")
                    .put("command_topic", "$base/screen/set")
                    .put("value_template", "{{ 'ON' if value_json.screen == 'awake' else 'OFF' }}"),
            )
            .put("screen_state", component("sensor", "Screen state", "mdi:monitor").put("value_template", "{{ value_json.screen }}"))
            .put(
                "brightness",
                component("number", "Brightness", "mdi:brightness-6")
                    .put("command_topic", "$base/brightness/set")
                    .put("value_template", "{{ value_json.brightness }}")
                    .put("min", 0).put("max", 100).put("unit_of_measurement", "%").put("mode", "slider"),
            )
            .put(
                "sleep_after",
                component("number", "Sleep after", "mdi:timer-outline")
                    .put("command_topic", "$base/sleep_after/set")
                    .put("value_template", "{{ value_json.sleep_after }}")
                    .put("min", 0).put("max", 3600).put("step", 10).put("unit_of_measurement", "s")
                    .put("mode", "box").put("entity_category", "config"),
            )
            .put(
                "page",
                component("select", "Page", "mdi:book-open-page-variant")
                    .put("command_topic", "$base/page/set")
                    .put("value_template", "{{ value_json.page }}")
                    .put("options", JSONArray(pages)),
            )
            .put(
                "last_touch",
                component("sensor", "Last touch", "mdi:gesture-tap")
                    .put("device_class", "timestamp")
                    .put("value_template", "{{ value_json.last_touch }}"),
            )
            .put(
                "battery",
                component("sensor", "Battery", null)
                    .put("device_class", "battery").put("unit_of_measurement", "%").put("state_class", "measurement")
                    .put("value_template", "{{ value_json.battery }}"),
            )
            .put(
                "charging",
                component("binary_sensor", "Charging", null)
                    .put("device_class", "battery_charging")
                    .put("value_template", "{{ 'ON' if value_json.charging else 'OFF' }}"),
            )
            .put(
                "battery_temperature",
                component("sensor", "Battery temperature", null)
                    .put("device_class", "temperature").put("unit_of_measurement", "°C").put("state_class", "measurement")
                    .put("entity_category", "diagnostic")
                    .put("value_template", "{{ value_json.battery_temperature }}"),
            )
            .put(
                "wifi_signal",
                component("sensor", "Wi-Fi signal", null)
                    .put("device_class", "signal_strength").put("unit_of_measurement", "dBm").put("state_class", "measurement")
                    .put("entity_category", "diagnostic")
                    .put("value_template", "{{ value_json.wifi_signal }}"),
            )
            .put(
                "update",
                component("update", "biwurth", null)
                    .put("state_topic", "$base/update")
                    .put("command_topic", "$base/update/set")
                    .put("payload_install", "install")
                    .put("entity_category", "config"),
            )
            .put(
                "ip",
                component("sensor", "IP address", "mdi:ip-network")
                    .put("entity_category", "diagnostic")
                    .put("value_template", "{{ value_json.ip }}"),
            )

        val config = JSONObject()
            .put(
                "device",
                JSONObject()
                    .put("identifiers", JSONArray(listOf(deviceId)))
                    .put("name", settings.deviceName)
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("sw_version", "biwurth ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE}")
                    .put("configuration_url", "http://${ipAddress() ?: "localhost"}:${WebServer.PORT}"),
            )
            .put("origin", JSONObject().put("name", "biwurth").put("sw_version", BuildConfig.VERSION_NAME))
            .put("state_topic", "$base/state")
            .put("availability_topic", "$base/availability")
            .put("components", components)
        publish(client, "homeassistant/device/$deviceId/config", config.toString())
    }

    private fun component(platform: String, name: String, icon: String?): JSONObject {
        val key = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return JSONObject()
            .put("platform", platform)
            .put("name", name)
            .put("unique_id", "${deviceId}_$key")
            .apply { if (icon != null) put("icon", icon) }
    }

    private fun publishState(client: MqttClient) {
        if (!client.isConnected) return
        val screen = app.screen
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temperature = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val pages = app.config.layout.value.pages

        val state = JSONObject()
            .put("screen", screen.state.value.name.lowercase())
            .put("brightness", screen.brightness.value.takeIf { it >= 0 } ?: systemBrightness())
            .put("sleep_after", app.prefs.idleSeconds)
            .put("page", pages.getOrNull(screen.page.value)?.name ?: pages.firstOrNull()?.name.orEmpty())
            .put("last_touch", Instant.ofEpochMilli(screen.lastTouch.value).toString())
            .put("battery", if (level >= 0) level * 100 / scale else JSONObject.NULL)
            .put("charging", plugged)
            .put("battery_temperature", temperature)
            .put("wifi_signal", wifiSignal() ?: JSONObject.NULL)
            .put("ip", ipAddress() ?: JSONObject.NULL)
        runCatching { publish(client, "$base/state", state.toString()) }
    }

    // Home Assistant's update entity: versions, notes, and whether an install is running.
    private fun publishUpdate(client: MqttClient) {
        if (!client.isConnected) return
        val updater = app.updater
        val state = updater.state.value
        val release = when (state) {
            is UpdateState.Available -> state.release
            is UpdateState.Downloading -> state.release
            is UpdateState.Installing -> state.release
            is UpdateState.NeedsPermission -> state.release
            else -> null
        }
        val json = JSONObject()
            .put("installed_version", updater.installed.toString())
            .put("latest_version", (release?.version ?: updater.latest?.version ?: updater.installed).toString())
            .put("title", "biwurth")
            .put("in_progress", state is UpdateState.Downloading || state is UpdateState.Installing)
        release?.let { r ->
            json.put("release_url", r.pageUrl)
            json.put("release_summary", r.changes.flatMap { it.lines }.joinToString("\n").take(255))
        }
        runCatching { publish(client, "$base/update", json.toString()) }
    }

    private fun publish(client: MqttClient, topic: String, payload: String) {
        client.publish(topic, payload.toByteArray(), 1, true)
    }

    private fun systemBrightness(): Int {
        val value = Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        return value * 100 / 255
    }

    private fun wifiSignal(): Int? {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        return caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
    }

    private fun ipAddress(): String? {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
    }
}
