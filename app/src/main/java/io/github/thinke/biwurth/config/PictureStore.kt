package io.github.thinke.biwurth.config

import android.content.Context
import java.io.File

/**
 * Pictures shown on Empty space tiles, uploaded from the website: JPEG, PNG, WebP or SVG. A tile
 * refers to one by file name. Pictures no layout uses are let go, keeping the newest few for a
 * layout being edited but not yet saved.
 */
class PictureStore(context: Context) {
    private val dir = File(context.filesDir, "pictures").apply { mkdirs() }

    fun file(name: String): File? = File(dir, name).takeIf { NAME.matches(name) && it.isFile && it.parentFile == dir }

    /** Stores a picture and returns its file name, after checking what it is. */
    fun save(bytes: ByteArray, keep: Set<String>): String {
        val name = "pic-${System.currentTimeMillis()}.${typeOf(bytes)}"
        File(dir, name).writeBytes(bytes)
        prune(keep + name)
        return name
    }

    /** Puts back a picture under its own name, from a backup. */
    fun restore(name: String, bytes: ByteArray) {
        require(NAME.matches(name)) { "Bad picture name" }
        typeOf(bytes)
        File(dir, name).writeBytes(bytes)
    }

    /** Deletes pictures not in [keep], apart from the newest few. A theme's (theme-…) stay with it. */
    fun prune(keep: Set<String>) {
        dir.listFiles().orEmpty().filter { it.name !in keep && !it.name.startsWith("theme-") }
            .sortedByDescending { it.lastModified() }.drop(KEEP_UNUSED).forEach { it.delete() }
    }

    /** Deletes the pictures an uninstalled theme brought. */
    fun deleteTheme(id: String) {
        dir.listFiles().orEmpty().filter { it.name.startsWith("theme-$id-") }.forEach { it.delete() }
    }

    companion object {
        private const val KEEP_UNUSED = 10
        private val NAME = Regex("[A-Za-z0-9._-]+")

        fun isSvg(name: String) = name.endsWith(".svg")

        private fun typeOf(bytes: ByteArray): String = when {
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes.size > 8 && String(bytes, 1, 3, Charsets.US_ASCII) == "PNG" -> "png"
            bytes.size > 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            // Text with an <svg> element near the start (after an XML declaration or comments).
            String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8).contains("<svg") -> "svg"
            else -> throw IllegalArgumentException("Not a JPEG, PNG, WebP or SVG picture")
        }
    }
}
