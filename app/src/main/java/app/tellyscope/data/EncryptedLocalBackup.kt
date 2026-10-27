// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Portable, authenticated local backup of explicitly approved app-owned metadata.
 * Never includes ADB private keys, tokens, keystores, downloaded untrusted profiles,
 * caches or application data of other packages.
 *
 * Does not modify a TV. Restore replaces selected private metadata files only;
 * callers must obtain explicit approval and restart their ViewModel afterwards.
 */
object EncryptedLocalBackup {
    const val MAX_PER_FILE = 2_000_000
    private const val MAX_PLAINTEXT = 8_000_000
    private const val MAGIC = "TVOPMETA01"
    val includedFiles: Set<String> = setOf(
        "devices.tsv", "history.tsv", "names.tsv", "folds.properties", "updates.properties"
    )

    data class Preview(val contents: Map<String, ByteArray>) {
        val names: Set<String> get() = contents.keys
    }

    fun export(filesDir: File, password: CharArray): ByteArray {
        val data = linkedMapOf<String, ByteArray>()
        for (name in includedFiles.sorted()) {
            val f = File(filesDir, name)
            if (!f.exists()) continue
            require(f.isFile && f.length() in 0..MAX_PER_FILE.toLong()) {
                "Файл резервной копии слишком велик или недоступен: $name"
            }
            data[name] = f.readBytes()
        }
        return EncryptedBackupCodec.encrypt(encode(data), password)
    }

    fun preview(data: ByteArray, password: CharArray): Preview =
        Preview(decode(EncryptedBackupCodec.decrypt(data, password)))

    /** Caller must explicitly select entries; defaults to none, not all. */
    fun restore(filesDir: File, backup: ByteArray, password: CharArray, selected: Set<String>): Set<String> =
        restorePreview(filesDir, preview(backup, password), selected)

    /**
     * Restore selected files using a two-phase transaction. Before replacing a single
     * live metadata file, stage and back up *all* selected entries in the same directory.
     * If any replacement fails, restore every changed file in reverse order.
     *
     * A crash during a multi-file transaction is not fully covered: see QA release gate.
     * If automatic rollback fails, the .tvop-rollback copies are intentionally retained.
     */
    fun restorePreview(filesDir: File, preview: Preview, selected: Set<String>): Set<String> {
        require(filesDir.isDirectory) { "Недоступен каталог приложения." }
        val records = preview.contents
        require(selected.all { it in includedFiles && it in records }) { "Выбран неизвестный раздел архива." }
        val names = selected.sorted()
        // Reject partial/invalid archives before the first file is touched.
        for (name in names) {
            val bytes = records.getValue(name)
            require(bytes.size <= MAX_PER_FILE) { "Превышен размер раздела: $name" }
            val destination = File(filesDir, name)
            require(!destination.exists() || (destination.isFile && !Files.isSymbolicLink(destination.toPath()))) {
                "Текущий раздел недоступен или является ссылкой: $name"
            }
        }

        val staged = linkedMapOf<String, File>()
        val backups = linkedMapOf<String, File?>()
        val replaced = mutableListOf<String>()
        var rollbackFailed = false
        try {
            for (name in names) {
                val stage = File.createTempFile(".tvop-stage-", ".tmp", filesDir)
                staged[name] = stage
                stage.writeBytes(records.getValue(name))
            }
            // Every pre-existing target is saved before any replacement starts.
            for (name in names) {
                val original = File(filesDir, name)
                backups[name] = if (original.exists()) {
                    File.createTempFile(".tvop-rollback-", ".bak", filesDir).also {
                        Files.copy(original.toPath(), it.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } else null
            }
            for (name in names) {
                Files.move(
                    staged.getValue(name).toPath(), File(filesDir, name).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
                replaced += name
            }
            return names.toSet()
        } catch (failure: Exception) {
            for (name in replaced.asReversed()) {
                try {
                    val original = backups[name]
                    val target = File(filesDir, name).toPath()
                    if (original == null) Files.deleteIfExists(target)
                    else Files.move(original.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: Exception) {
                    rollbackFailed = true
                }
            }
            if (rollbackFailed) throw IOException(
                "Автоматический откат не завершён. Сохранены файлы .tvop-rollback-*.bak для ручного восстановления.",
                failure,
            )
            throw failure
        } finally {
            staged.values.forEach { it.delete() }
            // Never destroy original backup data when a partial rollback could not finish.
            if (!rollbackFailed) backups.values.filterNotNull().forEach { it.delete() }
        }
    }

    private fun encode(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeUTF(MAGIC)
            d.writeInt(files.size)
            for ((name, bytes) in files) {
                require(name in includedFiles) { "Недопустимое имя файла архива." }
                require(bytes.size <= MAX_PER_FILE) { "Файл превышает лимит резервной копии." }
                d.writeUTF(name)
                d.writeInt(bytes.size)
                d.write(bytes)
            }
        }
        require(out.size() <= MAX_PLAINTEXT) { "Резервная копия превышает безопасный размер." }
        return out.toByteArray()
    }

    private fun decode(payload: ByteArray): Map<String, ByteArray> {
        require(payload.size <= MAX_PLAINTEXT) { "Архив превышает допустимый размер." }
        DataInputStream(ByteArrayInputStream(payload)).use { d ->
            require(d.readUTF() == MAGIC) { "Неизвестная версия архива." }
            val n = d.readInt()
            require(n in 0..includedFiles.size) { "Недопустимое количество записей архива." }
            val files = linkedMapOf<String, ByteArray>()
            repeat(n) {
                val name = d.readUTF()
                require(name in includedFiles && name !in files) { "Архив содержит дубликат или запрещённое имя." }
                val size = d.readInt()
                require(size in 0..MAX_PER_FILE && size <= d.available()) {
                    "Запись архива повреждена или слишком велика."
                }
                files[name] = ByteArray(size).also { d.readFully(it) }
            }
            require(d.available() == 0) { "Архив содержит неподдерживаемые данные." }
            return files
        }
    }
}
