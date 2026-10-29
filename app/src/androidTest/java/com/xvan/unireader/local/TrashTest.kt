package com.xvan.unireader.local

import androidx.test.platform.app.InstrumentationRegistry
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.NoteKind
import com.xvan.unireader.local.store.newBoardId
import com.xvan.unireader.shared.NOTE_TAP
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回收站的库回环（`Trash` + `TrashStore`），对应 Mac `../spike/trash-test.swift` 的主干：
 * 归档 → 删除 → 恢复（情形 A 照常放回 / 情形 B 同一份 PDF 已重新导入 → 并入那一篇）、画板、manifest 回环、图片护住。
 * 全程在 `cacheDir` 里做。
 */
class TrashTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun freshWs(name: String): File {
        val parent = File(ctx.cacheDir, "trashtest").apply { mkdirs() }
        File(parent, "$name.unrd").deleteRecursively()
        return Workspace.create(parent, name)
    }

    @Test
    fun 文档归档删除再恢复() {
        val ws = freshWs("doc")
        LibraryStore.open(ws).use { s ->
            val d = s.findOrCreate("HASH-A", "高等数学", 12, "PDFs/a.pdf")
            s.upsertTextNote(d.id, "N1", 0, 0.2f, 0.2f, "笔记")
            s.insertImageNote(d.id, "I1", 1, floatArrayOf(0.5f, 0.5f, 0f, 0f), "SHA1", "", NOTE_TAP, JSONObject().put("kind", "file"))
            val e = Trash.deleteDocument(s, ws, d)
            assertNull("库里没了", s.document(d.id))
            assertTrue(e.snapshot.isFile)
            assertEquals(2, e.manifest.counts.total)
            assertEquals(listOf("SHA1"), e.manifest.images)
            assertEquals(listOf("HASH-A"), e.manifest.contentHashes)
            // manifest 回环
            val m = Trash.scan(ws).single().manifest
            assertEquals("高等数学", m.title)
            assertEquals(Trash.KIND_DOCUMENT, m.kind)
            assertEquals(setOf("SHA1"), Trash.protectedImages(ws))
            // 恢复（情形 A）
            val r = Trash.restore(s, Trash.scan(ws).single())
            assertNull(r.mergedInto)
            assertNotNull(s.document(d.id))
            assertEquals(setOf("N1", "I1"), s.notes(d.id).map { it.id }.toSet())
            assertTrue("条目清掉了", Trash.scan(ws).isEmpty())
        }
    }

    @Test
    fun 同一份PDF重新导入过就并入那一篇() {
        val ws = freshWs("merge")
        LibraryStore.open(ws).use { s ->
            val d = s.findOrCreate("HASH-B", "线代", 5, "PDFs/b.pdf")
            s.upsertTextNote(d.id, "N2", 0, 0.2f, 0.2f, "旧笔记")
            Trash.deleteDocument(s, ws, d)
            val again = s.findOrCreate("HASH-B", "线代（重新导入）", 5, "PDFs/b2.pdf")
            assertTrue(again.id != d.id)
            val r = Trash.restore(s, Trash.scan(ws).single())
            assertEquals(again.id, r.mergedInto)
            assertNull("原来那篇不复活（并入）", s.document(d.id))
            assertEquals("线代（重新导入）", s.document(again.id)!!.title)
            assertEquals(listOf("N2"), s.notes(again.id).filter { it.kind == NoteKind.TEXT }.map { it.id })
        }
    }

    @Test
    fun 画板归档删除再恢复() {
        val ws = freshWs("board")
        LibraryStore.open(ws).use { s ->
            val id = newBoardId()
            val now = com.xvan.unireader.local.store.Iso.now()
            val b = com.xvan.unireader.local.store.BoardNote(
                id = id, title = "草图", bg = "rgba(255,255,255,1.0)", pattern = "dots", groupName = "",
                createdAt = now, updatedAt = now, lastOpenedAt = null,
            )
            BoardController.createBoard(s, b, null)
            s.insertBoardImage(id, newBoardId(), "SHA2", 0f, 0f, 100f, 50f, "x.png")
            val e = Trash.deleteBoard(s, ws, s.board(id)!!, "草图")
            assertNull(s.board(id))
            assertEquals(1, e.manifest.counts.boardImage)
            Trash.restore(s, Trash.scan(ws).single())
            assertNotNull(s.board(id))
            assertEquals(1, s.boardItems(id).size)
        }
    }

    @Test
    fun 目录名与净化同Mac() {
        val d = java.util.Calendar.getInstance().apply { set(2026, 8, 21, 14, 3, 11) }.time
        assertEquals("2026-09-21T14-03-11_高等数学", Trash.directoryName(d, "高等数学", emptySet()))
        assertEquals("2026-09-21T14-03-11_高等数学-2", Trash.directoryName(d, "高等数学", setOf("2026-09-21T14-03-11_高等数学")))
        assertEquals("a-b", Trash.sanitize("..a/b"))
    }
}
