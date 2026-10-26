// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import okio.Buffer
import okio.Sink
import okio.Source
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException

/** Answers by the first matching command prefix and records what ran. */
class FakeShell(
    private val answers: Map<String, ShellResult> = emptyMap(),
    val transfer: FakeTransfer = FakeTransfer(),
) : AdbSession, AdbTransfer by transfer {
    val ran = mutableListOf<String>()
    var closed = false
    var failWith: Exception? = null
    var rebooted = false
    /** Thrown by [reboot], like Kadb does when adbd refuses the service. */
    var rebootFails: Exception? = null
    /** Checked before [answers], for a TV whose answer changes after a command. */
    var answer: (String) -> ShellResult? = { null }

    override suspend fun shell(command: String): ShellResult {
        failWith?.let { throw it }
        ran += command
        answer(command)?.let { return it }
        return answers.entries.firstOrNull { command.startsWith(it.key) }?.value ?: ShellResult("", 0)
    }

    val pushed = mutableMapOf<String, ByteArray>()
    var failPush: Exception? = null

    override suspend fun push(bytes: ByteArray, remotePath: String) {
        failPush?.let { throw it }
        pushed[remotePath] = bytes
    }

    override suspend fun reboot() {
        rebootFails?.let { throw it }
        rebooted = true
    }

    /** The helper's whole stdout for the next [exec], read as it goes; null refuses the exec. */
    var execOutput: String? = null
    val execs = mutableListOf<String>()
    /** Everything written to an [exec]'s stdin. */
    val execInput = Buffer()
    var execClosed = false

    override suspend fun exec(command: String): AdbPipe {
        failWith?.let { throw it }
        execs += command
        val out = Buffer().writeUtf8(execOutput ?: throw IOException("exec refused"))
        return object : AdbPipe {
            override val stdin = execInput
            override val stdout = out
            override fun close() { execClosed = true }
        }
    }

    override fun close() { closed = true }
}

/** A TV filesystem in a map, plus the install calls it saw. */
class FakeTransfer : AdbTransfer {
    val files = mutableMapOf<String, ByteArray>()
    val directories = mutableSetOf<String>()
    val installs = mutableListOf<Pair<List<String>, List<String>>>()
    /** Thrown by install calls, like Kadb does with the TV's "Failure [...]" answer. */
    var installFails: Exception? = null
    /** Runs between chunks, so a test can cancel mid-copy. */
    var onChunk: () -> Unit = {}

    override fun install(source: Source, size: Long, vararg options: String) {
        drain(source)
        installFails?.let { throw it }
        installs += listOf("stream:$size") to options.toList()
    }

    override fun installMultiple(apks: List<File>, vararg options: String) {
        installFails?.let { throw it }
        installs += apks.map { it.name } to options.toList()
    }

    override fun push(source: Source, remotePath: String, mode: Int, lastModifiedMs: Long) {
        files[remotePath] = drain(source)
    }

    override fun pull(sink: Sink, remotePath: String) {
        val bytes = files[remotePath] ?: throw java.io.IOException("Sync failed: No such file")
        val src = bytes.inputStream().source().buffer()
        while (!src.exhausted()) {
            val chunk = Buffer()
            src.read(chunk, CHUNK)
            sink.write(chunk, chunk.size)
            onChunk()
        }
        sink.flush()
    }

    override fun list(remoteDir: String): List<RemoteEntry> =
        files.filterKeys { it.substringBeforeLast('/') == remoteDir }
            .map { (path, bytes) -> RemoteEntry(path.substringAfterLast('/'), 0x81A4, bytes.size.toLong(), 1_700_000_000) } +
            directories.filter { it.substringBeforeLast('/') == remoteDir }
                .map { RemoteEntry(it.substringAfterLast('/'), 0x41ED, 0L, 1_700_000_000) }

    private fun drain(source: Source): ByteArray {
        val all = Buffer()
        while (source.read(all, CHUNK) != -1L) onChunk()
        return all.readByteArray()
    }

    private companion object {
        const val CHUNK = 8192L
    }
}

fun ok(output: String) = ShellResult(output, 0)
