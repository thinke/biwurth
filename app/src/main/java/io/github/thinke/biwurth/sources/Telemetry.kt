package io.github.thinke.biwurth.sources

import io.github.thinke.biwurth.config.SourceConfig
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * A source's telemetry, when switched on in its settings: its readings ("changes" or "every"
 * reading) and what happened (button presses, errors), as one CSV file per source per day
 * (`telemetry/<source>/<yyyy-MM-dd>.csv`, rows `time,field,value`), kept for the source's
 * "keepDays". Unlike SourceHistory, which keeps numbers for graphs, this keeps everything.
 */
class Telemetry(private val dir: File) {
    // Last value written per entity, for "changes" mode.
    private val last = ConcurrentHashMap<String, String>()

    fun reading(source: SourceConfig, field: String, value: String) {
        when (source.settings.optString("telemetry", "off")) {
            "every" -> write(source, field, value)
            "changes" -> if (last.put("${source.id}.$field", value) != value) write(source, field, value)
        }
    }

    /** Something that happened: always written when the source records anything. */
    fun event(source: SourceConfig, text: String) {
        if (source.settings.optString("telemetry", "off") != "off") write(source, "event", text)
    }

    /** The last [days] days of a source's telemetry, oldest first, with a header. */
    fun csv(sourceId: String, days: Int): String {
        val folder = File(dir, sourceId)
        val since = LocalDate.now().minusDays((days - 1).toLong())
        val files = folder.listFiles { f -> f.extension == "csv" }.orEmpty()
            .filter { runCatching { !LocalDate.parse(it.nameWithoutExtension).isBefore(since) }.getOrDefault(false) }
            .sortedBy { it.name }
        return "time,field,value\n" + files.joinToString("") { it.readText() }
    }

    /** Days on record and bytes used, per source id, for the website. */
    fun usage(sourceId: String): Pair<Int, Long> {
        val files = File(dir, sourceId).listFiles { f -> f.extension == "csv" }.orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    @Synchronized
    private fun write(source: SourceConfig, field: String, value: String) {
        val folder = File(dir, source.id).apply { mkdirs() }
        val today = LocalDate.now()
        val file = File(folder, "$today.csv")
        if (!file.exists()) prune(folder, source.settings.optInt("keepDays", 30).coerceIn(1, 3650), today)
        file.appendText("${LocalDateTime.now().format(TIME)},${csvField(field)},${csvField(value)}\n")
    }

    // A new day: drop the days that have aged out.
    private fun prune(folder: File, keepDays: Int, today: LocalDate) {
        val oldest = today.minusDays((keepDays - 1).toLong())
        folder.listFiles { f -> f.extension == "csv" }.orEmpty().forEach { f ->
            val day = runCatching { LocalDate.parse(f.nameWithoutExtension) }.getOrNull()
            if (day == null || day.isBefore(oldest)) f.delete()
        }
    }

    private fun csvField(text: String) =
        if (text.any { it == ',' || it == '"' || it == '\n' }) "\"" + text.replace("\"", "\"\"").replace('\n', ' ') + "\"" else text

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    }
}
