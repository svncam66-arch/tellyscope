// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Sink
import okio.Source

/** The two shared folders Tellyscope reads and writes. Nothing outside them. */
enum class TvFolder(val path: String) {
    DOWNLOAD("/sdcard/Download"),
    MOVIES("/sdcard/Movies"),
}

data class TvFile(val name: String, val size: Long, val modifiedMillis: Long, val isDirectory: Boolean = false)

/** Phone ↔ TV file copies over the ADB sync service. */
class Files(private val session: AdbSession) {

    /** Plain files in [folder], newest first. A missing folder is an empty one. */
    suspend fun list(folder: TvFolder, subfolders: List<String> = emptyList()): List<TvFile> = runTransfer { _ ->
        val path = verifiedDirectory(folder, subfolders)
        session.list(path)
            .filter {
                val kind = it.mode and S_IFMT
                (kind == S_IFREG || kind == S_IFDIR) && safeVisibleSegment(it.name)
            }
            .map { TvFile(it.name, it.size, it.mtimeSec * 1000, it.mode and S_IFMT == S_IFDIR) }
            .sortedWith(compareByDescending<TvFile> { it.isDirectory }.thenByDescending { it.modifiedMillis })
    }

    /**
     * Copies [source] into [folder] as [name], or "name (1).ext" if that's taken, so nothing on the
     * TV is ever overwritten. Returns the name it was saved under. A cancelled or failed copy deletes
     * the partial file, so a retry doesn't land as "name (1)" beside a truncated one.
     */
    suspend fun push(
        source: Source,
        size: Long?,
        name: String,
        folder: TvFolder,
        subfolders: List<String> = emptyList(),
        onProgress: (Progress) -> Unit = {},
    ): String {
        // Every entry counts as taken, hidden files and folders too, not just what [list] shows.
        val dir = runTransfer { _ -> verifiedDirectory(folder, subfolders) }
        val taken = runTransfer { _ -> session.list(dir).map { it.name }.toSet() }
        val target = uniqueName(safeName(name), taken)
        val path = "$dir/$target"
        runTransfer(cleanUp = { runCatching { session.shell("rm -f ${shellQuote(path)}") } }) { stopped ->
            session.push(source.counting(size, stopped, onProgress), path, FILE_MODE, System.currentTimeMillis())
        }
        return target
    }

    /** Copies [file] from [folder] into [sink]. On cancel the caller deletes what it was writing to. */
    suspend fun pull(
        folder: TvFolder,
        file: TvFile,
        sink: Sink,
        subfolders: List<String> = emptyList(),
        onProgress: (Progress) -> Unit = {},
    ) {
        checkedSegment(file.name)
        require(!file.isDirectory) { "Папку нельзя скачать как отдельный файл." }
        val dir = runTransfer { _ ->
            val checked = verifiedDirectory(folder, subfolders)
            require(session.list(checked).any { it.name == file.name && it.mode and S_IFMT == S_IFREG }) {
                "Файл недоступен для скачивания."
            }
            checked
        }
        runTransfer { stopped -> session.pull(sink.counting(file.size, stopped, onProgress), "$dir/${file.name}") }
    }

    /**
     * Only descend through actual folders reported by ADB sync. A symlink is never
     * traversed, and no user-controlled string may escape Download or Movies.
     */
    private fun safeVisibleSegment(name: String): Boolean =
        name.isNotBlank() && name.length <= 128 && name != "." && name != ".." &&
            !name.startsWith(".") && name == safeName(name) &&
            name.none { it.code == 127 }

    private fun checkedSegment(name: String): String {
        require(safeVisibleSegment(name)) { "Недопустимое имя файла или папки." }
        return name
    }

    private fun verifiedDirectory(folder: TvFolder, parts: List<String>): String {
        require(parts.size <= 12) { "Превышена допустимая глубина папок." }
        var directory = folder.path
        for (part in parts) {
            checkedSegment(part)
            val entry = session.list(directory).firstOrNull { it.name == part }
            require(entry != null && entry.mode and S_IFMT == S_IFDIR) {
                "Папка не найдена или является недопустимой ссылкой."
            }
            directory += "/$part"
        }
        return directory
    }

    /** Creates one folder; never creates parents or overwrites a file. */
    suspend fun createDirectory(folder: TvFolder, parts: List<String>, name: String) = withContext(Dispatchers.IO) {
        val clean = checkedSegment(name)
        val dir = verifiedDirectory(folder, parts)
        require(session.list(dir).none { it.name == clean }) { "Такое имя уже существует." }
        val cmd = "mkdir " + shellQuote("$dir/$clean")
        val result = session.shell(cmd)
        check(result.exitCode == 0) { "Устройство отказало в создании папки." }
        check(session.list(dir).any { it.name == clean && it.mode and S_IFMT == S_IFDIR }) {
            "Устройство не подтвердило создание папки."
        }
    }

    /** Renames a file or directory in place, never overwriting an existing entry. */
    suspend fun rename(folder: TvFolder, parts: List<String>, oldName: String, newName: String) = withContext(Dispatchers.IO) {
        checkedSegment(oldName)
        val clean = checkedSegment(newName)
        require(oldName != clean) { "Укажите новое имя." }
        val dir = verifiedDirectory(folder, parts)
        val listed = session.list(dir)
        require(listed.any { it.name == oldName && (it.mode and S_IFMT) in setOf(S_IFREG, S_IFDIR) }) {
            "Файл или папка не найдены."
        }
        require(listed.none { it.name == clean }) { "Новое имя уже занято." }
        // -n is essential: concurrent changes should never overwrite user data.
        val result = session.shell("mv -n " + shellQuote("$dir/$oldName") + " " + shellQuote("$dir/$clean"))
        check(result.exitCode == 0) { "Устройство отказало в переименовании." }
        val after = session.list(dir).map { it.name }
        check(clean in after && oldName !in after) { "Переименование не подтверждено. Проверьте состояние файлов." }
    }

    /** Deletes one explicitly selected ordinary file or one EMPTY folder. Never recursive. */
    suspend fun delete(folder: TvFolder, parts: List<String>, name: String) = withContext(Dispatchers.IO) {
        val clean = checkedSegment(name)
        val dir = verifiedDirectory(folder, parts)
        val entry = session.list(dir).firstOrNull { it.name == clean }
            ?: throw IllegalArgumentException("Файл или папка уже отсутствуют.")
        val type = entry.mode and S_IFMT
        require(type == S_IFREG || type == S_IFDIR) { "Этот тип объекта запрещено удалять." }
        val path = "$dir/$clean"
        if (type == S_IFDIR) {
            require(session.list(path).isEmpty()) { "Можно удалять только пустые папки." }
        }
        val command = if (type == S_IFDIR) "rmdir " else "rm -f "
        val result = session.shell(command + shellQuote(path))
        check(result.exitCode == 0) { "Устройство отказало в удалении." }
        check(session.list(dir).none { it.name == clean }) {
            "Удаление не подтверждено. Проверьте состояние файлов."
        }
    }

    companion object {
        private const val S_IFMT = 0xF000
        private const val S_IFREG = 0x8000
        private const val S_IFDIR = 0x4000
        private const val FILE_MODE = 420 // 0644

        /** One path segment: no separators, no control characters, never "." or "..". */
        fun safeName(name: String): String {
            val cleaned = name.map { if (it == '/' || it == '\\' || it.code < 0x20) '_' else it }.joinToString("").trim()
            return cleaned.takeUnless { it.isEmpty() || it.all { c -> c == '.' } } ?: "file"
        }

        /** "clip.mp4" → "clip (1).mp4" → "clip (2).mp4" until it's free. */
        fun uniqueName(name: String, taken: Set<String>): String {
            if (name !in taken) return name
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val stem = name.substring(0, dot)
            val ext = name.substring(dot)
            return generateSequence(1) { it + 1 }.map { "$stem ($it)$ext" }.first { it !in taken }
        }
    }
}
