package io.github.thinke.biwurth.sources

import io.github.thinke.biwurth.config.MqttSettings
import io.github.thinke.biwurth.config.SourceConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.UUID

private fun SourceConfig.intervalMs(default: Int) = settings.optInt("interval", default).coerceIn(1, 86_400) * 1000L

/**
 * Polls a URL every interval and picks each field out of the reply by path. Headers, one
 * "Name: value" per line, carry API keys.
 */
internal class HttpSource(private val source: SourceConfig, private val sources: Sources) {
    suspend fun run() {
        val url = source.settings.optString("url").ifBlank { error("No URL") }
        while (true) {
            val reply = runInterruptible { get(url) }
            val root = JsonPath.parse(reply)
            var count = 0
            for (field in source.fields) {
                val state = JsonPath.stateOf(JsonPath.select(root, field.optString("path")))
                if (state != null) {
                    sources.publish(source, field, state)
                    count++
                }
            }
            if (count == 0 && source.fields.isNotEmpty()) sources.status(source, "Reply had none of the fields: check the paths")
            else sources.ok(source, count)
            delay(source.intervalMs(60))
        }
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "biwurth")
            source.settings.optString("headers").lines().map { it.trim() }.filter { ':' in it }.forEach {
                setRequestProperty(it.substringBefore(':').trim(), it.substringAfter(':').trim())
            }
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Subscribes to MQTT topics on the broker set on the MQTT tab (its own connection): each field
 * is a topic, optionally a JSON path into its messages. A field with a command topic makes a
 * button: a tap publishes the "on" payload, or "off" when the field is on.
 */
internal class MqttSource(
    private val source: SourceConfig,
    private val sources: Sources,
    private val broker: MqttSettings,
) {
    suspend fun run() {
        if (!broker.configured) error("Set up the broker on the MQTT tab first")
        val client = MqttClient(
            "${if (broker.tls) "ssl" else "tcp"}://${broker.host}:${broker.port}",
            "biwurth-src-${source.id}-${UUID.randomUUID().toString().take(8)}",
            MemoryPersistence(),
        )
        val lost = CompletableDeferred<Throwable?>()
        val byTopic = source.fields.filter { it.optString("topic").isNotBlank() }.groupBy { it.optString("topic").trim() }
        client.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                lost.complete(cause)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}

            override fun messageArrived(topic: String, message: MqttMessage) {
                val payload = message.toString()
                val root = JsonPath.parse(payload)
                for (field in byTopic[topic].orEmpty()) {
                    val path = field.optString("path")
                    val state = if (path.isBlank()) payload.trim() else JsonPath.stateOf(JsonPath.select(root, path))
                    state?.let { sources.publish(source, field, it) }
                }
                sources.ok(source, byTopic.size)
            }
        })
        try {
            runInterruptible {
                client.connect(
                    MqttConnectOptions().apply {
                        isCleanSession = true
                        keepAliveInterval = 30
                        connectionTimeout = 10
                        if (broker.username.isNotEmpty()) userName = broker.username
                        if (broker.password.isNotEmpty()) password = broker.password.toCharArray()
                    },
                )
            }
            if (byTopic.isNotEmpty()) client.subscribe(byTopic.keys.toTypedArray(), IntArray(byTopic.size) { 1 })
            sources.status(source, "Connected, waiting for messages")
            for (field in source.fields) {
                val command = field.optString("commandTopic").trim().ifEmpty { continue }
                val id = sources.entityId(source, field)
                sources.setAction(source, id) {
                    val on = sources.entities.value[id]?.state?.lowercase() in setOf("on", "true", "1")
                    val payload = if (on) field.optString("payloadOff", "OFF") else field.optString("payloadOn", "ON")
                    client.publish(command, payload.toByteArray(), 1, false)
                }
            }
            throw lost.await() ?: error("Disconnected")
        } finally {
            runCatching { if (client.isConnected) client.disconnectForcibly(1_000) }
            runCatching { client.close() }
        }
    }
}

/**
 * Checks that things are up: an http(s) URL answers below 400, or a "host:port" accepts a TCP
 * connection. Each field gives "up"/"down" and `<field>_ms` with the response time.
 */
internal class StatusSource(private val source: SourceConfig, private val sources: Sources) {
    suspend fun run() {
        while (true) {
            var up = 0
            for (field in source.fields) {
                val target = field.optString("target").trim().ifEmpty { continue }
                val started = System.nanoTime()
                val ok = runInterruptible { runCatching { check(target) }.getOrDefault(false) }
                val ms = (System.nanoTime() - started) / 1_000_000
                if (ok) up++
                sources.publish(source, field, if (ok) "up" else "down", JSONObject().put("response_ms", ms))
                if (ok) {
                    val timing = JSONObject().put("name", field.optString("name") + " ms").put("unit", "ms")
                    sources.publish(source, timing, ms.toString())
                }
            }
            sources.status(source, "$up of ${source.fields.size} up at ${java.time.LocalTime.now().withNano(0)}")
            delay(source.intervalMs(60))
        }
    }

    private fun check(target: String): Boolean {
        if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = true
            }
            return try {
                connection.responseCode < 400
            } finally {
                connection.disconnect()
            }
        }
        val host = target.substringBeforeLast(':')
        val port = target.substringAfterLast(':', "").toIntOrNull() ?: 80
        Socket().use { it.connect(InetSocketAddress(host, port), 5_000) }
        return true
    }
}
