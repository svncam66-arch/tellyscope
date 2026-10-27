// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.EncryptedAdbKeyBackup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class EncryptedAdbKeyBackupTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun separateAdbKeyExportIsEncryptedAndCorrectlyRecovered() {
        val originalKey = "private-key-material-should-never-be-plaintext".toByteArray()
        val dir = temp.newFolder("private")
        File(dir, "adbkey.pem").writeBytes(originalKey)
        val pass = "совершенно-другой-пароль".toCharArray()
        val data = EncryptedAdbKeyBackup.export(dir, pass)
        assertFalse(data.contentEquals(originalKey))
        assertFalse(String(data, Charsets.ISO_8859_1).contains("private-key-material"))
        assertArrayEquals(originalKey, EncryptedAdbKeyBackup.inspectEncrypted(data, pass))
    }

    @Test fun wrongPasswordCannotDecryptIdentity() {
        val dir = temp.newFolder("wrong")
        File(dir, "adbkey.pem").writeText("test-private-key")
        val bytes = EncryptedAdbKeyBackup.export(dir, "достаточно-сложный-пароль".toCharArray())
        assertThrows(Exception::class.java) {
            EncryptedAdbKeyBackup.inspectEncrypted(bytes, "неверный-пароль-отдельный".toCharArray())
        }
    }

    @Test fun refusesAbsentKeyOrShortPassword() {
        val dir = temp.newFolder("missing")
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedAdbKeyBackup.export(dir, "короткий".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedAdbKeyBackup.export(dir, "длинный-пароль-разрешён".toCharArray())
        }
    }

    @Test fun symlinkCannotExposeAnotherLocalFile() {
        val dir = temp.newFolder("links")
        val secret = File(temp.root, "not-adb-private.txt").apply { writeText("private") }
        Files.createSymbolicLink(File(dir, "adbkey.pem").toPath(), secret.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedAdbKeyBackup.export(dir, "отдельный-длинный-пароль".toCharArray())
        }
    }
}
