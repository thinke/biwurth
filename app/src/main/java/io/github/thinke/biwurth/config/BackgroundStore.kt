package io.github.thinke.biwurth.config

import android.content.Context
import java.io.File

/**
 * Background pictures uploaded from the website, kept in app storage. The layout refers to one by
 * file name; the newest few are kept so an unsaved layout can still go back to the previous one.
 */
class BackgroundStore(context: Context) {
    private val dir = File(context.filesDir, "backgrounds").apply { mkdirs() }

    fun file(name: String): File? = File(dir, name).takeIf { it.isFile && it.parentFile == dir }

    /** Stores a picture and returns its file name, after checking it really is a JPEG, PNG or WebP. */
    fun save(bytes: ByteArray): String {
        val name = "bg-${System.currentTimeMillis()}.${extension(bytes)}"
        File(dir, name).writeBytes(bytes)
        // Themes' backgrounds (theme-…) stay for as long as their theme.
        dir.listFiles()?.filterNot { it.name.startsWith("theme-") }?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        return name
    }

    /** Stores a theme's background under its own name ([name] without extension gets the right one). */
    fun saveAs(name: String, bytes: ByteArray): String {
        val stored = "${name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_")}.${extension(bytes)}"
        File(dir, stored).writeBytes(bytes)
        return stored
    }

    fun deleteTheme(id: String) {
        dir.listFiles().orEmpty().filter { it.name.startsWith("theme-$id-") }.forEach { it.delete() }
    }

    private fun extension(bytes: ByteArray) = when {
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size > 8 && String(bytes, 1, 3, Charsets.US_ASCII) == "PNG" -> "png"
        bytes.size > 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        else -> throw IllegalArgumentException("Not a JPEG, PNG or WebP picture")
    }

    private companion object {
        const val KEEP = 5
    }
}
