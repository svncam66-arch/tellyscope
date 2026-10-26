// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Versioned, password-protected backup payload container.
 *
 * This is a cryptographic building block, not an ADB-key export feature:
 * callers must separately authorize which records can be exported.
 * The payload is AES-256-GCM authenticated (including its format header).
 */
object EncryptedBackupCodec {
    private val MAGIC = "TVOPBK01".toByteArray(StandardCharsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val KEY_BITS = 256
    private const val ITERATIONS = 210_000

    /** Never stores the passphrase in the output or logs. */
    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "Требуется пароль для зашифрованной резервной копии." }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(MAGIC)
        cipher.updateAAD(salt)
        cipher.updateAAD(nonce)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(MAGIC.size + SALT_BYTES + NONCE_BYTES + ciphertext.size)
            .put(MAGIC).put(salt).put(nonce).put(ciphertext).array()
    }

    /**
     * Rejects truncated, unsupported or tampered backups, and incorrect passwords.
     * Authentication is checked before any plaintext is returned.
     */
    fun decrypt(encrypted: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "Требуется пароль резервной копии." }
        require(encrypted.size >= MAGIC.size + SALT_BYTES + NONCE_BYTES + TAG_BYTES) {
            "Повреждённая или неполная резервная копия."
        }
        val input = ByteBuffer.wrap(encrypted)
        val header = ByteArray(MAGIC.size).also(input::get)
        require(Arrays.equals(header, MAGIC)) { "Неизвестный формат резервной копии." }
        val salt = ByteArray(SALT_BYTES).also(input::get)
        val nonce = ByteArray(NONCE_BYTES).also(input::get)
        val ciphertext = ByteArray(input.remaining()).also(input::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header)
        cipher.updateAAD(salt)
        cipher.updateAAD(nonce)
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        val keyBytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            SecretKeySpec(keyBytes, "AES")
        } finally {
            keyBytes.fill(0)
        }
    }
}
