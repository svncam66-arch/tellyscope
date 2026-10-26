// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.assertContentEquals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Each filesystem operation stays in allowed public folders and cannot overwrite data. */
class FilesManagementTest {
    @Test fun createsNewFolderAndThenCanBrowseInside() = runTest {
        val sh = FakeShell()
        sh.answer = { command ->
            if (command == "mkdir '/sdcard/Download/My Folder'") {
                sh.transfer.directories += "/sdcard/Download/My Folder"
                ok("")
            } else null
        }
        val files = Files(sh)
        files.createDirectory(TvFolder.DOWNLOAD, emptyList(), "My Folder")
        assertTrue(files.list(TvFolder.DOWNLOAD).any { it.name == "My Folder" && it.isDirectory })
        sh.transfer.files["/sdcard/Download/My Folder/a.txt"] = byteArrayOf(1,2)
        assertEquals("a.txt", files.list(TvFolder.DOWNLOAD, listOf("My Folder")).single().name)
    }

    @Test fun cannotCreateFolderOutsidePublicPathOrThroughUnknownDirectory() = runTest {
        val sh = FakeShell()
        val files = Files(sh)
        assertFailsWith<IllegalArgumentException> {
            files.createDirectory(TvFolder.DOWNLOAD, listOf(".."), "out")
        }
        assertFailsWith<IllegalArgumentException> {
            files.createDirectory(TvFolder.MOVIES, emptyList(), "../../data")
        }
        assertFailsWith<IllegalArgumentException> {
            files.createDirectory(TvFolder.DOWNLOAD, listOf("link"), "inside")
        }
        assertTrue(sh.ran.isEmpty(), "No shell operation may run for an invalid path")
    }

    @Test fun safeRenameChangesOnlyTheSelectedFile() = runTest {
        val sh = FakeShell()
        sh.transfer.files["/sdcard/Movies/old.mp4"] = byteArrayOf(5)
        sh.answer = { cmd ->
            if (cmd == "mv -n '/sdcard/Movies/old.mp4' '/sdcard/Movies/new.mp4'") {
                sh.transfer.files["/sdcard/Movies/new.mp4"] =
                    sh.transfer.files.remove("/sdcard/Movies/old.mp4")!!
                ok("")
            } else null
        }
        Files(sh).rename(TvFolder.MOVIES, emptyList(), "old.mp4", "new.mp4")
        assertTrue("mv -n '/sdcard/Movies/old.mp4' '/sdcard/Movies/new.mp4'" in sh.ran)
        assertEquals(byteArrayOf(5).toList(), sh.transfer.files["/sdcard/Movies/new.mp4"]?.toList())
    }

    @Test fun collisionPreventsRenameOrOverwrite() = runTest {
        val sh = FakeShell()
        sh.transfer.files["/sdcard/Download/a.txt"] = byteArrayOf(1)
        sh.transfer.files["/sdcard/Download/b.txt"] = byteArrayOf(2)
        assertFailsWith<IllegalArgumentException> {
            Files(sh).rename(TvFolder.DOWNLOAD, emptyList(), "a.txt", "b.txt")
        }
        assertTrue(sh.ran.none { it.startsWith("mv ") })
    }

    @Test fun removesOneFileOnlyAfterExactCheck() = runTest {
        val sh = FakeShell()
        sh.transfer.files["/sdcard/Download/unwanted.txt"] = byteArrayOf(1)
        sh.transfer.files["/sdcard/Download/keep.txt"] = byteArrayOf(2)
        sh.answer = { cmd ->
            if (cmd == "rm -f '/sdcard/Download/unwanted.txt'") {
                sh.transfer.files.remove("/sdcard/Download/unwanted.txt")
                ok("")
            } else null
        }
        Files(sh).delete(TvFolder.DOWNLOAD, emptyList(), "unwanted.txt")
        assertEquals(1, sh.ran.count { it.startsWith("rm ") })
        assertTrue("/sdcard/Download/keep.txt" in sh.transfer.files)
    }

    @Test fun refusesRecursiveDeletionOfNonemptyDirectories() = runTest {
        val sh = FakeShell()
        sh.transfer.directories += "/sdcard/Download/My Folder"
        sh.transfer.files["/sdcard/Download/My Folder/keep.txt"] = byteArrayOf(3)
        assertFailsWith<IllegalArgumentException> {
            Files(sh).delete(TvFolder.DOWNLOAD, emptyList(), "My Folder")
        }
        assertTrue(sh.ran.none { it.startsWith("rmdir ") || it.startsWith("rm ") })
    }

    @Test fun sendsAndDownloadsFileInsideVerifiedNestedFolder() = runTest {
        val sh = FakeShell()
        sh.transfer.directories += "/sdcard/Movies/Sub"
        sh.transfer.directories += "/sdcard/Movies/Sub/Other"
        val source = byteArrayOf(10, 11, 12)
        val saved = Files(sh).push(
            Buffer().write(source), source.size.toLong(), "clip.bin",
            TvFolder.MOVIES, listOf("Sub", "Other"),
        )
        assertEquals("clip.bin", saved)
        assertContentEquals(source, sh.transfer.files["/sdcard/Movies/Sub/Other/clip.bin"])
        val file = Files(sh).list(TvFolder.MOVIES, listOf("Sub", "Other")).single()
        val result = Buffer()
        Files(sh).pull(TvFolder.MOVIES, file, result, listOf("Sub", "Other"))
        assertContentEquals(source, result.readByteArray())
    }

    @Test fun cannotReadThroughNonDirectoryEntryOrEscapeIntoParent() = runTest {
        val sh = FakeShell()
        sh.transfer.files["/sdcard/Download/link"] = byteArrayOf(1)
        assertFailsWith<IllegalArgumentException> {
            Files(sh).list(TvFolder.DOWNLOAD, listOf("link"))
        }
        assertFailsWith<IllegalArgumentException> {
            Files(sh).list(TvFolder.DOWNLOAD, listOf(".."))
        }
        assertTrue(sh.ran.isEmpty())
    }

    @Test fun neverMutatesHiddenOrUnknownEntries() = runTest {
        val sh = FakeShell()
        sh.transfer.files["/sdcard/Download/.nomedia"] = byteArrayOf(1)
        assertFailsWith<IllegalArgumentException> {
            Files(sh).delete(TvFolder.DOWNLOAD, emptyList(), ".nomedia")
        }
        assertFailsWith<IllegalArgumentException> {
            Files(sh).rename(TvFolder.DOWNLOAD, emptyList(), "missing", "exists")
        }
        assertTrue(sh.ran.isEmpty())
    }
}
