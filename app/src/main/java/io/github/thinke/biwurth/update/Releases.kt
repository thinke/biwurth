package io.github.thinke.biwurth.update

import org.json.JSONArray
import org.json.JSONObject

/** One release's changes, for "what's new". */
data class Change(val version: Version, val lines: List<String>)

/** A published release with the APK and its checksum file. */
data class Release(
    val version: Version,
    val tag: String,
    val name: String,
    val notes: String,
    val prerelease: Boolean,
    val apkUrl: String,
    val apkSize: Long,
    /** URL of the `<apk>.sha256` file published next to the APK. */
    val sha256Url: String,
    val pageUrl: String,
    /** What changed since the installed version, newest first (see [Releases.changesSince]). */
    val changes: List<Change> = emptyList(),
)

/**
 * Semantic-ish version: numeric core plus optional pre-release (`0.1.2-rc1`). A pre-release
 * sorts before its release; pre-release parts compare numerically when both are numbers.
 */
class Version private constructor(val core: List<Int>, val pre: List<String>) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(core.size, other.core.size)) {
            val c = core.getOrElse(i) { 0 }.compareTo(other.core.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        if (pre.isEmpty() || other.pre.isEmpty()) return other.pre.size.coerceAtMost(1) - pre.size.coerceAtMost(1)
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val a = pre[i]
            val b = other.pre[i]
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> comparePrefixNumber(a, b)
            }
            if (c != 0) return c
        }
        return pre.size.compareTo(other.pre.size)
    }

    override fun equals(other: Any?) = other is Version && compareTo(other) == 0
    override fun hashCode() = core.dropLastWhile { it == 0 }.hashCode() * 31 + pre.hashCode()
    override fun toString() = core.joinToString(".") + if (pre.isEmpty()) "" else "-" + pre.joinToString(".")

    companion object {
        /** Parses "v1.2.3", "1.2", "0.1.2-rc1"; null if the numeric core is missing. */
        fun parse(text: String): Version? {
            val t = text.trim().removePrefix("v").substringBefore('+')
            val core = t.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
            if (core.isEmpty()) return null
            val pre = if ('-' in t) t.substringAfter('-').split('.').filter { it.isNotEmpty() } else emptyList()
            return Version(core, pre)
        }

        /** "rc2" vs "rc10": compare the letter prefix, then the number after it. */
        private fun comparePrefixNumber(a: String, b: String): Int {
            val pa = a.takeWhile { !it.isDigit() }
            val pb = b.takeWhile { !it.isDigit() }
            if (pa != pb) return pa.compareTo(pb)
            val na = a.drop(pa.length).toIntOrNull()
            val nb = b.drop(pb.length).toIntOrNull()
            return if (na != null && nb != null) na.compareTo(nb) else a.compareTo(b)
        }
    }
}

object Releases {
    const val API_URL = "https://api.github.com/repos/thinke/biwurth/releases?per_page=20"

    /** All usable releases from a GitHub `/releases` response, newest version first. */
    fun parse(json: String): List<Release> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            if (o.optBoolean("draft")) return@mapNotNull null
            val tag = o.optString("tag_name").ifEmpty { return@mapNotNull null }
            val version = Version.parse(tag) ?: return@mapNotNull null
            val assets = o.optJSONArray("assets") ?: JSONArray()
            val all = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            val apk = all.firstOrNull { it.optString("name").endsWith(".apk") } ?: return@mapNotNull null
            val sum = all.firstOrNull { it.optString("name") == apk.optString("name") + ".sha256" } ?: return@mapNotNull null
            Release(
                version = version,
                tag = tag,
                name = o.optString("name").ifEmpty { tag },
                notes = o.optString("body"),
                prerelease = o.optBoolean("prerelease"),
                apkUrl = apk.optString("browser_download_url").ifEmpty { return@mapNotNull null },
                apkSize = apk.optLong("size"),
                sha256Url = sum.optString("browser_download_url").ifEmpty { return@mapNotNull null },
                pageUrl = o.optString("html_url"),
            )
        }.sortedByDescending { it.version }
    }

    /** The release to offer over [installed], or null if none is newer. */
    fun newest(releases: List<Release>, installed: Version, includePrereleases: Boolean): Release? =
        releases.filter { includePrereleases || !it.prerelease }.maxByOrNull { it.version }?.takeIf { it.version > installed }

    /**
     * What changed after [installed] up to [target]: the notes of every release in between,
     * newest first, so updating 0.1.8 → 0.2.2 shows 0.2.2, 0.2.1 and 0.2.0.
     */
    fun changesSince(releases: List<Release>, installed: Version, target: Release, includePrereleases: Boolean): List<Change> =
        releases.filter { it.version > installed && it.version <= target.version && (includePrereleases || !it.prerelease || it.tag == target.tag) }
            .sortedByDescending { it.version }
            .map { Change(it.version, noteLines(it.notes)) }
            .filter { it.lines.isNotEmpty() }

    /** Release notes (GitHub markdown) as plain lines: list marks, headings, links and the "Full Changelog" footer removed. */
    fun noteLines(notes: String): List<String> = notes.lines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("**Full Changelog**") }
        .map { line ->
            line.removePrefix("- ").removePrefix("* ").replace("**", "")
                .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1") // [text](url) → text
        }

    /** The hash from a `sha256sum` line ("<hex>  <file>"). */
    fun parseSha256(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

    fun Release.toJson(): JSONObject = JSONObject()
        .put("version", version.toString())
        .put("name", name)
        .put("prerelease", prerelease)
        .put("pageUrl", pageUrl)
        .put("apkSize", apkSize)
        .put(
            "changes",
            JSONArray(changes.map { JSONObject().put("version", it.version.toString()).put("lines", JSONArray(it.lines)) }),
        )
}
