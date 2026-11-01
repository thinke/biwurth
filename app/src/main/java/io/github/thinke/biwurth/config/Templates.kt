package io.github.thinke.biwurth.config

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Page templates: ready pages to start from. The app's own (assets/templates/<name>.json, id
 * builtin/<name>) and those installed themes bring (<theme>/<name>). A template is
 * {"name", "description", "tiles": [...]}: tiles as in a layout, positions optional, entities
 * usually left for whoever uses it to fill in (the layout check lists what's missing).
 */
class Templates(private val context: Context, private val themes: ThemeStore) {
    private val builtIn: List<JSONObject> by lazy {
        context.assets.list("templates").orEmpty().filter { it.endsWith(".json") }.sorted().map { file ->
            JSONObject(context.assets.open("templates/$file").use { it.readBytes().toString(Charsets.UTF_8) })
                .put("id", "builtin/${file.removeSuffix(".json")}")
        }
    }

    /** Every template: the app's, then each theme's, with where it comes from. */
    fun all(): List<JSONObject> =
        builtIn.map { JSONObject(it.toString()).put("source", "biwurth") } +
            themes.themes.value.values.sortedBy { it.name }.flatMap { theme ->
                theme.templates.map { JSONObject(it.toString()).put("source", theme.name).put("theme", theme.id) }
            }

    fun find(id: String): JSONObject? = all().firstOrNull { it.optString("id") == id }

    /** A new page from [template] for [layout]'s grid: fresh ids, tiles without a position placed. */
    fun page(template: JSONObject, layout: Layout, name: String?): Page {
        val tiles = template.optJSONArray("tiles") ?: JSONArray()
        val fresh = (0 until tiles.length()).mapNotNull { tiles.optJSONObject(it) }.map { JSONObject(it.toString()).apply { remove("id") } }
        return Page(
            id = UUID.randomUUID().toString().take(8),
            name = name?.takeIf { it.isNotBlank() } ?: template.optString("name").ifBlank { "Page ${layout.pages.size + 1}" },
            tiles = Layout.tilesFromJson(fresh, layout.columns, layout.rows),
        )
    }

    fun summary(template: JSONObject): JSONObject = JSONObject()
        .put("id", template.optString("id"))
        .put("name", template.optString("name"))
        .put("description", template.optString("description"))
        .put("source", template.optString("source"))
        .put("tiles", template.optJSONArray("tiles")?.length() ?: 0)
        .apply { template.optString("theme").takeIf { it.isNotEmpty() }?.let { put("theme", it) } }
}
