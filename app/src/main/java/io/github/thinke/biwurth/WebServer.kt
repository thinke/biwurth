package io.github.thinke.biwurth

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import io.github.thinke.biwurth.config.CameraSettings
import io.github.thinke.biwurth.config.Background
import io.github.thinke.biwurth.config.Layout
import io.github.thinke.biwurth.config.MqttSettings
import io.github.thinke.biwurth.config.Orientation
import io.github.thinke.biwurth.config.Page
import io.github.thinke.biwurth.config.SourceConfig
import io.github.thinke.biwurth.config.ThemeSettings
import io.github.thinke.biwurth.config.ThemeStore
import io.github.thinke.biwurth.config.TileTypes
import io.github.thinke.biwurth.config.Templates
import androidx.compose.ui.graphics.toArgb
import io.github.thinke.biwurth.ui.CameraStatus
import io.github.thinke.biwurth.ui.cameraTilesOnPanel
import io.github.thinke.biwurth.ui.PanelSnapshot
import io.github.thinke.biwurth.ui.PanelTheme
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
    private class NotFound(message: String) : Exception(message)

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
        } catch (e: NotFound) {
            json(404, JSONObject().put("error", e.message))
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
        if (!authorized(request.headers["authorization"].orEmpty())) {
            return json(401, error("Wrong or missing key").put("help", "/llms.txt"))
        }

        val screen = app.screen
        // Background pictures for the website's preview: /api/background/image/<file name>.
        // A source's telemetry as CSV: /api/telemetry/<source id>?days=N.
        if (request.method == "GET" && request.path.startsWith("/api/telemetry/")) {
            val id = request.path.substringAfterLast('/')
            if (app.config.sources.value.none { it.id == id }) return json(404, error("No such source"))
            val days = request.query.split('&').firstOrNull { it.startsWith("days=") }?.substringAfter('=')?.toIntOrNull() ?: 7
            return Response(200, "text/csv; charset=utf-8", app.sources.telemetry.csv(id, days.coerceIn(1, 3650)).toByteArray())
        }
        if (request.method == "GET" && request.path.startsWith("/api/picture/")) {
            val file = app.pictures.file(request.path.substringAfterLast('/')) ?: return json(404, error("Not found"))
            val type = when (file.extension) { "png" -> "image/png"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"; else -> "image/jpeg" }
            return Response(200, type, file.readBytes())
        }
        if (request.method == "GET" && request.path.startsWith("/api/background/image/")) {
            val file = app.backgrounds.file(request.path.substringAfterLast('/')) ?: return json(404, error("Not found"))
            val type = when (file.extension) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
            return Response(200, type, file.readBytes())
        }
        if (request.path.startsWith("/api/layout/pages")) return pageRoute(request)
        if (request.path == "/api/themes" || request.path.startsWith("/api/themes/")) return themeRoute(request)
        if (request.path == "/api/templates" || request.path.startsWith("/api/templates/")) return templateRoute(request)
        if (request.path == "/api/screenshot" && request.method in setOf("GET", "POST")) return screenshot(request)
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
            "GET /api/schema" -> json(200, schema())
            "POST /api/benchmark" -> {
                // ?seconds=5-60&mode=idle|full|swipe&warm=1: see Benchmark.
                val params = params(request.query)
                val mode = runCatching { Benchmark.Mode.valueOf(params["mode"].orEmpty().uppercase()) }.getOrDefault(Benchmark.Mode.IDLE)
                val seconds = (params["seconds"]?.toIntOrNull() ?: 15).coerceIn(5, 60)
                val result = runCatching { runBlocking { Benchmark.run(app, seconds, mode, params["warm"] in setOf("1", "true")) } }
                    .getOrElse { throw BadRequest(it.message ?: "Benchmark failed") }
                json(200, result)
            }
            "GET /api/layout" -> json(200, JSONObject().put("layout", app.config.layout.value.toJson()))
            "POST /api/layout/check" -> {
                // Checked only: nothing is saved or preloaded. The layout comes back as it would be
                // saved, with the positions of tiles that had none.
                val layout = Layout.fromJson(JSONObject(request.body))
                val report = runBlocking { LayoutCompiler(app).compile(layout, preload = false) }
                json(200, JSONObject().put("layout", layout.toJson()).put("report", report.toJson()))
            }
            "GET /api/layout/history" -> json(
                200,
                JSONObject().put(
                    "history",
                    JSONArray(
                        app.config.layoutHistory().map { (replaced, layout) ->
                            JSONObject().put("replaced", java.time.Instant.ofEpochMilli(replaced).toString())
                                .put("pages", JSONArray(layout.pages.map { "${it.name} (${it.tiles.size} tiles)" }))
                        },
                    ),
                ),
            )
            "POST /api/layout/undo" -> {
                val layout = app.config.undoLayout() ?: throw BadRequest("No earlier layout to go back to")
                json(200, JSONObject().put("layout", layout.toJson()))
            }
            "GET /api/status" -> json(200, status())
            "GET /api/entities" -> json(200, entities(request.query))
            "PUT /api/layout" -> {
                // Compile first (check and preload), then switch: the new layout appears complete.
                val layout = Layout.fromJson(JSONObject(request.body))
                val report = runBlocking { LayoutCompiler(app).compile(layout) }
                app.config.saveLayout(layout)
                json(200, JSONObject().put("layout", layout.toJson()).put("report", report.toJson()))
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
            "PUT /api/picture" -> {
                // A picture for an Empty space tile; the ones layouts use (saved or undoable) are kept.
                val name = runCatching { app.pictures.save(request.bytes, usedPictures()) }.getOrElse { throw BadRequest(it.message ?: "Bad picture") }
                json(200, JSONObject().put("picture", name))
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
            "PUT /api/sources" -> {
                app.config.saveSources(keepSecrets(SourceConfig.listFromJson(JSONArray(request.body))))
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
                if (body.has("showFps")) {
                    app.prefs.showFps = body.getBoolean("showFps")
                    FrameStats.showCounter.value = app.prefs.showFps
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
        val mqtt = app.config.mqtt.value
        return JSONObject()
            .put("version", BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) " (debug)" else "")
            .put("layout", app.config.layout.value.toJson())
            .put("mqtt", mqtt.toJson().put("password", "").put("passwordSet", mqtt.password.isNotEmpty()))
            .put("cameras", app.config.cameras.value.toJson())
            .put(
                "screen",
                JSONObject()
                    .put("idleSeconds", app.prefs.idleSeconds)
                    .put("sleepMode", app.prefs.sleepMode.name)
                    .put("brightness", app.prefs.brightness)
                    .put("showFps", app.prefs.showFps)
                    .put("adminActive", Kiosk.isAdmin(app)),
            )
            .put("display", display())
    }

    // The panel's native size; the editor turns it to match the chosen orientation.
    private fun display(): JSONObject {
        val mode = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).mode
        return JSONObject().put("width", mode.physicalWidth).put("height", mode.physicalHeight)
            .put("densityDpi", app.resources.displayMetrics.densityDpi)
    }

    private fun status() = JSONObject()
        .put("screen", app.screen.state.value.name.lowercase())
        .put("page", app.screen.page.value)
        .put("ha", haSummary())
        .put("mqtt", app.mqtt.status.value)
        .put("entities", app.sources.homeAssistants.value.values.sumOf { it.entities.value.size })
        .put("ownEntities", app.sources.entities.value.size)
        .put("memory", memory())
        .put(
            "cameras",
            JSONObject()
                .put("tiles", cameraTilesOnPanel())
                .put("maxLive", app.config.cameras.value.maxLive)
                .put("live", minOf(cameraTilesOnPanel(), app.config.cameras.value.maxLive)),
        )

    // The panel's own memory and how much the tablet has left, in MB: PSS counts the shared parts
    // fairly, so it's the figure to watch over time.
    private fun memory(): JSONObject {
        val own = android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo)
        val device = android.app.ActivityManager.MemoryInfo()
        app.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(device)
        val runtime = Runtime.getRuntime()
        fun mb(bytes: Long) = bytes / (1 shl 20)
        return JSONObject()
            .put("appMb", own.totalPss / 1024)
            .put("javaHeapMb", mb(runtime.totalMemory() - runtime.freeMemory()))
            .put("javaHeapMaxMb", mb(runtime.maxMemory()))
            .put("nativeHeapMb", mb(android.os.Debug.getNativeHeapAllocatedSize()))
            .put("deviceAvailableMb", mb(device.availMem))
            .put("deviceTotalMb", mb(device.totalMem))
            .put("deviceLow", device.lowMemory)
            // Close to full: Android says so, under 15 % is left, or our Java heap nears its limit.
            .put(
                "warning",
                device.lowMemory || device.availMem < device.totalMem * 0.15 ||
                    runtime.totalMemory() - runtime.freeMemory() > runtime.maxMemory() * 0.85,
            )
            .put("suggestedMaxLive", suggestedMaxLive(device.totalMem))
    }

    // Live streams this tablet's RAM carries comfortably, each a hardware decoder and its frames
    // (roughly 20-60 MB a stream), next to Android and the panel.
    private fun suggestedMaxLive(totalBytes: Long): Int = when {
        totalBytes <= 2L shl 30 -> 1
        totalBytes <= 3L shl 30 -> 2
        totalBytes <= 4L shl 30 -> 4
        else -> 6
    }

    // "Connected" for one, "2 of 3 connected" for several, "None" without any.
    private fun haSummary(): String {
        val all = app.config.sources.value.filter { it.isHomeAssistant && it.enabled }
        if (all.isEmpty()) return "None"
        val connections = app.sources.homeAssistants.value
        if (all.size == 1) return connections[all[0].id]?.status?.value ?: "Starting…"
        return "${all.count { connections[it.id]?.connected?.value == true }} of ${all.size} connected"
    }

    // Saving sources from the website: a Home Assistant without a token keeps the one it had,
    // since the website never gets tokens back.
    private fun keepSecrets(incoming: List<SourceConfig>): List<SourceConfig> {
        val current = app.config.sources.value.associateBy { it.id }
        return incoming.onEach { source ->
            val old = current[source.id]?.settings ?: return@onEach
            if (source.isHomeAssistant && source.settings.optString("token").isBlank()) source.settings.put("token", old.optString("token"))
            if (!source.settings.has("headers") && old.has("headers")) source.settings.put("headers", old.optString("headers"))
        }
    }

    /**
     * Every entity tiles can show, with what kind of tile suits each. The query can narrow it:
     * q=<words> (all in the id or name, any case) and domain=<domain>.
     */
    private fun entities(query: String): JSONArray {
        val params = params(query)
        val words = params["q"].orEmpty().lowercase().split(' ').filter { it.isNotBlank() }
        val domain = params["domain"]?.takeIf { it.isNotBlank() }
        return JSONArray(
            app.hub.entities.value.values.sortedBy { it.id }
                .filter { e -> words.all { it in e.id.lowercase() || it in e.name.lowercase() } }
                .filter { e -> domain == null || TileTypes.domainOf(e.id) == domain }
                .map {
                    val numeric = it.state.toDoubleOrNull() != null
                    JSONObject().put("id", it.id).put("name", it.name).put("state", it.state).put("unit", it.unit)
                        .put("domain", TileTypes.domainOf(it.id))
                        .put("deviceClass", it.attributes.optString("device_class"))
                        .put("numeric", numeric)
                        .put("tiles", JSONArray(TileTypes.suitableFor(it.id, numeric)))
                },
        )
    }

    private fun params(query: String): Map<String, String> = query.split('&').filter { '=' in it }.associate {
        java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    /** What a layout can hold and how it's drawn: for whoever writes one without the editor. */
    private fun schema(): JSONObject = JSONObject()
        .put(
            "grid",
            JSONObject()
                .put("columns", app.config.layout.value.columns)
                .put("rows", app.config.layout.value.rows)
                .put("note", "Tiles sit on whole cells: x 0..columns-w, y 0..rows-h. The grid stretches over the whole screen."),
        )
        .put("display", display())
        .put("orientations", JSONArray(Orientation.entries.map { it.key }))
        .put("themes", JSONArray(PanelTheme.PRESETS.keys.toList()))
        .put("fonts", JSONArray(listOf("inter", "system")))
        .put(
            "theme",
            JSONObject()
                .put("fields", "preset, accent (\"#rrggbb\" or \"\"), font, textScale 0.5-2, tileOpacity 0-100, gap 0-32 (dp between cards, default 8), animations (true/false)"),
        )
        .put(
            "background",
            JSONObject().put("types", JSONArray(Background.TYPES.toList())).put("modes", JSONArray(Background.MODES.toList()))
                .put("note", "colors: 2-3 \"#rrggbb\" for a gradient; image: a file name from PUT /api/background/image; dim: 0-90 %."),
        )
        .put(
            "tile",
            JSONObject()
                .put("fields", "id (keep it when changing a tile), type, x, y, w, h, props (all values strings)")
                .put("autoPlacement", "Leave out x and y to put a tile in the first free spot, left to right then top to bottom, after the tiles that have a position. Leave out w and h for the type's defaultSize."),
        )
        .put("tileTypes", TileTypes.toJson())

    // ---- Pages and tiles one at a time -----------------------------------------------------------
    //
    //   POST   /api/layout/pages                        {name, tiles?}   add a page at the end
    //   PATCH  /api/layout/pages/<page>                 {name}           rename it
    //   DELETE /api/layout/pages/<page>                                  remove it
    //   POST   /api/layout/pages/<page>/tiles           {type, …}        add a tile (placed if no x/y)
    //   PATCH  /api/layout/pages/<page>/tiles/<tile>    {x?, …, props?}  change it; props merge, "" or null removes one
    //   DELETE /api/layout/pages/<page>/tiles/<tile>                     remove it
    //
    // <page> is a page id or its number from 0. Each saves (undo goes back) and returns the layout and its report.
    private fun pageRoute(request: Request): Response {
        val parts = request.path.removePrefix("/api/layout/pages").trim('/').split('/').filter { it.isNotEmpty() }
        val layout = app.config.layout.value
        if (parts.isEmpty()) {
            if (request.method != "POST") throw NotFound("Not found")
            val body = JSONObject(request.body.ifBlank { "{}" })
            val tiles = body.optJSONArray("tiles") ?: JSONArray()
            val page = Page(
                id = body.optString("id").ifBlank { java.util.UUID.randomUUID().toString().take(8) },
                name = body.optString("name").ifBlank { "Page ${layout.pages.size + 1}" },
                tiles = Layout.tilesFromJson((0 until tiles.length()).mapNotNull { tiles.optJSONObject(it) }, layout.columns, layout.rows),
            )
            if (layout.pages.any { it.id == page.id }) throw BadRequest("A page with id ${page.id} exists")
            return saveLayout(layout.copy(pages = layout.pages + page))
        }
        val index = layout.pages.indexOfFirst { it.id == parts[0] }.takeIf { it >= 0 }
            ?: parts[0].toIntOrNull()?.takeIf { it in layout.pages.indices }
            ?: throw NotFound("No page ${parts[0]}")
        val page = layout.pages[index]
        fun withPage(changed: Page?) = layout.copy(pages = layout.pages.toMutableList().apply { if (changed == null) removeAt(index) else set(index, changed) })

        if (parts.size == 1) {
            return when (request.method) {
                "PATCH" -> saveLayout(withPage(page.copy(name = JSONObject(request.body).optString("name").ifBlank { page.name })))
                "DELETE" -> if (layout.pages.size == 1) throw BadRequest("The last page can't go") else saveLayout(withPage(null))
                else -> json(405, error("Method not allowed"))
            }
        }
        if (parts[1] != "tiles") throw NotFound("Not found")
        if (parts.size == 2) {
            if (request.method != "POST") return json(405, error("Method not allowed"))
            val body = JSONObject(request.body)
            if (body.optString("id").let { id -> id.isNotEmpty() && page.tiles.any { it.id == id } }) throw BadRequest("A tile with id ${body.optString("id")} exists")
            val tile = Layout.tileFromJson(body, layout.columns, layout.rows) ?: throw BadRequest("Unknown tile type \"${body.optString("type")}\"")
            val placed = if (body.has("x") && body.has("y")) {
                tile
            } else {
                Layout.freeSpot(page.tiles, tile.w, tile.h, layout.columns, layout.rows)?.let { tile.copy(x = it.first, y = it.second) }
                    ?: throw BadRequest("No free ${tile.w}×${tile.h} spot on ${page.name}: give x and y, or a smaller size")
            }
            return saveLayout(withPage(page.copy(tiles = page.tiles + placed)))
        }
        val tileIndex = page.tiles.indexOfFirst { it.id == parts[2] }.takeIf { it >= 0 } ?: throw NotFound("No tile ${parts[2]} on ${page.name}")
        val tile = page.tiles[tileIndex]
        return when (request.method) {
            "DELETE" -> saveLayout(withPage(page.copy(tiles = page.tiles.filterIndexed { i, _ -> i != tileIndex })))
            "PATCH" -> {
                val body = JSONObject(request.body)
                val merged = JSONObject().put("id", tile.id).put("type", body.optString("type").ifBlank { tile.type })
                    .put("x", body.optInt("x", tile.x)).put("y", body.optInt("y", tile.y))
                    .put("w", body.optInt("w", tile.w)).put("h", body.optInt("h", tile.h))
                val props = JSONObject(tile.props)
                body.optJSONObject("props")?.let { changes ->
                    for (key in changes.keys()) {
                        val value = changes.opt(key)
                        if (value == null || value == JSONObject.NULL || value.toString().isEmpty()) props.remove(key) else props.put(key, value.toString())
                    }
                }
                merged.put("props", props)
                val changed = Layout.tileFromJson(merged, layout.columns, layout.rows) ?: throw BadRequest("Unknown tile type \"${merged.optString("type")}\"")
                saveLayout(withPage(page.copy(tiles = page.tiles.toMutableList().apply { set(tileIndex, changed) })))
            }
            else -> json(405, error("Method not allowed"))
        }
    }

    // ---- Themes and page templates ----------------------------------------------------------------
    //
    //   GET    /api/themes                  installed themes (and the built-in colour presets)
    //   POST   /api/themes                  body: a theme zip; installs it (replacing one with its id)
    //   GET    /api/themes/<id>.zip         an installed theme's package
    //   GET    /api/themes/current.zip      the current look (and pages, as templates) as a new theme;
    //                                       ?id=<id>&name=<name>
    //   POST   /api/themes/<id>/apply       puts the theme's look on the layout (saved; undo goes back)
    //   DELETE /api/themes/<id>             uninstalls it (not the app's own)
    //   GET    /api/templates               page templates: the app's and installed themes'
    //   GET    /api/templates/<id>          one template, tiles and all
    //   POST   /api/templates/page          {template: <id>, name?}: adds a page made from it
    private val templates by lazy { Templates(app, app.themes) }

    private fun themeRoute(request: Request): Response {
        val rest = request.path.removePrefix("/api/themes").trim('/')
        return when {
            rest.isEmpty() && request.method == "GET" -> json(
                200,
                JSONObject()
                    .put("presets", JSONArray(ThemeStore.BUILT_IN_PRESETS.toList()))
                    .put("themes", JSONArray(app.themes.themes.value.values.sortedBy { it.name }.map(::themeJson))),
            )
            rest.isEmpty() && request.method == "POST" -> {
                val theme = runCatching { app.themes.install(request.bytes) }
                    .getOrElse { throw BadRequest(if (it is java.util.zip.ZipException) "Not a zip file" else it.message ?: "Bad theme") }
                EventLog.i("theme", "Installed the theme ${theme.name}")
                json(200, themeJson(theme))
            }
            rest == "current.zip" && request.method == "GET" -> {
                val params = params(request.query)
                val id = params["id"]?.takeIf { ThemeStore.ID.matches(it) } ?: "my-theme"
                Response(200, "application/zip", exportTheme(id, params["name"]?.ifBlank { null } ?: "My theme"))
            }
            rest.endsWith(".zip") && request.method == "GET" ->
                app.themes.packageZip(rest.removeSuffix(".zip"))?.let { Response(200, "application/zip", it) } ?: throw NotFound("No theme ${rest.removeSuffix(".zip")}")
            rest.endsWith("/apply") && request.method == "POST" -> {
                val theme = app.themes.themes.value[rest.removeSuffix("/apply")] ?: throw NotFound("No theme ${rest.removeSuffix("/apply")}")
                val layout = app.config.layout.value
                saveLayout(layout.copy(theme = app.themes.settings(theme), background = app.themes.background(theme) ?: layout.background))
            }
            request.method == "DELETE" && '/' !in rest -> {
                runCatching { app.themes.delete(rest) }.getOrElse {
                    if (it is NoSuchElementException) throw NotFound(it.message ?: "No theme") else throw BadRequest(it.message ?: "Can't delete")
                }
                json(200, JSONObject().put("deleted", rest))
            }
            else -> throw NotFound("Not found")
        }
    }

    private fun themeJson(theme: ThemeStore.Theme): JSONObject = JSONObject()
        .put("id", theme.id)
        .put("name", theme.name)
        .put("description", theme.json.optString("description"))
        .put("builtIn", theme.builtIn)
        .put("colors", paletteJson(PanelTheme.from(ThemeSettings(preset = theme.id), app.themes.themes.value)))
        .put("settings", app.themes.settings(theme).toJson())
        .apply { app.themes.background(theme)?.let { put("background", it.toJson()) } }
        .put("pictures", JSONArray(theme.pictures))
        .put("templates", JSONArray(theme.templates.map { JSONObject().put("id", it.optString("id")).put("name", it.optString("name")) }))

    private fun paletteJson(p: PanelTheme): JSONObject {
        fun hex(c: androidx.compose.ui.graphics.Color) = String.format("#%06x", c.toArgb() and 0xFFFFFF)
        return JSONObject().put("background", hex(p.background)).put("card", hex(p.card)).put("text", hex(p.text))
            .put("muted", hex(p.muted)).put("accent", hex(p.accent)).put("onAccent", hex(p.onAccent))
    }

    /** The current look as a theme package, its pictures and background with it, and each page as a template. */
    private fun exportTheme(id: String, name: String): ByteArray {
        val layout = app.config.layout.value
        val palette = PanelTheme.from(layout.theme, app.themes.themes.value)
        val files = linkedMapOf<String, ByteArray>()
        val manifest = layout.theme.toJson().apply { remove("preset"); remove("accent") }
            .put("id", id).put("name", name).put("description", "Made from ${layout.pages.size} page(s) on biwurth ${BuildConfig.VERSION_NAME}.")
            .put("colors", paletteJson(palette))
        val background = layout.background.toJson()
        layout.background.image?.let(app.backgrounds::file)?.let { file ->
            files["backgrounds/${file.name}"] = file.readBytes()
            background.put("image", "backgrounds/${file.name}")
        } ?: background.put("image", "")
        manifest.put("background", background)
        files["theme.json"] = manifest.toString(2).toByteArray()
        layout.pages.forEachIndexed { i, page ->
            val tiles = JSONArray(
                page.tiles.map { tile ->
                    with(Layout) { tile.toJson() }.apply {
                        remove("id")
                        val props = getJSONObject("props")
                        props.optString("picture").takeIf { it.isNotEmpty() }?.let { picture ->
                            app.pictures.file(picture)?.let { files["pictures/${it.name}"] = it.readBytes() }
                            props.put("picture", "pictures/$picture")
                        }
                    }
                },
            )
            files["templates/page-${i + 1}.json"] = JSONObject().put("name", page.name).put("tiles", tiles).toString(2).toByteArray()
        }
        return java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                files.forEach { (path, bytes) ->
                    zip.putNextEntry(java.util.zip.ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun templateRoute(request: Request): Response {
        val rest = request.path.removePrefix("/api/templates").trim('/')
        return when {
            rest.isEmpty() && request.method == "GET" -> json(200, JSONObject().put("templates", JSONArray(templates.all().map(templates::summary))))
            rest == "page" && request.method == "POST" -> {
                val body = JSONObject(request.body)
                val template = templates.find(body.optString("template")) ?: throw NotFound("No template ${body.optString("template")}")
                val layout = app.config.layout.value
                saveLayout(layout.copy(pages = layout.pages + templates.page(template, layout, body.optString("name"))))
            }
            request.method == "GET" -> json(200, templates.find(rest) ?: throw NotFound("No template $rest"))
            else -> throw NotFound("Not found")
        }
    }

    // Pictures the saved layout and the undo history use.
    private fun usedPictures(): Set<String> =
        (listOf(app.config.layout.value) + app.config.layoutHistory().map { it.second })
            .flatMap { layout -> layout.pages.flatMap { page -> page.tiles.mapNotNull { it.prop("picture") } } }.toSet()

    private fun saveLayout(layout: Layout): Response {
        val report = runBlocking { LayoutCompiler(app).compile(layout) }
        app.config.saveLayout(layout)
        return json(200, JSONObject().put("layout", layout.toJson()).put("report", report.toJson()))
    }

    /**
     * A page as the panel draws it, as PNG (or JPEG with format=jpeg), drawn off-screen. GET: the
     * saved layout. POST: a draft layout in the body, or {"layout": …, "page": …}; it's preloaded,
     * not saved. Query: page=<number> (default 0), scale=0.1-1, settle=<ms to wait for data>.
     */
    private fun screenshot(request: Request): Response {
        val params = params(request.query)
        var pageIndex = params["page"]?.toIntOrNull() ?: 0
        val layout = if (request.method == "POST" && request.body.isNotBlank()) {
            val body = JSONObject(request.body)
            if (body.has("page")) pageIndex = body.getInt("page")
            Layout.fromJson(body.optJSONObject("layout") ?: body).also { runBlocking { LayoutCompiler(app).compile(it) } }
        } else {
            app.config.layout.value
        }
        if (pageIndex !in layout.pages.indices) throw BadRequest("No page $pageIndex: the layout has ${layout.pages.size} (from 0)")
        val jpeg = params["format"] == "jpeg"
        val options = PanelSnapshot.Options(
            scale = params["scale"]?.toFloatOrNull() ?: 1f,
            jpeg = jpeg,
            settleMs = params["settle"]?.toLongOrNull()?.coerceIn(500, 15_000) ?: 2_500L,
        )
        val image = runBlocking { PanelSnapshot.capture(app, layout, pageIndex, options) }
        return Response(200, if (jpeg) "image/jpeg" else "image/png", image)
    }

    /**
     * Everything the website configures, in one file. The Home Assistant token and MQTT password
     * only with [includeSecrets]; the website key never, so a backup can't open this panel.
     */
    private fun backup(includeSecrets: Boolean): JSONObject {
        val mqtt = app.config.mqtt.value
        return JSONObject()
            .put("type", "biwurth-backup")
            .put("version", 1)
            .put("created", java.time.Instant.now().toString())
            .put("layout", app.config.layout.value.toJson())
            .put("mqtt", mqtt.toJson().apply { if (!includeSecrets) remove("password") })
            .put("cameras", app.config.cameras.value.toJson())
            .put(
                "sources",
                // HTTP headers carry API keys: secrets, like the token.
                JSONArray(
                    app.config.sources.value.map { source ->
                        source.toJson().apply {
                            if (!includeSecrets) getJSONObject("settings").apply {
                                remove("headers")
                                remove("token")
                            }
                        }
                    },
                ),
            )
            .apply {
                val image = app.config.layout.value.background.image?.let(app.backgrounds::file)
                if (image != null) {
                    put("backgroundImage", android.util.Base64.encodeToString(image.readBytes(), android.util.Base64.NO_WRAP))
                }
                // Empty space tiles' pictures, under their names, so the layout finds them again.
                val pictures = JSONObject()
                app.config.layout.value.pages.flatMap { it.tiles }.mapNotNull { it.prop("picture") }.toSet().forEach { name ->
                    app.pictures.file(name)?.let { pictures.put(name, android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP)) }
                }
                if (pictures.length() > 0) put("pictures", pictures)
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
        // Backups from before several Home Assistants had one under "ha": make it a source.
        backup.optJSONObject("ha")?.let { ha ->
            if (backup.optJSONArray("sources")?.let { a -> (0 until a.length()).any { a.optJSONObject(it)?.optString("type") == "homeassistant" } } != true) {
                val sources = backup.optJSONArray("sources") ?: JSONArray().also { backup.put("sources", it) }
                val settings = JSONObject().put("url", ha.optString("url")).put("primary", true)
                if (ha.optString("token").isNotEmpty()) settings.put("token", ha.optString("token"))
                val list = JSONArray().put(
                    JSONObject().put("id", "home_assistant").put("name", "Home Assistant").put("type", "homeassistant")
                        .put("enabled", ha.optBoolean("enabled", true)).put("settings", settings),
                )
                for (i in 0 until sources.length()) list.put(sources.get(i))
                backup.put("sources", list)
            }
        }
        backup.optJSONObject("mqtt")?.let { mqtt ->
            if (mqtt.optString("password").isEmpty()) mqtt.put("password", app.config.mqtt.value.password)
            app.config.saveMqtt(MqttSettings.fromJson(mqtt))
        }
        backup.optJSONObject("cameras")?.let { app.config.saveCameras(CameraSettings.fromJson(it)) }
        backup.optJSONArray("sources")?.let { array ->
            // Headers left out of a backup keep the ones this panel has.
            app.config.saveSources(keepSecrets(SourceConfig.listFromJson(array)))
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
        backup.optJSONObject("pictures")?.let { pictures ->
            for (name in pictures.keys()) {
                runCatching { app.pictures.restore(name, android.util.Base64.decode(pictures.getString(name), android.util.Base64.DEFAULT)) }
                    .onFailure { EventLog.w("web", "Skipping picture $name from the backup", it) }
            }
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
        val sources = app.config.sources.value.map { source ->
            source.toJson()
                .put("status", status[source.id] ?: if (source.enabled) "Starting…" else "Off")
                .put("entities", JSONArray(source.fields.map { "src.${source.id}.${source.fieldId(it)}" }))
                .put("primary", source.id == app.sources.primaryHa.value)
                .apply {
                    val (days, bytes) = app.sources.telemetry.usage(source.id)
                    put("telemetryDays", days).put("telemetryBytes", bytes)
                    // The website never gets a token back, only whether one is set.
                    val settings = JSONObject(getJSONObject("settings").toString())
                    if (settings.has("token")) settings.put("tokenSet", settings.optString("token").isNotEmpty()).remove("token")
                    put("settings", settings)
                }
        }
        return JSONObject().put("sources", JSONArray(sources))
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
            "txt" to "text/plain; charset=utf-8",
            "md" to "text/markdown; charset=utf-8",
        )
    }
}
