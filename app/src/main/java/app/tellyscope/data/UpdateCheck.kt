// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.Properties

/** Where releases are published: the app's own GitHub repo. */
const val RELEASES = "https://github.com/svncam66-arch/tellyscope/releases"
private const val RELEASES_API = "https://api.github.com/repos/svncam66-arch/tellyscope/releases?per_page=20"

/** How long a check counts for: release builds ask every 30 minutes, alpha builds every 5 minutes for testing. */
private const val RELEASE_INTERVAL_MS = 30 * 60 * 1000L
private const val ALPHA_INTERVAL_MS = 5 * 60 * 1000L

/** A newer Tellyscope than the one running. */
data class Update(val version: String, val notes: String, val url: String)

/**
 * Asks GitHub, at most every 30 minutes (5 minutes on alpha builds), whether a newer release is out. No auth and no download: it
 * only reads the release list. Off switch, last check and a skipped version live in [file].
 * Release builds look at full releases only; alpha builds at `-alpha` pre-releases too.
 */
class UpdateCheck(
    private val file: File,
    /** The running versionName, alpha suffix included. */
    private val current: String,
    val alpha: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetch: suspend (String) -> ByteArray = ::download,
) {
    @get:Synchronized @set:Synchronized
    var enabled: Boolean
        get() = props().getProperty("off") != "true"
        set(on) = save { if (on) remove("off") else setProperty("off", "true") }

    @Synchronized
    fun skip(version: String) = save { setProperty("skip", version) }

    /** The newest release past [current], unless it was skipped. Null when off, checked recently, or on any failure. */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        val p = props()
        if (p.getProperty("off") == "true") return@withContext null
        val last = p.getProperty("checked")?.toLongOrNull()
        val now = clock()
        val interval = if (alpha) ALPHA_INTERVAL_MS else RELEASE_INTERVAL_MS
        if (last != null && now - last in 0 until interval) return@withContext null
        val text = try {
            fetch(RELEASES_API).decodeToString()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext null
        }
        save { setProperty("checked", now.toString()) }
        newest(text, alpha)?.takeIf { compareVersions(it.version, current) > 0 && it.version != p.getProperty("skip") }
    }

    private fun props() = Properties().apply {
        runCatching { file.takeIf { it.exists() }?.inputStream()?.use(::load) }
    }

    @Synchronized
    private fun save(change: Properties.() -> Unit) {
        val p = props().apply(change)
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { p.store(it, null) }
        }
    }
}

/** The newest release in a GitHub release list a build may move to; null when there is none or the list won't parse. */
fun newest(json: String, alpha: Boolean): Update? = runCatching {
    val list = JSONArray(json)
    (0 until list.length()).map(list::getJSONObject)
        .filter { !it.optBoolean("draft") }
        .mapNotNull { r ->
            val version = r.optString("tag_name").removePrefix("v")
            val parsed = parseVersion(version) ?: return@mapNotNull null
            // A release build never offers an alpha; an alpha build offers both.
            if (parsed.alpha != null && !alpha) return@mapNotNull null
            if (r.optBoolean("prerelease") && parsed.alpha == null) return@mapNotNull null
            Update(version, r.optString("body").trim(), "$RELEASES/tag/v$version")
        }
        .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
}.getOrNull()

private class Version(val core: List<Int>, val alpha: Int?)

/** `X.Y.Z` or `X.Y.Z-alpha.N`; anything else is not a Tellyscope version. */
private fun parseVersion(v: String): Version? {
    val m = Regex("""(\d+)\.(\d+)\.(\d+)(?:-alpha\.(\d+))?""").matchEntire(v) ?: return null
    val n = m.groupValues.drop(1).map { it.toIntOrNull() }
    if (n.take(3).any { it == null }) return null
    return Version(n.take(3).map { it!! }, m.groups[4]?.let { it.value.toIntOrNull() ?: return null })
}

/** Like semver: `0.2.0-alpha.3` < `0.2.0` < `0.2.1-alpha.1`. An unparseable version sorts first. */
fun compareVersions(a: String, b: String): Int {
    val x = parseVersion(a)
    val y = parseVersion(b)
    if (x == null || y == null) return compareValues(x != null, y != null)
    for (i in 0..2) x.core[i].compareTo(y.core[i]).takeIf { it != 0 }?.let { return it }
    return when {
        x.alpha == y.alpha -> 0
        x.alpha == null -> 1
        y.alpha == null -> -1
        else -> x.alpha.compareTo(y.alpha)
    }
}
