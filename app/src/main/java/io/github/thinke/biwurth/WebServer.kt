package io.github.thinke.biwurth

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import io.github.thinke.biwurth.config.CameraSettings
import io.github.thinke.biwurth.config.HaSettings
import io.github.thinke.biwurth.config.Layout
import io.github.thinke.biwurth.config.MqttSettings
import io.github.thinke.biwurth.config.SourceConfig
import io.github.thinke.biwurth.ui.CameraStatus
import io.github.thinke.biwurth.update.UpdateState
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * The panel's website and API on port [PORT]: the layout editor (assets/web) and the JSON API it
 * uses for the layout, Home Assistant, MQTT and screen settings. Everything under /api, plus /state, /wake and /sleep for Home Assistant, needs
 * `Authorization: Bearer <key>` with the key shown in the tablet's Settings.
 */
class WebServer(private val app: PanelApp) {
    private val main = Handler(Looper.getMainLooper())

    private class Request(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val bytes: ByteArray,
    ) {
        val body: String get() = bytes.toString(Charsets.UTF_8)
    }
    private class Response(val status: Int, val type: String, val body: ByteArray)
    private class BadRequest(message: String) : Exception(message)

    fun start() {
        thread(name = "web-server", isDaemon = true) {
            try {
                ServerSocket(PORT).use { server ->
                    while (true) {
                        val socket = server.accept()
                        thread(name = "web-client", isDaemon = true) { socket.use(::handle) }
                    }
                }
            } catch (e: Exception) {
                EventLog.e("web", "Web server stopped", e)
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream().buffered()
        val request = try {
            read(input) ?: return
        } catch (e: IOException) {
            return
        }
        val response = try {
            route(request)
        } catch (e: BadRequest) {
            json(400, JSONObject().put("error", e.message))
        } catch (e: JSONException) {
            json(400, JSONObject().put("error", "Invalid JSON: ${e.message}"))
        } catch (e: Exception) {
            EventLog.e("web", "${request.method} ${request.path} failed", e)
            json(500, JSONObject().put("error", e.message ?: e.javaClass.simpleName))
        }
        val head = "HTTP/1.1 ${response.status} ${REASONS[response.status] ?: ""}\r\n" +
            "Content-Type: ${response.type}\r\nContent-Length: ${response.body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray())
            write(response.body)
            flush()
        }
    }

    private fun read(input: InputStream): Request? {
        val requestLine = readLine(input)?.split(" ") ?: return null
        if (requestLine.size < 2) return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY) throw IOException("body too large")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw IOException("body cut short")
            read += n
        }
        val target = requestLine[1]
        return Request(requestLine[0], target.substringBefore('?'), target.substringAfter('?', ""), headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (line.size() == 0) null else line.toString("UTF-8")
            if (b == '\n'.code) return line.toString("UTF-8")
            if (b != '\r'.code) line.write(b)
            if (line.size() > 8192) throw IOException("line too long")
        }
    }

    private fun route(request: Request): Response {
        val isApi = request.path.startsWith("/api/") || request.path in CONTROL_PATHS
        if (!isApi) return if (request.method == "GET") static(request.path) else json(405, error("Method not allowed"))
        if (!authorized(request.headers["authorization"].orEmpty())) return json(401, error("Wrong or missing key"))

        val screen = app.screen
        // Background pictures for the website's preview: /api/background/image/<file name>.
        if (request.method == "GET" && request.path.startsWith("/api/background/image/")) {
            val file = app.backgrounds.file(request.path.substringAfterLast('/')) ?: return json(404, error("Not found"))
            val type = when (file.extension) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
            return Response(200, type, file.readBytes())
        }
        return when ("${request.method} ${request.path}") {
            "GET /state", "GET /api/state" -> json(200, JSONObject().put("screen", screen.state.value.name.lowercase()))
            "POST /wake", "POST /api/wake" -> {
                main.post(screen::wake)
                json(200, JSONObject().put("screen", "awake"))
            }
            "POST /sleep", "POST /api/sleep" -> {
                main.post(screen::sleep)
                json(200, JSONObject().put("screen", "sleeping"))
            }
            "GET /api/config" -> json(200, config())
            "GET /api/status" -> json(200, status())
            "GET /api/entities" -> json(200, entities())
            "PUT /api/layout" -> {
                // Compile first (check and preload), then switch: the new layout appears complete.
                val layout = Layout.fromJson(JSONObject(request.body))
                val report = runBlocking { LayoutCompiler(app).compile(layout) }
                app.config.saveLayout(layout)
                json(200, JSONObject().put("layout", layout.toJson()).put("report", report.toJson()))
            }
            "PUT /api/ha" -> {
                val body = JSONObject(request.body)
                val url = body.optString("url").trim()
                if (url.isNotEmpty() && !url.matches(Regex("^https?://.+", RegexOption.IGNORE_CASE))) {
                    throw BadRequest("The URL must start with http:// or https://")
                }
                // An empty token keeps the saved one, so the website never needs to see it.
                val token = body.optString("token").trim().ifEmpty { app.config.ha.value.token }
                val enabled = if (body.has("enabled")) body.getBoolean("enabled") else app.config.ha.value.enabled
                app.config.saveHa(HaSettings(url, token, enabled))
                json(200, config())
            }
            "PUT /api/mqtt" -> {
                val body = JSONObject(request.body)
                // An empty password keeps the saved one, like the Home Assistant token.
                if (body.optString("password").isEmpty()) body.put("password", app.config.mqtt.value.password)
                app.config.saveMqtt(MqttSettings.fromJson(body))
                json(200, config())
            }
            "GET /api/update" -> json(200, app.updater.stateJson())
            "POST /api/update/check" -> {
                runBlocking { app.updater.check() }
                json(200, app.updater.stateJson())
            }
            "POST /api/update/install", "POST /api/update/skip" -> {
                val release = (app.updater.state.value as? UpdateState.Available)?.release
                    ?: (app.updater.state.value as? UpdateState.NeedsPermission)?.release
                    ?: (app.updater.state.value as? UpdateState.Failed)?.release
                    ?: throw BadRequest("No update to act on: check first")
                if (request.path.endsWith("install")) app.updater.install(release) else app.updater.skip(release)
                json(200, app.updater.stateJson())
            }
            "POST /api/update/permission" -> {
                main.post(app.updater::openInstallPermissionSettings)
                json(200, app.updater.stateJson())
            }
            "PUT /api/update/settings" -> {
                val body = JSONObject(request.body)
                if (body.has("check")) app.prefs.updateCheck = body.getBoolean("check")
                if (body.has("prerelease")) app.prefs.updatePrerelease = body.getBoolean("prerelease")
                if (body.has("autoInstall")) app.prefs.updateAutoInstall = body.getBoolean("autoInstall")
                json(200, app.updater.stateJson())
            }
            "POST /api/provisioning" -> {
                // The release to install: the newest seen on GitHub, else this one.
                val body = JSONObject(request.body.ifBlank { "{}" })
                val version = app.updater.latest?.version ?: app.updater.installed
                val payload = Provisioning.payload(app, version, body.optString("wifiSsid"), body.optString("wifiPassword"))
                json(
                    200,
                    JSONObject()
                        .put("version", version.toString())
                        .put("debugBuild", BuildConfig.DEBUG)
                        // What the code holds, so it can be checked by eye (never the Wi-Fi password).
                        .put("apk", payload.getString("android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION"))
                        .put("checksum", payload.getString("android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"))
                        .put("svg", Provisioning.qrSvg(payload.toString())),
                )
            }
            "PUT /api/background/image" -> {
                val name = runCatching { app.backgrounds.save(request.bytes) }.getOrElse { throw BadRequest(it.message ?: "Bad picture") }
                json(200, JSONObject().put("image", name))
            }
            "GET /api/logs" -> Response(200, "text/plain; charset=utf-8", EventLog.tail(500).toByteArray())
            "GET /api/logs/all" -> Response(200, "text/plain; charset=utf-8", EventLog.all().toByteArray())
            "DELETE /api/logs" -> {
                EventLog.clear()
                json(200, JSONObject().put("cleared", true))
            }
            "GET /api/sources" -> json(200, sourcesJson())
            "PUT /api/ha/enabled" -> {
                val ha = app.config.ha.value
                app.config.saveHa(ha.copy(enabled = JSONObject(request.body).getBoolean("enabled")))
                json(200, sourcesJson())
            }
            "PUT /api/sources" -> {
                app.config.saveSources(SourceConfig.listFromJson(JSONArray(request.body)))
                json(200, sourcesJson())
            }
            "GET /api/backup" -> json(200, backup(includeSecrets = "secrets=1" in request.query.split('&')))
            "PUT /api/backup" -> json(200, restore(JSONObject(request.body)))
            "GET /api/cameras" -> json(200, go2rtcStreams())
            "GET /api/cameras/status" -> json(
                200,
                JSONArray(
                    CameraStatus.entries.values.sortedBy { it.name }.map {
                        JSONObject().put("name", it.name).put("url", it.url).put("status", it.text)
                    },
                ),
            )
            "PUT /api/cameras" -> {
                app.config.saveCameras(CameraSettings.fromJson(JSONObject(request.body)))
                json(200, config())
            }
            "POST /api/admin/request" -> {
                main.post { Kiosk.requestAdmin(app) }
                json(200, JSONObject().put("message", "Confirm on the tablet"))
            }
            "PUT /api/screen" -> {
                val body = JSONObject(request.body)
                if (body.has("idleSeconds")) app.prefs.idleSeconds = body.getInt("idleSeconds").coerceIn(0, 86_400)
                if (body.has("brightness")) {
                    val brightness = body.getInt("brightness").coerceIn(-1, 100)
                    main.post { screen.setBrightness(brightness) }
                }
                if (body.has("sleepMode")) {
                    app.prefs.sleepMode = runCatching { SleepMode.valueOf(body.getString("sleepMode")) }
                        .getOrElse { throw BadRequest("Unknown sleep mode") }
                }
                main.post(screen::restartIdleTimer)
                json(200, config())
            }
            else -> json(404, error("Not found"))
        }
    }

    private fun config(): JSONObject {
        val ha = app.config.ha.value
        val mqtt = app.config.mqtt.value
        return JSONObject()
            .put("version", BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) " (debug)" else "")
            .put("layout", app.config.layout.value.toJson())
            .put("ha", JSONObject().put("url", ha.url).put("tokenSet", ha.token.isNotEmpty()).put("enabled", ha.enabled))
            .put("mqtt", mqtt.toJson().put("password", "").put("passwordSet", mqtt.password.isNotEmpty()))
            .put("cameras", app.config.cameras.value.toJson())
            .put(
                "screen",
                JSONObject()
                    .put("idleSeconds", app.prefs.idleSeconds)
                    .put("sleepMode", app.prefs.sleepMode.name)
                    .put("brightness", app.prefs.brightness)
                    .put("adminActive", Kiosk.isAdmin(app)),
            )
            .put("display", display())
    }

    // The panel's native size; the editor turns it to match the chosen orientation.
    private fun display(): JSONObject {
        val mode = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).mode
        return JSONObject().put("width", mode.physicalWidth).put("height", mode.physicalHeight)
    }

    private fun status() = JSONObject()
        .put("screen", app.screen.state.value.name.lowercase())
        .put("page", app.screen.page.value)
        .put("ha", app.ha.status.value)
        .put("mqtt", app.mqtt.status.value)
        .put("entities", app.ha.entities.value.size)
        .put("ownEntities", app.sources.entities.value.size)

    private fun entities(): JSONArray = JSONArray(
        app.hub.entities.value.values.sortedBy { it.id }.map {
            JSONObject().put("id", it.id).put("name", it.name).put("state", it.state).put("unit", it.unit)
        },
    )

    /**
     * Everything the website configures, in one file. The Home Assistant token and MQTT password
     * only with [includeSecrets]; the website key never, so a backup can't open this panel.
     */
    private fun backup(includeSecrets: Boolean): JSONObject {
        val ha = app.config.ha.value
        val mqtt = app.config.mqtt.value
        return JSONObject()
            .put("type", "biwurth-backup")
            .put("version", 1)
            .put("created", java.time.Instant.now().toString())
            .put("layout", app.config.layout.value.toJson())
            .put("ha", JSONObject().put("url", ha.url).put("enabled", ha.enabled).apply { if (includeSecrets) put("token", ha.token) })
            .put("mqtt", mqtt.toJson().apply { if (!includeSecrets) remove("password") })
            .put("cameras", app.config.cameras.value.toJson())
            .put(
                "sources",
                // HTTP headers carry API keys: secrets, like the token.
                JSONArray(
                    app.config.sources.value.map { source ->
                        source.toJson().apply {
                            if (!includeSecrets) getJSONObject("settings").remove("headers")
                        }
                    },
                ),
            )
            .apply {
                val image = app.config.layout.value.background.image?.let(app.backgrounds::file)
                if (image != null) {
                    put("backgroundImage", android.util.Base64.encodeToString(image.readBytes(), android.util.Base64.NO_WRAP))
                }
            }
            .put(
                "sources",
                // HTTP headers carry API keys: secrets, like the token.
                JSONArray(
                    app.config.sources.value.map { source ->
                        source.toJson().apply {
                            if (!includeSecrets) getJSONObject("settings").remove("headers")
                        }
                    },
                ),
            )
            .apply {
                val image = app.config.layout.value.background.image?.let(app.backgrounds::file)
                if (image != null) {
                    put("backgroundImage", android.util.Base64.encodeToString(image.readBytes(), android.util.Base64.NO_WRAP))
                }
            }
            .put(
                "screen",
                JSONObject()
                    .put("idleSeconds", app.prefs.idleSeconds)
                    .put("sleepMode", app.prefs.sleepMode.name)
                    .put("brightness", app.prefs.brightness)
                    .put("kiosk", app.prefs.kiosk),
            )
    }

    /** Applies the parts of a backup it contains; a missing token or password keeps the current one. */
    private fun restore(backup: JSONObject): JSONObject {
        // "panel-backup": files from before the app was named biwurth.
        if (backup.optString("type") !in setOf("biwurth-backup", "panel-backup")) throw BadRequest("Not a biwurth backup file")
        var report: LayoutCompiler.Report? = null
        backup.optJSONObject("ha")?.let { ha ->
            val token = ha.optString("token").ifEmpty { app.config.ha.value.token }
            app.config.saveHa(HaSettings(ha.optString("url").trim(), token, ha.optBoolean("enabled", true)))
        }
        backup.optJSONObject("mqtt")?.let { mqtt ->
            if (mqtt.optString("password").isEmpty()) mqtt.put("password", app.config.mqtt.value.password)
            app.config.saveMqtt(MqttSettings.fromJson(mqtt))
        }
        backup.optJSONObject("cameras")?.let { app.config.saveCameras(CameraSettings.fromJson(it)) }
        backup.optJSONArray("sources")?.let { array ->
            // Headers left out of a backup keep the ones this panel has.
            val current = app.config.sources.value.associateBy { it.id }
            val restored = SourceConfig.listFromJson(array).map { source ->
                val headers = current[source.id]?.settings?.optString("headers")
                if (!source.settings.has("headers") && !headers.isNullOrEmpty()) source.settings.put("headers", headers)
                source
            }
            app.config.saveSources(restored)
        }
        backup.optJSONObject("screen")?.let { screen ->
            if (screen.has("idleSeconds")) app.prefs.idleSeconds = screen.getInt("idleSeconds").coerceIn(0, 86_400)
            runCatching { SleepMode.valueOf(screen.getString("sleepMode")) }.onSuccess { app.prefs.sleepMode = it }
            if (screen.has("kiosk")) app.prefs.kiosk = screen.getBoolean("kiosk")
            if (screen.has("brightness")) {
                val brightness = screen.getInt("brightness").coerceIn(-1, 100)
                main.post { app.screen.setBrightness(brightness) }
            }
            main.post(app.screen::restartIdleTimer)
        }
        backup.optJSONObject("layout")?.let {
            var layout = Layout.fromJson(it)
            backup.optString("backgroundImage").takeIf { data -> data.isNotEmpty() }?.let { data ->
                val name = app.backgrounds.save(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
                layout = layout.copy(background = layout.background.copy(image = name))
            }
            report = runBlocking { LayoutCompiler(app).compile(layout) }
            app.config.saveLayout(layout)
        }
        return config().apply { report?.let { put("report", it.toJson()) } }
    }

    // Home Assistant (the one connection) and each source: settings, the entity ids its fields
    // become, and how it's doing.
    private fun sourcesJson(): JSONObject {
        val status = app.sources.status.value
        val ha = app.config.ha.value
        val sources = app.config.sources.value.map { source ->
            source.toJson()
                .put("status", status[source.id] ?: if (source.enabled) "Starting…" else "Off")
                .put("entities", JSONArray(source.fields.map { "src.${source.id}.${source.fieldId(it)}" }))
        }
        return JSONObject()
            .put("ha", JSONObject().put("enabled", ha.enabled).put("configured", ha.configured).put("status", app.ha.status.value))
            .put("sources", JSONArray(sources))
    }

    // The stream names go2rtc knows, for the camera picker; empty without go2rtc.
    private fun go2rtcStreams(): JSONArray {
        val base = app.config.cameras.value.go2rtcUrl.trim().trimEnd('/')
        if (base.isEmpty()) return JSONArray()
        return runCatching {
            val connection = (URL("$base/api/streams").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
            }
            val streams = connection.inputStream.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
            JSONArray(streams.keys().asSequence().sorted().toList())
        }.getOrElse { throw BadRequest("go2rtc not reachable at $base: ${it.message}") }
    }

    private fun static(path: String): Response {
        val file = if (path == "/") "index.html" else path.removePrefix("/")
        if (".." in file) return json(404, error("Not found"))
        return try {
            val bytes = app.assets.open("web/$file").use { it.readBytes() }
            Response(200, CONTENT_TYPES[file.substringAfterLast('.')] ?: "application/octet-stream", bytes)
        } catch (e: FileNotFoundException) {
            json(404, error("Not found"))
        }
    }

    private fun authorized(header: String) =
        MessageDigest.isEqual(header.toByteArray(), "Bearer ${app.prefs.token}".toByteArray())

    private fun error(message: String) = JSONObject().put("error", message)

    private fun json(status: Int, body: Any) =
        Response(status, "application/json; charset=utf-8", body.toString().toByteArray())

    companion object {
        const val PORT = 2323
        private const val TAG = "WebServer"
        // Background pictures are the largest uploads.
        private const val MAX_BODY = 16 shl 20
        private val CONTROL_PATHS = setOf("/state", "/wake", "/sleep")
        private val REASONS = mapOf(
            200 to "OK", 400 to "Bad Request", 401 to "Unauthorized",
            404 to "Not Found", 405 to "Method Not Allowed", 500 to "Internal Server Error",
        )
        private val CONTENT_TYPES = mapOf(
            "html" to "text/html; charset=utf-8",
            "js" to "text/javascript; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "svg" to "image/svg+xml",
            "png" to "image/png",
        )
    }
}
