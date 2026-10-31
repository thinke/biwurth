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
        val extension = when {
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes.size > 8 && String(bytes, 1, 3, Charsets.US_ASCII) == "PNG" -> "png"
            bytes.size > 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> throw IllegalArgumentException("Not a JPEG, PNG or WebP picture")
        }
        val name = "bg-${System.currentTimeMillis()}.$extension"
        File(dir, name).writeBytes(bytes)
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        return name
    }

    private companion object {
        const val KEEP = 5
    }
}
