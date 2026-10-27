// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Owner-signed profile cache. Entire signed envelope is one atomically replaced file,
 * rather than two independently updated signature/manifest files.
 *
 * Never trusts network metadata or a filename. On EVERY load and rollback, signature,
 * device fingerprint and owner approval are checked again. No trusted signing key
 * is provisioned until the project owner publishes one independently.
 */
class VerifiedProfileStore(private val directory: File, private val pinnedKey: String?) {
    private val magic = 0x54565031 // TVP1
    data class Imported(val activeRevision: Int, val previousRevision: Int?)

    @Synchronized
    fun current(device: DeviceCapabilities): SignedProfilePolicy.Verdict =
        read(activeFile(device), device)
            ?: SignedProfilePolicy.Verdict.Rejected("Подтверждённого профиля для прошивки нет.")

    /** Import must be explicitly requested and both files obtained through a trusted route. */
    @Synchronized
    fun import(
        manifest: ByteArray,
        signature: ByteArray,
        device: DeviceCapabilities,
    ): SignedProfilePolicy.Verdict {
        val verdict = SignedProfilePolicy.verify(manifest, signature, pinnedKey, device)
        if (verdict !is SignedProfilePolicy.Verdict.Accepted) return verdict
        val active = activeFile(device)
        val previous = previousFile(device)
        val existing = current(device) as? SignedProfilePolicy.Verdict.Accepted
        if (existing != null && verdict.profile.revision <= existing.profile.revision) {
            return SignedProfilePolicy.Verdict.Rejected(
                "Новая версия профиля должна иметь больший номер; для отката есть отдельная команда.",
            )
        }
        val envelope = pack(manifest, signature)
        directory.mkdirs()
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        val stage = File.createTempFile(".tvop-profile-", ".tmp", directory)
        try {
            stage.writeBytes(envelope)
            // Keep last known, independently verified signed profile for manual rollback.
            if (existing != null) {
                val backupStage = File.createTempFile(".tvop-profile-prev-", ".tmp", directory)
                try {
                    active.copyTo(backupStage, overwrite = true)
                    Files.move(
                        backupStage.toPath(), previous.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                    )
                } finally {
                    backupStage.delete()
                }
            }
            Files.move(stage.toPath(), active.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            stage.delete()
        }
        return current(device)
    }

    /** Explicit rollback; never silently re-enable a profile from a different build. */
    @Synchronized
    fun rollback(device: DeviceCapabilities): SignedProfilePolicy.Verdict {
        val previous = previousFile(device)
        val oldVerdict = read(previous, device)
            ?: return SignedProfilePolicy.Verdict.Rejected("Нет проверенной предыдущей версии.")
        if (oldVerdict !is SignedProfilePolicy.Verdict.Accepted) return oldVerdict
        val active = activeFile(device)
        val stage = File.createTempFile(".tvop-profile-rollback-", ".tmp", directory)
        try {
            previous.copyTo(stage, overwrite = true)
            Files.move(stage.toPath(), active.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            // Re-importing a newer signed revision is required after rollback.
            previous.delete()
        } finally { stage.delete() }
        return current(device)
    }

    private fun activeFile(device: DeviceCapabilities) = File(directory, filePrefix(device) + ".active")
    private fun previousFile(device: DeviceCapabilities) = File(directory, filePrefix(device) + ".previous")

    private fun filePrefix(device: DeviceCapabilities): String {
        val value = listOf(device.manufacturer, device.model, device.firmwareFingerprint, device.sdk)
            .joinToString("\u0000") { it?.toString().orEmpty() }
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun read(file: File, device: DeviceCapabilities): SignedProfilePolicy.Verdict? {
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() !in 1L..70_000L) return null
        return try {
            val input = DataInputStream(ByteArrayInputStream(file.readBytes()))
            input.use {
                require(it.readInt() == magic)
                val length = it.readInt()
                require(length in 1..65_536 && length <= it.available())
                val manifest = ByteArray(length).also(it::readFully)
                val signatureSize = it.readInt()
                require(signatureSize in 256..1024 && signatureSize == it.available())
                val signature = ByteArray(signatureSize).also(it::readFully)
                SignedProfilePolicy.verify(manifest, signature, pinnedKey, device)
            }
        } catch (_: Exception) {
            SignedProfilePolicy.Verdict.Rejected("Файл профиля повреждён или неподдерживаем.")
        }
    }

    private fun pack(manifest: ByteArray, signature: ByteArray): ByteArray {
        require(manifest.size in 1..65_536 && signature.size in 256..1024)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeInt(magic)
            it.writeInt(manifest.size)
            it.write(manifest)
            it.writeInt(signature.size)
            it.write(signature)
        }
        return out.toByteArray()
    }
}
