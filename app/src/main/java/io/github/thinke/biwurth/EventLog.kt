package io.github.thinke.biwurth

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * biwurth's own log, kept in app storage so it can be read and downloaded from the website later:
 * Android's log is small, gone after a reboot, and this tablet (log.tag=E) keeps only errors of it.
 * Rotates at [MAX_BYTES] keeping one older file. Never give it passwords or keys.
 */
object EventLog {
    private const val MAX_BYTES = 512 * 1024
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private var file: File? = null

    fun init(dir: File) {
        file = File(dir, "biwurth.log")
        // Crashes: write the stack trace before the process dies, so it's there after the restart.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            e("crash", "Uncaught in ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
        i("app", "biwurth ${BuildConfig.VERSION_NAME} started")
    }

    fun i(tag: String, message: String) = write('I', tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = write('W', tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = write('E', tag, message, error)

    @Synchronized
    private fun write(level: Char, tag: String, message: String, error: Throwable?) {
        Log.println(if (level == 'E') Log.ERROR else if (level == 'W') Log.WARN else Log.INFO, "biwurth.$tag", message)
        val file = file ?: return
        val trace = error?.let { "\n" + StringWriter().also { w -> it.printStackTrace(PrintWriter(w)) }.toString().trimEnd() }.orEmpty()
        runCatching {
            if (file.length() > MAX_BYTES) file.renameTo(File(file.parentFile, "biwurth.1.log"))
            file.appendText("${LocalDateTime.now().format(TIME)} $level $tag: $message$trace\n")
        }
    }

    /** The newest [lines] lines, oldest first. */
    @Synchronized
    fun tail(lines: Int): String = all().lines().filter { it.isNotEmpty() }.takeLast(lines).joinToString("\n")

    /** Both files, oldest first, for downloading. */
    @Synchronized
    fun all(): String {
        val dir = file?.parentFile ?: return ""
        return listOf(File(dir, "biwurth.1.log"), File(dir, "biwurth.log")).filter { it.exists() }.joinToString("") { it.readText() }
    }

    @Synchronized
    fun clear() {
        val dir = file?.parentFile ?: return
        File(dir, "biwurth.1.log").delete()
        file?.writeText("")
        i("app", "Log cleared")
    }
}
