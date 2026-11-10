package com.xvan.unireader.local.server

import com.xvan.unireader.local.server.NoteSync.Action
import com.xvan.unireader.local.server.NoteSync.Base
import com.xvan.unireader.local.server.NoteSync.Entry
import java.io.File
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 笔记文件同步的纯逻辑：判定表逐行对 Mac `ServerNoteSync.syncOne` 的注释表，路径规则对 `SyncNotes`。 */
class NoteSyncTest {
    private fun srv(rev: Long, sha: String, deleted: Boolean = false) = Entry("Notes/a.md", rev, if (deleted) "" else sha, 1, deleted)
    private fun base(rev: Long, sha: String) = Base(rev, sha, 1, 1.0)

    @Test
    fun decideFull() {
        fun d(l: String?, b: Base?, s: Entry?) = NoteSync.decide(l, b, s, full = true)
        assertEquals("两边一样：记基线", Action.Record, d("x", null, srv(3, "x")))
        assertEquals("各自新建了同名文件", Action.Conflict, d("mine", null, srv(3, "theirs")))
        assertEquals("本机没动：下载", Action.Pull, d("old", base(2, "old"), srv(3, "new")))
        assertEquals("只有本机改了：上传", Action.Push(2), d("mine", base(2, "old"), srv(2, "old")))
        assertEquals("两边都改了", Action.Conflict, d("mine", base(2, "old"), srv(3, "theirs")))
        assertEquals("本机删了：删服务器的", Action.DeleteRemote(2), d(null, base(2, "old"), srv(2, "old")))
        assertEquals("本机删了但别处又改过：下载", Action.Pull, d(null, base(2, "old"), srv(3, "new")))
        assertEquals("新文件：下载", Action.Pull, d(null, null, srv(3, "new")))
        assertEquals("别的设备删了、本机没动：挪进 .trash", Action.TrashLocal, d("old", base(2, "old"), srv(3, "", deleted = true)))
        assertEquals("别的设备删了、本机改过：上传", Action.Push(0), d("mine", base(2, "old"), srv(3, "", deleted = true)))
        assertEquals("本机新建：上传", Action.Push(0), d("mine", null, null))
        // 🔴 服务器上压根没记录（不是墓碑）绝不当成被删
        assertEquals("服务器没记录、本机没动：上传而不是删", Action.Push(0), d("old", base(2, "old"), null))
        assertEquals("两边都没有：清基线", Action.Forget, d(null, base(2, "old"), srv(3, "", deleted = true)))
        assertEquals(Action.Forget, d(null, base(2, "old"), null))
    }

    @Test
    fun decidePushOnly() {
        fun d(l: String?, b: Base?) = NoteSync.decide(l, b, null, full = false)
        assertEquals("没变：不动", Action.None, d("x", base(2, "x")))
        assertEquals("改过：上传带基线", Action.Push(2), d("y", base(2, "x")))
        assertEquals("新建：base 0", Action.Push(0), d("y", null))
        assertEquals("本机删了：删服务器的", Action.DeleteRemote(2), d(null, base(2, "x")))
        assertEquals(Action.None, d(null, null))
    }

    @Test
    fun paths() {
        assertTrue(NoteSync.isSyncablePath("Notes/a.md"))
        assertTrue(NoteSync.isSyncablePath("Notes/数学/极限 (1)+x&y.md"))
        assertFalse("不在 Notes/ 下", NoteSync.isSyncablePath("PDFs/a.pdf"))
        assertFalse(NoteSync.isSyncablePath("Notes/.obsidian/app.json"))
        assertFalse(NoteSync.isSyncablePath("Notes/.trash/a.md"))
        assertFalse(NoteSync.isSyncablePath("Notes/Trash/a.md"))
        assertFalse(NoteSync.isSyncablePath("Notes/a.md.part"))
        assertFalse(NoteSync.isSyncablePath("Notes/"))
        assertFalse("镜像本端不管", NoteSync.isSyncablePath("NotesMirror/S1/a.md"))
        assertEquals("NFC", "Notes/é.md", NoteSync.normalize("Notes/é.md"))
    }

    @Test
    fun conflictName() {
        val at = Date(1762145400000L)
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.US).format(at)
        assertEquals("Notes/数学/极限 (冲突 平板 $stamp).md", NoteSync.conflictPath("Notes/数学/极限.md", "平板", at) { false })
        val taken = setOf("Notes/a (冲突 Pad-6- $stamp).md")
        assertEquals("重名往后加 2；设备名里的括号换掉", "Notes/a (冲突 Pad-6- $stamp 2).md",
            NoteSync.conflictPath("Notes/a.md", "Pad(6)", at) { it in taken })
        assertEquals("没有扩展名", "Notes/README (冲突 设备 $stamp)", NoteSync.conflictPath("Notes/README", "  ", at) { false })
    }

    @Test
    fun scanAndHash() {
        val ws = File(System.getProperty("java.io.tmpdir"), "notesync-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(ws, "Notes/数学").mkdirs()
            File(ws, "Notes/.trash").mkdirs()
            File(ws, "Notes/a.md").writeText("A")
            File(ws, "Notes/数学/b.md").writeText("BB")
            File(ws, "Notes/.trash/gone.md").writeText("x")
            File(ws, "Notes/.hidden.md").writeText("x")
            val items = NoteSync.scan(ws)
            assertEquals(listOf("Notes/a.md", "Notes/数学/b.md"), items.map { it.rel })
            assertEquals(2L, items[1].size)
            assertEquals("559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd", NoteSync.sha256Hex(items[0].file))
            // 基线文件的读写用到 org.json（JVM 单测里是空壳），在真机端到端里验（`ServerSyncE2ETest`）
        } finally {
            ws.deleteRecursively()
        }
    }
}
