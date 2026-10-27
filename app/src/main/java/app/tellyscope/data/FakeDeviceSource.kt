// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import app.tellyscope.adb.ApkInfo
import app.tellyscope.adb.ConnectionRequest
import app.tellyscope.adb.InstallResult
import app.tellyscope.adb.InstalledVersion
import app.tellyscope.adb.Progress
import app.tellyscope.adb.RemoteKey
import app.tellyscope.adb.TvFile
import app.tellyscope.adb.TvFolder
import app.tellyscope.adb.Tweak
import java.io.File
import java.io.IOException
import java.time.YearMonth
import kotlin.random.Random
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import okio.Buffer
import okio.Sink
import okio.Source

/**
 * Stand-in [DeviceSource] for building the screens before the ADB module exists.
 * [Scenario] switches between the flows worth looking at.
 */
class FakeDeviceSource(
    private val scenario: Scenario = Scenario.Normal,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) : DeviceSource {

    enum class Scenario {
        Normal,
        /** Scan finds nothing: the brand guide. */
        Empty,
        /** The TV never answers the Allow prompt. */
        NoAllow,
    }

    private val hits = listOf(
        ScanHit("192.168.1.23", 5555, ScanKind.Classic, "xgimi-projector", "XGIMI"),
        ScanHit("192.168.1.41", 5555, ScanKind.Classic, "android-7f3a", "Amlogic"),
        ScanHit("192.168.1.57", 37211, ScanKind.Wireless, "chromecast", "Google"),
        ScanHit("192.168.1.88", 5555, ScanKind.Classic, null, null),
    )

    private val models = mapOf(
        "XGIMI" to Triple("XGIMI Horizon Pro", "XGIMI", "11"),
        "Amlogic" to Triple("X96 Max", "Amlogic", "9"),
        "Google" to Triple("Chromecast with Google TV", "Google", "12"),
    )

    private val apps = mutableMapOf<String, MutableList<AppInfo>>()
    private val book = HistoryBook()
    private val tvFiles = mutableMapOf<TvFolder, MutableList<TvFile>>()
    private val simulatedSubfolders = mutableMapOf<String, MutableList<TvFile>>()

    private fun fakeFolder(folder: TvFolder, subfolders: List<String>): MutableList<TvFile> =
        if (subfolders.isEmpty()) tvFiles.getOrPut(folder) {
            if (folder == TvFolder.DOWNLOAD) baseFiles.toMutableList() else mutableListOf()
        } else simulatedSubfolders.getOrPut(folder.name + "/" + subfolders.joinToString("/")) { mutableListOf() }


    override fun scan(): Flow<ScanEvent> = flow {
        val subnet = "192.168.1.0/24"
        val pending = if (scenario == Scenario.Empty) ArrayDeque() else ArrayDeque(hits)
        var probed = 0
        while (probed < 254) {
            delay(120)
            probed = minOf(254, probed + 23)
            emit(ScanEvent.Progress(ScanProgress(probed, 254, subnet)))
            if (pending.isNotEmpty() && random.nextFloat() < 0.45f) emit(ScanEvent.Found(pending.removeFirst()))
        }
        pending.forEach { emit(ScanEvent.Found(it)) }
        emit(ScanEvent.Done)
    }

    override suspend fun connect(hit: ScanHit): ConnectResult {
        if (scenario == Scenario.NoAllow) {
            delay(Long.MAX_VALUE)
        }
        if (hit.kind == ScanKind.Wireless) {
            delay(300)
            return ConnectResult.NeedsPairing
        }
        delay(3_200) // the user finding the remote and pressing Allow
        return ConnectResult.Online(device(hit))
    }

    override suspend fun pair(hit: ScanHit, code: String): ConnectResult {
        delay(800)
        if (!code.matches(Regex("\\d{6}"))) {
            return ConnectResult.WrongCode("that code didn't work. codes change each time the box opens; check the TV and try again.")
        }
        return ConnectResult.Online(device(hit))
    }

    /** Connected or remembered TVs, keyed by id; starts with one offline TV to show that state. */
    private val remembered = linkedMapOf<String, Device>().apply {
        listOf(
            Device(
                id = "192.168.1.64",
                hit = ScanHit("192.168.1.64", 5555, ScanKind.Classic, "bedroom-firetv", "Amazon"),
                state = DeviceState.Offline,
                model = "Fire TV Stick 4K",
                manufacturer = "Amazon",
                android = "9",
                lastSeen = clock() - 14 * 60_000,
                lastKnown = LastKnown("Amazon", "Amazon", "9", YearMonth.of(2024, 3), 5.1, 5.4),
            ),
        ).takeIf { scenario == Scenario.Normal }.orEmpty().forEach { put(it.id, it) }
    }

    /** TVs restarting, and when they answer again. */
    private val backAt = mutableMapOf<String, Long>()

    override suspend fun known(): List<Device> {
        backAt.entries.removeAll { (id, at) ->
            (at <= clock()).also { back -> if (back) remembered[id]?.let { remembered[id] = it.copy(state = DeviceState.Online, lastSeen = clock()) } }
        }
        return remembered.values.toList()
    }

    override suspend fun reconnect(deviceId: String) = Unit

    override suspend fun rename(deviceId: String, name: String?) {
        remembered[deviceId]?.let { remembered[deviceId] = it.copy(nickname = name?.trim()?.ifEmpty { null }) }
    }

    override suspend fun forget(deviceId: String) {
        remembered.remove(deviceId)
    }

    private fun device(hit: ScanHit): Device {
        val (model, maker, android) = models[hit.maker] ?: Triple("Android TV box", hit.maker, "10")
        return Device(hit.ip, hit, DeviceState.Online, model, maker, android, clock()).also { remembered[it.id] = it }
    }

    override fun live(deviceId: String): Flow<Snapshot> = flow {
        while (currentCoroutineContext().isActive) {
            emit(snapshot(deviceId))
            delay(2_000)
        }
    }

    fun snapshot(deviceId: String): Snapshot {
        val enabled = appsFor(deviceId).filter { it.enabled }.map { it.packageName }.toSet()
        val procs = baseProcs
            .filter { it.packageName == null || it.packageName in enabled }
            .map {
                it.copy(
                    cpuPercent = (it.cpuPercent + (random.nextDouble() - 0.5) * 3).coerceAtLeast(0.0),
                    ramMb = (it.ramMb * (0.97 + random.nextDouble() * 0.06)).toInt(),
                )
            }
        return Snapshot(
            at = clock(),
            cpuPercent = procs.sumOf { it.cpuPercent }.toInt(),
            ramUsedMb = procs.sumOf { it.ramMb } + 260,
            ramTotalMb = 2048,
            storageUsedGb = 9.8,
            storageTotalGb = 16.0,
            tempC = 52 + random.nextInt(-1, 2),
            uptimeSeconds = 3 * 86_400L + 4 * 3_600L,
            screenOn = true,
            procs = procs,
            netBytesPerSec = 2_400_000L + random.nextLong(-200_000, 200_000),
            softwareMonth = YearMonth.of(2023, 5),
        )
    }

    override suspend fun apps(deviceId: String): List<AppInfo> = appsFor(deviceId).toList()

    private fun appsFor(deviceId: String) = apps.getOrPut(deviceId) { baseApps.toMutableList() }

    override suspend fun network(deviceId: String) = NetworkInfo(
        iface = "wifi",
        ip = deviceId,
        mac = "58:d5:0a:3c:91:e2",
        ssid = "home-5g",
        rssi = -54 + random.nextInt(-3, 4),
        linkMbps = 433,
        sockets = listOf(
            Socket("YouTube", "youtube.com:443", "established"),
            Socket("Jellyfin", "192.168.1.10:8096", "established"),
            Socket("Google Play services", "googleapis.com:443", "established"),
            Socket("XGIMI Assistant", "xgimi.com:443", "closing"),
        ),
        traffic = listOf(
            AppTraffic("YouTube", 1840, 22),
            AppTraffic("Jellyfin", 960, 4),
            AppTraffic("Netflix", 410, 6),
            AppTraffic("Google Play services", 120, 31),
            AppTraffic("XGIMI Updates", 64, 1),
        ),
    )

    override suspend fun act(deviceId: String, action: Action, packageName: String, undoing: Long?): HistoryEntry {
        delay(400)
        val list = appsFor(deviceId)
        if (!book.hasBaseline(deviceId)) book.setBaseline(deviceId, list.filter { !it.enabled }.map { it.packageName }.toSet())
        val i = list.indexOfFirst { it.packageName == packageName }
        val app = list[i]
        when (action) {
            Action.Disable -> list[i] = app.copy(enabled = false)
            Action.Enable -> list[i] = app.copy(enabled = true)
            Action.Uninstall -> list.removeAt(i)
            Action.Stop, Action.Open, Action.Change, Action.ClearCaches, Action.Home, Action.HomeBack -> Unit
        }
        val undo = when (action) {
            Action.Stop -> Action.Open
            Action.Disable -> Action.Enable
            Action.Enable -> Action.Disable
            Action.Home -> Action.Enable
            Action.HomeBack -> Action.Home
            Action.Open, Action.Uninstall, Action.Change, Action.ClearCaches -> null
        }
        return book.record(deviceId, clock(), action, packageName, app.label, undo, undoing)
    }

    override suspend fun history(deviceId: String): List<HistoryEntry> = book.entries(deviceId)

    override suspend fun restoreAll(deviceId: String): List<HistoryEntry> {
        val off = appsFor(deviceId).filter { !it.enabled }.map { it.packageName }.toSet()
        return restoreEach(book.restorable(deviceId).filter { it in off }) { pkg ->
            act(deviceId, Action.Enable, pkg, undoing = book.openDisable(deviceId, pkg))
        }
    }

    override suspend fun installed(deviceId: String, packageName: String): InstalledVersion? =
        appsFor(deviceId).firstOrNull { it.packageName == packageName }?.let { InstalledVersion(1, it.version) }

    override suspend fun install(
        deviceId: String,
        apks: List<File>,
        apk: ApkInfo,
        allowDowngrade: Boolean,
        onProgress: (Progress) -> Unit,
    ): InstallResult {
        val total = apks.sumOf { it.length() }
        for (i in 0..20) {
            onProgress(Progress(total * i / 20, total))
            delay(100)
        }
        delay(1_200) // the TV unpacking it
        return InstallResult.Done
    }

    override suspend fun files(deviceId: String, folder: TvFolder): List<TvFile> =
        fakeFolder(folder, emptyList()).sortedWith(compareByDescending<TvFile> { it.isDirectory }.thenByDescending { it.modifiedMillis })

    override suspend fun browseFiles(deviceId: String, folder: TvFolder, subfolders: List<String>): List<TvFile> =
        fakeFolder(folder, subfolders).sortedWith(compareByDescending<TvFile> { it.isDirectory }.thenByDescending { it.modifiedMillis })

    override suspend fun createDirectory(deviceId: String, folder: TvFolder, subfolders: List<String>, name: String) {
        require(name.isNotBlank() && name !in setOf(".", "..") && '/' !in name && '\\' !in name)
        val entries = fakeFolder(folder, subfolders)
        require(entries.none { it.name == name })
        entries += TvFile(name, 0L, clock(), isDirectory = true)
    }

    override suspend fun renameFile(deviceId: String, folder: TvFolder, subfolders: List<String>, oldName: String, newName: String) {
        require(newName.isNotBlank() && '/' !in newName && '\\' !in newName)
        val entries = fakeFolder(folder, subfolders)
        require(entries.none { it.name == newName })
        val index = entries.indexOfFirst { it.name == oldName }
        require(index >= 0)
        entries[index] = entries[index].copy(name = newName)
        if (entries[index].isDirectory) {
            val prefix = folder.name + "/" + (subfolders + oldName).joinToString("/")
            val next = folder.name + "/" + (subfolders + newName).joinToString("/")
            simulatedSubfolders.keys.filter { it == prefix || it.startsWith("$prefix/") }.forEach { key ->
                simulatedSubfolders[next + key.removePrefix(prefix)] = simulatedSubfolders.remove(key) ?: mutableListOf()
            }
        }
    }

    override suspend fun deleteFile(deviceId: String, folder: TvFolder, subfolders: List<String>, name: String) {
        val entries = fakeFolder(folder, subfolders)
        val found = entries.firstOrNull { it.name == name } ?: error("Файл отсутствует")
        if (found.isDirectory) require(fakeFolder(folder, subfolders + name).isEmpty())
        entries.remove(found)
    }

    override suspend fun push(deviceId: String, folder: TvFolder, name: String, size: Long?, source: Source, onProgress: (Progress) -> Unit): String {
        var done = 0L
        val buf = Buffer()
        while (true) {
            val n = source.read(buf, 256 * 1024)
            if (n < 0) break
            buf.clear()
            done += n
            onProgress(Progress(done, size))
            delay(50)
        }
        files(deviceId, folder)
        tvFiles.getValue(folder) += TvFile(name, done, clock())
        return name
    }

    override suspend fun pushInto(
        deviceId: String, folder: TvFolder, subfolders: List<String>,
        name: String, size: Long?, source: Source, onProgress: (Progress) -> Unit,
    ): String {
        if (subfolders.isEmpty()) return push(deviceId, folder, name, size, source, onProgress)
        var read = 0L
        val buf = Buffer()
        while (true) {
            val n = source.read(buf, 256L * 1024L)
            if (n < 0) break
            buf.clear()
            read += n
            onProgress(Progress(read, size))
        }
        fakeFolder(folder, subfolders) += TvFile(name, read, clock())
        return name
    }

    override suspend fun pullFrom(
        deviceId: String, folder: TvFolder, subfolders: List<String>,
        file: TvFile, sink: Sink, onProgress: (Progress) -> Unit,
    ) {
        require(fakeFolder(folder, subfolders).any { it.name == file.name && !it.isDirectory })
        pull(deviceId, folder, file, sink, onProgress)
    }

    override suspend fun pull(deviceId: String, folder: TvFolder, file: TvFile, sink: Sink, onProgress: (Progress) -> Unit) {
        val chunk = ByteArray(64 * 1024)
        var done = 0L
        while (done < file.size) {
            val n = minOf(chunk.size.toLong(), file.size - done)
            sink.write(Buffer().write(chunk, 0, n.toInt()), n)
            done += n
            onProgress(Progress(done, file.size))
            delay(30)
        }
        sink.flush()
    }

    private val baseFiles get() = listOf(
        TvFile("holiday-2025.mp4", 812_000_000, clock() - 3 * 86_400_000L),
        TvFile("projectivy-backup.plbackup", 48_200, clock() - 26 * 3_600_000L),
        TvFile("subtitles.srt", 61_000, clock() - 40 * 60_000L),
    )

    override suspend fun suggestions(deviceId: String): Suggestions {
        delay(600)
        val all = listOf(ListEntry(fakeList.id, fakeList.name, null, fakeList.sdk, "devices/${fakeList.id}.json", "0".repeat(64)))
        return Suggestions.Ready(null, fakeList, 30, suggest(fakeList, appsFor(deviceId), 30), all)
    }

    override suspend fun pickList(deviceId: String, listId: String) = Unit

    private var listSource: String? = null

    override suspend fun listSource() = listSource

    override suspend fun setListSource(url: String?) {
        listSource = url?.let { normalize(it) ?: throw IllegalStateException("the address has to start with https://") }
    }

    override suspend fun press(deviceId: String, key: RemoteKey): Boolean {
        delay(60)
        return true
    }

    override suspend fun type(deviceId: String, text: String): Boolean {
        delay(150)
        return true
    }

    override suspend fun openUpdates(deviceId: String): Boolean {
        delay(300)
        return true
    }

    override suspend fun reboot(deviceId: String): Boolean {
        delay(300)
        remembered[deviceId]?.let { remembered[deviceId] = it.copy(state = DeviceState.Offline) }
        backAt[deviceId] = clock() + 20_000
        return true
    }

    /** Global settings per TV, for the tweaks card. */
    private val settings = mutableMapOf<String, MutableMap<String, String?>>()

    private fun settingsFor(deviceId: String) = settings.getOrPut(deviceId) { mutableMapOf("private_dns_mode" to "opportunistic") }

    override suspend fun tweaks(deviceId: String): TvTweaks {
        val s = settingsFor(deviceId)
        return TvSettings.tweaks(s, s, sdk = 30)
    }

    override suspend fun change(deviceId: String, tweak: Tweak, values: Map<String, String?>, undoing: Long?): HistoryEntry {
        delay(300)
        val s = settingsFor(deviceId)
        val before = values.keys.associateWith { s[it] }
        s.putAll(values)
        return book.record(deviceId, clock(), Action.Change, tweak.name, TvSettings.label(tweak, s), Action.Change, undoing, before)
    }

    override suspend fun clearCaches(deviceId: String): Long {
        delay(800)
        book.record(deviceId, clock(), Action.ClearCaches, "", TvSettings.CACHES, undo = null)
        return 1_340_000
    }

    override suspend fun screenshot(deviceId: String, sink: Sink) {
        delay(500)
        throw IOException("the demo TV has no screen to capture")
    }

    override suspend fun connectionRequest(deviceId: String): ConnectionRequest? = null

    override suspend fun answerRequest(deviceId: String, request: ConnectionRequest, allow: Boolean, always: Boolean) = false

    private companion object {
        val fakeList = DeviceList(
            id = "xgimi-horizon-pro",
            name = "XGIMI Horizon Pro",
            testedOs = "Android TV 11",
            sdk = 30,
            keep = emptySet(),
            groups = listOf(
                ListGroup("Extras", listOf(
                    ListPackage("com.xgimi.assistant", "voice assistant", optional = false),
                    ListPackage("com.google.android.tvrecommendations", null, optional = false),
                    ListPackage("com.netflix.ninja", null, optional = true),
                )),
            ),
        )


        val baseApps = listOf(
            AppInfo("com.spocky.projengmenu", "Projectivy Launcher", 0xFF3D5AFE, false, true, "this is your home screen", "4.52"),
            AppInfo("com.google.android.youtube.tv", "YouTube", 0xFFC4302B, false, true, null, "4.40.303"),
            AppInfo("com.netflix.ninja", "Netflix", 0xFFB20710, false, true, null, "11.2.0", removable = true),
            AppInfo("org.jellyfin.androidtv", "Jellyfin", 0xFF7B5EA7, false, true, null, "0.17.4", removable = true),
            AppInfo("com.xgimi.assistant", "XGIMI Assistant", null, true, true, null, "2.1.8"),
            AppInfo("com.xgimi.ota", "XGIMI Updates", null, true, true, null, "1.4.0"),
            AppInfo("com.google.android.tvrecommendations", "TV Recommendations", null, true, false, null, "1.0"),
            AppInfo("com.android.systemui", "System UI", null, true, true, "the TV needs this to draw the screen", "11"),
            AppInfo("com.android.tv.settings", "Settings", null, true, true, "you need this to change TV settings", "11"),
            AppInfo("com.google.android.gms", "Google Play services", null, true, true, "most apps stop working without it", "24.38"),
            AppInfo("com.google.android.inputmethod.latin", "Gboard", null, true, true, "you'd lose the on-screen keyboard", "14.1"),
        )

        val baseProcs = listOf(
            Proc(412, "surfaceflinger", null, 9.0, 38),
            Proc(688, "system_server", null, 6.0, 214),
            Proc(1502, "com.spocky.projengmenu", "com.spocky.projengmenu", 2.0, 312),
            Proc(2210, "com.google.android.youtube.tv", "com.google.android.youtube.tv", 4.0, 220),
            Proc(2241, "com.google.android.youtube.tv:service", "com.google.android.youtube.tv", 1.0, 41),
            Proc(1980, "com.google.android.gms.persistent", "com.google.android.gms", 1.0, 118),
            Proc(2077, "com.xgimi.assistant", "com.xgimi.assistant", 1.0, 96),
            Proc(2301, "org.jellyfin.androidtv", "org.jellyfin.androidtv", 0.5, 88),
            Proc(1440, "com.android.systemui", "com.android.systemui", 1.0, 102),
            Proc(530, "media.codec", null, 2.0, 22),
            Proc(2390, "com.xgimi.ota", "com.xgimi.ota", 0.0, 31),
        )
    }
}
