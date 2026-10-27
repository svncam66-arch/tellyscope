// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import java.io.File
import java.nio.file.Files

/**
 * Explicit export only. Encrypted backups of the ADB identity are NEVER mixed
 * with the ordinary settings backup and require their own password.
 *
 * Original adbkey.pem is managed by Kadb; encrypting that live file at rest
 * requires a separate custom Kadb keystore integration (unimplemented stable gate).
 */
object EncryptedAdbKeyBackup {
    private val MAGIC = "TVOP-ADB-KEY-V1\n".toByteArray(Charsets.US_ASCII)
    private const val MAX_BYTES = 128 * 1024

    fun export(appFiles: File, password: CharArray): ByteArray {
        require(password.size >= 12) { "Используйте отдельный пароль длиной от 12 символов." }
        val keyFile = File(appFiles, "adbkey.pem")
        require(keyFile.isFile && !Files.isSymbolicLink(keyFile.toPath()) &&
            keyFile.length() in 1..MAX_BYTES.toLong()) {
            "Личный ключ ADB недоступен, повреждён или имеет недопустимый размер."
        }
        val raw = keyFile.readBytes()
        val wrapped = MAGIC + raw
        return try {
            EncryptedBackupCodec.encrypt(wrapped, password)
        } finally {
            raw.fill(0)
            wrapped.fill(0)
        }
    }

    /** For validation only: returns plaintext key bytes; callers must clear them. */
    fun inspectEncrypted(data: ByteArray, password: CharArray): ByteArray {
        val plain = EncryptedBackupCodec.decrypt(data, password)
        return try {
            require(plain.size in (MAGIC.size + 1)..(MAGIC.size + MAX_BYTES))
            require(plain.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                "Архив не содержит отдельного ключа ADB."
            }
            plain.copyOfRange(MAGIC.size, plain.size)
        } finally {
            plain.fill(0)
        }
    }
}
