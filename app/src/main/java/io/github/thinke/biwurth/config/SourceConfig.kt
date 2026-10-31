package io.github.thinke.biwurth.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * One of biwurth's own data sources (HTTP/JSON, MQTT topics, status checks, Modbus TCP): its
 * type, name and settings, and the [fields] it turns into entities `src.<id>.<field>`. The
 * settings and fields stay JSON, so each source type reads what it needs.
 */
data class SourceConfig(
    val id: String,
    val name: String,
    val type: String,
    val enabled: Boolean,
    val settings: JSONObject,
    val fields: List<JSONObject>,
) {
    /** A field's entity id part, from its name. */
    fun fieldId(field: JSONObject) = slug(field.optString("name"))

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type).put("enabled", enabled)
        .put("settings", settings).put("fields", JSONArray(fields))

    companion object {
        val TYPES = setOf("http", "mqtt", "status", "modbus")

        /** Entity id parts: lower case letters, digits and underscores. */
        fun slug(text: String) = text.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

        fun listFromJson(array: JSONArray): List<SourceConfig> =
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::fromJson) }
                .distinctBy { it.id }

        private fun fromJson(json: JSONObject): SourceConfig? {
            val type = json.optString("type").takeIf { it in TYPES } ?: return null
            val name = json.optString("name").trim().ifEmpty { return null }
            val fields = json.optJSONArray("fields") ?: JSONArray()
            return SourceConfig(
                id = slug(json.optString("id").ifBlank { name }).ifEmpty { return null },
                name = name,
                type = type,
                enabled = json.optBoolean("enabled", true),
                settings = json.optJSONObject("settings") ?: JSONObject(),
                fields = (0 until fields.length()).mapNotNull { fields.optJSONObject(it) }
                    .filter { slug(it.optString("name")).isNotEmpty() },
            )
        }
    }
}
