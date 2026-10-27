// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.EncryptedBackupCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.util.Arrays

class EncryptedBackupCodecTest {
    private val secret = "Профиль и настройки ТВ".toByteArray(Charsets.UTF_8)

    @Test fun encryptedDataRoundTrips() {
        val encrypted = EncryptedBackupCodec.encrypt(secret, "длинный уникальный пароль".toCharArray())
        assertFalse(encrypted.contentEquals(secret))
        assertArrayEquals(secret, EncryptedBackupCodec.decrypt(encrypted, "длинный уникальный пароль".toCharArray()))
    }

    @Test fun wrongPasswordCannotReadBackup() {
        val encrypted = EncryptedBackupCodec.encrypt(secret, "правильный пароль".toCharArray())
        assertThrows(GeneralSecurityException::class.java) {
            EncryptedBackupCodec.decrypt(encrypted, "неверный пароль".toCharArray())
        }
    }

    @Test fun modifyingAuthenticatedDataIsRejected() {
        val encrypted = EncryptedBackupCodec.encrypt(secret, "пароль".toCharArray())
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) {
            EncryptedBackupCodec.decrypt(encrypted, "пароль".toCharArray())
        }
    }

    @Test fun everyBackupHasFreshRandomSaltAndNonce() {
        val password = "пароль".toCharArray()
        val first = EncryptedBackupCodec.encrypt(secret, password)
        val second = EncryptedBackupCodec.encrypt(secret, password)
        assertFalse(first.contentEquals(second))
    }

    @Test fun malformedAndUnsupportedPayloadsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackupCodec.decrypt(byteArrayOf(1,2), "пароль".toCharArray())
        }
        val encrypted = EncryptedBackupCodec.encrypt(secret, "пароль".toCharArray())
        encrypted[0] = 0
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackupCodec.decrypt(encrypted, "пароль".toCharArray())
        }
    }
}
