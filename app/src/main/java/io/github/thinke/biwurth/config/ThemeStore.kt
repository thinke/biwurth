package io.github.thinke.biwurth.config

import android.content.Context
import io.github.thinke.biwurth.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Theme packages: zip files with the panel's look and things to build pages from.
 *
 *     theme.json         {"id", "name", "description", "base": a built-in preset, "colors": {background,
 *                        card, text, muted, accent, onAccent}, "font", "textScale", "tileOpacity",
 *                        "gap", "animations", "cards", "tiles": {radius, padding, borderWidth,
 *                        borderColor, graphLineWidth, graphFill, graphColors, valueWeight},
 *                        "background": {… "image": "backgrounds/x.jpg"}}
 *     pictures/…         SVG, PNG, JPEG or WebP for Empty space tiles
 *     backgrounds/…      background pictures
 *     templates/<name>.json  page templates: {"name", "description", "tiles": [...]}; a tile's
 *                        "picture": "pictures/x.svg" refers to the package's own picture
 *
 * Installing unpacks it to themes/<id>/ and puts its pictures in the picture and background
 * stores as theme-<id>-<file>, which they keep for as long as the theme is installed. The app's
 * own themes (assets/themes/<id>/, same layout) are installed at start.
 */
class ThemeStore(private val context: Context, private val pictures: PictureStore, private val backgrounds: BackgroundStore) {
    class Theme(val id: String, val json: JSONObject, val builtIn: Boolean) {
        val name: String get() = json.optString("name").ifBlank { id }
        val pictures: List<String> get() = json.optJSONArray("pictures").strings()
        val templates: List<JSONObject> get() = json.optJSONArray("templates").objects()
    }

    private val dir = File(context.filesDir, "themes").apply { mkdirs() }
    private val _themes = MutableStateFlow<Map<String, Theme>>(emptyMap())
    val themes: StateFlow<Map<String, Theme>> = _themes

    init {
        installBuiltIn()
        _themes.value = readInstalled()
    }

    /** Installs a theme package; replaces an installed one with the same id. Returns it. */
    @Synchronized
    fun install(zip: ByteArray, builtIn: Boolean = false): Theme {
        val files = unzip(zip)
        val manifest = files["theme.json"]?.let { JSONObject(String(it, Charsets.UTF_8)) } ?: throw IllegalArgumentException("No theme.json in the zip")
        val id = manifest.optString("id").ifBlank { throw IllegalArgumentException("theme.json needs an \"id\"") }
        require(ID.matches(id)) { "Theme id \"$id\": letters, digits and - only, up to 40" }
        require(id !in BUILT_IN_PRESETS) { "\"$id\" is a built-in colour preset's name" }
        val existing = _themes.value[id]
        require(existing == null || existing.builtIn == builtIn || builtIn) { "\"$id\" is one of the app's own themes" }
        remove(id, keepEntry = true)

        // Pictures and backgrounds go to their stores; the manifest learns their stored names.
        val stored = mutableMapOf<String, String>()
        val pictureNames = JSONArray()
        for ((path, bytes) in files) {
            val file = path.substringAfterLast('/')
            when {
                path.startsWith("pictures/") -> {
                    val name = "theme-$id-$file"
                    pictures.restore(name, bytes)
                    stored[path] = name
                    pictureNames.put(name)
                }
                path.startsWith("backgrounds/") -> stored[path] = backgrounds.saveAs("theme-$id-$file", bytes)
            }
        }
        val templates = JSONArray()
        files.filterKeys { it.startsWith("templates/") && it.endsWith(".json") }.toSortedMap().forEach { (path, bytes) ->
            val template = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { throw IllegalArgumentException("$path: ${it.message}") }
            template.put("id", "$id/${path.substringAfterLast('/').removeSuffix(".json")}")
            template.optJSONArray("tiles")?.objects()?.forEach { tile ->
                tile.optJSONObject("props")?.let { props ->
                    props.optString("picture").takeIf { it.isNotEmpty() }?.let { ref -> stored[ref]?.let { props.put("picture", it) } }
                }
            }
            templates.put(template)
        }
        manifest.optJSONObject("background")?.let { bg ->
            bg.optString("image").takeIf { it.isNotEmpty() }?.let { ref -> bg.put("image", stored[ref] ?: "") }
        }
        manifest.put("pictures", pictureNames).put("templates", templates)

        val target = File(dir, id).apply { mkdirs() }
        File(target, "theme.json").writeText(manifest.toString(2))
        File(target, "package.zip").writeBytes(zip)
        if (builtIn) File(target, "builtin").writeText(BUILT_IN_VERSION) else File(target, "builtin").delete()
        val theme = Theme(id, manifest, builtIn)
        _themes.value = _themes.value + (id to theme)
        return theme
    }

    /** Uninstalls a theme and its pictures. The app's own themes can't go. */
    @Synchronized
    fun delete(id: String) {
        val theme = _themes.value[id] ?: throw NoSuchElementException("No theme $id")
        require(!theme.builtIn) { "\"$id\" is one of the app's own themes" }
        remove(id, keepEntry = false)
    }

    /** The package as installed, to download and edit. */
    fun packageZip(id: String): ByteArray? = File(File(dir, id), "package.zip").takeIf { it.isFile }?.readBytes()

    /** Every picture an installed theme owns: never pruned while the theme is here. */
    fun ownedPictures(): Set<String> = _themes.value.values.flatMap { it.pictures }.toSet()

    /** Theme settings for a layout from [theme]: the look, with the theme's colours as its preset. */
    fun settings(theme: Theme): ThemeSettings {
        val json = theme.json
        return ThemeSettings(
            preset = theme.id,
            accent = null,
            font = if (json.optString("font") == "system") "system" else "inter",
            textScale = json.optDouble("textScale", 1.0).toFloat().coerceIn(0.5f, 2f),
            tileOpacity = json.optInt("tileOpacity", 100).coerceIn(0, 100),
            gap = json.optInt("gap", 8).coerceIn(0, 32),
            animations = json.optBoolean("animations", true),
            cards = json.optBoolean("cards", true),
            tiles = TileStyle.fromJson(json.optJSONObject("tiles")),
        )
    }

    fun background(theme: Theme): Background? = theme.json.optJSONObject("background")?.let(Background::fromJson)

    private fun remove(id: String, keepEntry: Boolean) {
        File(dir, id).deleteRecursively()
        pictures.deleteTheme(id)
        backgrounds.deleteTheme(id)
        if (!keepEntry) _themes.value = _themes.value - id
    }

    private fun readInstalled(): Map<String, Theme> = dir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { folder ->
        runCatching { Theme(folder.name, JSONObject(File(folder, "theme.json").readText()), File(folder, "builtin").isFile) }
            .onFailure { EventLog.w("theme", "Skipping the theme in ${folder.name}", it) }.getOrNull()
    }.associateBy { it.id }

    // The app's own themes: (re)installed when missing or from an older app version.
    private fun installBuiltIn() {
        val names = context.assets.list("themes").orEmpty()
        for (name in names) {
            val marker = File(File(dir, name), "builtin")
            if (marker.isFile && marker.readText() == BUILT_IN_VERSION) continue
            runCatching { install(zipAsset("themes/$name"), builtIn = true) }
                .onFailure { EventLog.w("theme", "Couldn't install the built-in theme $name", it) }
        }
    }

    private fun zipAsset(path: String): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            fun add(asset: String, entry: String) {
                val children = context.assets.list(asset).orEmpty()
                if (children.isEmpty()) {
                    zip.putNextEntry(ZipEntry(entry))
                    context.assets.open(asset).use { it.copyTo(zip) }
                    zip.closeEntry()
                } else {
                    children.forEach { add("$asset/$it", if (entry.isEmpty()) it else "$entry/$it") }
                }
            }
            add(path, "")
        }
    }.toByteArray()

    companion object {
        val ID = Regex("[a-z0-9][a-z0-9-]{0,39}")
        val BUILT_IN_PRESETS = setOf("dark", "graphite", "nord", "forest", "light")
        // Bump when the app's own themes change, so tablets reinstall them.
        private const val BUILT_IN_VERSION = "7"
        private const val MAX_ENTRIES = 200
        private const val MAX_BYTES = 20 shl 20

        /** Reads a zip's files, refusing paths outside it and anything past the size limits. */
        fun unzip(zip: ByteArray): Map<String, ByteArray> {
            val files = mutableMapOf<String, ByteArray>()
            var total = 0L
            ZipInputStream(ByteArrayInputStream(zip)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (entry.isDirectory) continue
                    // A theme zipped with its folder: drop that first folder.
                    val path = entry.name.replace('\\', '/').trimStart('/')
                    require(".." !in path.split('/')) { "Bad path in the zip: ${entry.name}" }
                    require(files.size < MAX_ENTRIES) { "Too many files in the zip" }
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16_384)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_BYTES) { "The zip unpacks to more than ${MAX_BYTES shr 20} MB" }
                        out.write(buffer, 0, n)
                    }
                    files[path] = out.toByteArray()
                }
            }
            if ("theme.json" !in files) {
                val root = files.keys.firstOrNull { it.endsWith("/theme.json") && it.count { c -> c == '/' } == 1 }?.substringBefore('/')
                if (root != null) return files.filterKeys { it.startsWith("$root/") }.mapKeys { it.key.removePrefix("$root/") }
            }
            return files
        }

        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
