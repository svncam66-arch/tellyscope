// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.EncryptedLocalBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EncryptedLocalBackupTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun exportExcludesAdbKeysAndRestoresOnlySelectedMetadata() {
        val original = files.newFolder("original")
        File(original, "history.tsv").writeText("history contents")
        File(original, "devices.tsv").writeText("private device id")
        File(original, "adbkey.pem").writeText("TOP SECRET")
        val pass = "очень-надёжный-пароль".toCharArray()
        val archive = EncryptedLocalBackup.export(original, pass)
        assertFalse(String(archive, Charsets.ISO_8859_1).contains("TOP SECRET"))
        val preview = EncryptedLocalBackup.preview(archive, pass)
        assertEquals(setOf("history.tsv", "devices.tsv"), preview.names)
        val target = files.newFolder("restored")
        EncryptedLocalBackup.restore(target, archive, pass, setOf("history.tsv"))
        assertEquals("history contents", File(target, "history.tsv").readText())
        assertFalse(File(target, "devices.tsv").exists())
        assertFalse(File(target, "adbkey.pem").exists())
    }

    @Test fun restoreRejectsUnauthorizedPath() {
        val original = files.newFolder("source")
        val pass = "достаточно-длинный-пароль".toCharArray()
        val archive = EncryptedLocalBackup.export(original, pass)
        val target = files.newFolder("target")
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedLocalBackup.restore(target, archive, pass, setOf("../adbkey.pem"))
        }
    }

    @Test fun wrongPasswordNeverProducesPreview() {
        val original = files.newFolder("source")
        val archive = EncryptedLocalBackup.export(original, "секретный-длинный-пароль".toCharArray())
        assertThrows(Exception::class.java) {
            EncryptedLocalBackup.preview(archive, "неправильный-длинный-пароль".toCharArray())
        }
    }

    @Test fun untouchedFilesAreNotOverwritten() {
        val source = files.newFolder("source")
        File(source,"history.tsv").writeText("new")
        val pass = "защищённый-длинный-пароль".toCharArray()
        val archive = EncryptedLocalBackup.export(source,pass)
        val target = files.newFolder("target")
        File(target,"history.tsv").writeText("old")
        assertTrue(EncryptedLocalBackup.restore(target, archive, pass, emptySet()).isEmpty())
        assertEquals("old",File(target,"history.tsv").readText())
    }
}
