package com.xvan.unireader.local.store

import androidx.test.platform.app.InstrumentationRegistry
import com.xvan.unireader.local.ImageAssets
import com.xvan.unireader.local.MdNotes
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.shared.HL_BOX
import com.xvan.unireader.shared.HL_FILL
import com.xvan.unireader.shared.HL_UNDERLINE
import com.xvan.unireader.shared.NOTE_ALWAYS
import com.xvan.unireader.shared.NOTE_TAP
import com.xvan.unireader.shared.PadConst
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模式1 与 Mac 对齐的笔记功能（`../TODO.md` 差距清单 A1~A6）的库层插桩测试：
 * 高亮画法 / 换色 / 转批注、笔记的类型 / 颜色 / 画法 / 卡片、笔记类型整表存取、图片引用计数 / 待删除 / 清理、`md_doc` 对账。
 * 键名与取值逐条对 Mac 的 payload 契约（`HighlightModel` / `TextNoteModel` / `NoteTypeModel` / `ImageNoteModel`）。
 *
 * 全程在 `cacheDir` 里做，不需要存储权限。
 */
class NoteFeaturesStoreTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun freshWs(name: String): File {
        val parent = File(ctx.cacheDir, "notefeat").apply { mkdirs() }
        File(parent, "$name.unrd").deleteRecursively()
        return Workspace.create(parent, name)
    }

    /** 造一篇文档（不需要真 PDF：note 表只认 document 行；hash 随便给一个不撞的） */
    private fun doc(s: LibraryStore): String =
        s.findOrCreate(java.util.UUID.randomUUID().toString().replace("-", ""), "测试", 3, "PDFs/x.pdf").id

    private fun json(b: ByteArray) = JSONObject(String(b, StandardCharsets.UTF_8))

    @Test
    fun 高亮的画法与换色按Mac的键写() {
        LibraryStore.open(freshWs("hl")).use { s ->
            val d = doc(s)
            s.insertHighlight(d, "H1", 0, floatArrayOf(0.1f, 0.1f, 0.3f, 0.02f), "原文", listOf(floatArrayOf(0.1f, 0.1f, 0.3f, 0.02f)),
                intArrayOf(255, 214, 40), HL_UNDERLINE)
            var p = json(s.noteById("H1")!!.payload)
            assertEquals("underline", p.getString("style"))
            s.updateHighlight("H1", intArrayOf(120, 190, 255), HL_BOX)
            p = json(s.noteById("H1")!!.payload)
            assertEquals("box", p.getString("style"))
            assertEquals(120, p.getJSONObject("color").getInt("r"))
            assertEquals("原文", p.getString("quote"))   // 别的键原样留着
            val f = s.textFills(d).single()
            assertEquals(HL_BOX, f.style)
            assertEquals("H1", f.highlightId)
            assertEquals(PadConst.FILL.STROKE_A, f.a, 1e-6f)
        }
    }

    @Test
    fun 高亮转批注是一个事务且带上颜色画法() {
        LibraryStore.open(freshWs("hl2note")).use { s ->
            val d = doc(s)
            s.insertHighlight(d, "H2", 2, floatArrayOf(0.2f, 0.3f, 0.4f, 0.03f), "被批注", listOf(floatArrayOf(0.2f, 0.3f, 0.4f, 0.03f)),
                intArrayOf(150, 220, 120), HL_FILL)
            assertTrue(s.highlightToNote("H2", "N2", "我的批注", NOTE_TAP, NoteMarks(null, intArrayOf(150, 220, 120), HL_UNDERLINE)))
            assertNull(s.noteById("H2"))
            val n = s.textNotes(d).single()
            assertEquals("N2", n.id)
            assertEquals(2L, n.page)
            assertEquals("被批注", n.quote)
            assertEquals(HL_UNDERLINE, n.style)
            assertEquals(150, n.color!![0])
            assertTrue("选区注解：anchor 宽 > 0", n.aw > 0f)
        }
    }

    @Test
    fun 编辑器改类型颜色画法且选区注解空正文留着() {
        LibraryStore.open(freshWs("marks")).use { s ->
            val d = doc(s)
            s.insertSelectionNote(d, "S1", 0, floatArrayOf(0.1f, 0.1f, 0.2f, 0.02f), "q", listOf(floatArrayOf(0.1f, 0.1f, 0.2f, 0.02f)), "正文")
            s.upsertTextNote(d, "S1", 0, 0.1f, 0.1f, "", NOTE_ALWAYS, NoteMarks("T1", intArrayOf(255, 150, 190), HL_BOX))
            val p = json(s.noteById("S1")!!.payload)
            assertEquals("", p.getString("text"))
            assertEquals("T1", p.getString("type_id"))
            assertEquals("box", p.getString("style"))
            assertEquals("always", p.getString("display"))
            // 通用 + 跟类型色：两个键都去掉（同 Mac 编码 nil 时不写）
            s.upsertTextNote(d, "S1", 0, 0.1f, 0.1f, "x", NOTE_TAP, NoteMarks(null, null, HL_FILL))
            val q = json(s.noteById("S1")!!.payload)
            assertFalse(q.has("type_id"))
            assertFalse(q.has("color"))
            // 点注解空正文 = 删除
            s.upsertTextNote(d, "P1", 0, 0.5f, 0.5f, "点注解")
            s.upsertTextNote(d, "P1", 0, 0.5f, 0.5f, "", NOTE_TAP, NoteMarks(null, null, HL_FILL))
            assertNull(s.noteById("P1"))
        }
    }

    @Test
    fun 卡片键读得出来() {
        LibraryStore.open(freshWs("card")).use { s ->
            val d = doc(s)
            s.upsertTextNote(d, "C1", 0, 0.5f, 0.5f, "卡片")
            val row = s.noteById("C1")!!
            val o = json(row.payload).put("card", JSONObject().put("dx", 12).put("dy", -8).put("w", 300))
            s.upsertNote(row.copy(payload = o.toString().toByteArray()))
            val c = s.textNotes(d).single().card!!
            assertEquals(12f, c.dx, 0f)
            assertEquals(-8f, c.dy, 0f)
            assertEquals(300f, c.w!!, 0f)
            assertNull(c.h)
        }
    }

    @Test
    fun 笔记类型整表存取与用量() {
        LibraryStore.open(freshWs("types")).use { s ->
            val d = doc(s)
            val t = NoteTypeDef("ABC-1", "重点", "red", "flame")
            s.saveNoteTypes(listOf(t, NoteTypeDef(NoteTypeDef.GENERAL_ID, "x", "gray", "note.text")))
            assertEquals(listOf(t), s.noteTypes())   // 通用不落库
            val arr = org.json.JSONArray(s.meta("note_types"))
            assertEquals("color_key 键名", "red", arr.getJSONObject(0).getString("color_key"))
            assertEquals("icon_name 键名", "flame", arr.getJSONObject(0).getString("icon_name"))
            s.upsertTextNote(d, "U1", 0, 0.5f, 0.5f, "a", NOTE_TAP, NoteMarks("abc-1", null, HL_FILL))
            assertEquals(1, s.noteTypeUsage("ABC-1"))   // 比较忽略大小写
            assertEquals(intArrayOf(255, 59, 48).toList(), s.noteTypeColors()["ABC-1"]!!.toList())
        }
    }

    @Test
    fun 图片引用计数待删除与清理() {
        val ws = freshWs("img")
        LibraryStore.open(ws).use { s ->
            val d = doc(s)
            val bmp = android.graphics.Bitmap.createBitmap(40, 20, android.graphics.Bitmap.Config.ARGB_8888)
            val p = ImageAssets.prepare(bmp)
            ImageAssets.write(p, ws)
            assertTrue(ImageAssets.file(ws, p.sha256, "png").isFile)
            s.insertImageIfAbsent(LibImage(p.sha256, p.ext, p.width, p.height, p.data.size.toLong(), Iso.now(), null))
            val src = JSONObject().put("kind", "file").put("name", "a.png")
            s.insertImageNote(d, "I1", 0, floatArrayOf(0.5f, 0.5f, 0f, 0f), p.sha256, "说明", NOTE_TAP, src)
            s.insertImageNote(d, "I2", 1, floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f), p.sha256, "", NOTE_TAP, src)
            assertEquals(2, s.imageRefCounts()[p.sha256])
            assertEquals(2, s.imageNotes(d).size)
            assertTrue(s.imageNotes(d).first { it.id == "I2" }.boxed)
            s.deleteImageNote("I1")
            assertNull("还有一处引用，不进待删除", s.image(p.sha256)!!.orphanedAt)
            s.deleteImageNote("I2")
            assertNotNull("没人引用了：进待删除", s.image(p.sha256)!!.orphanedAt)
            assertTrue("30 天没到不清", s.purgeableImages("2000-01-01T00:00:00.000Z").isEmpty())
            assertEquals(1, s.purgeableImages("2999-01-01T00:00:00.000Z").size)
            // 再引用同一张图：待删除清零
            s.insertImageNote(d, "I3", 0, floatArrayOf(0.5f, 0.5f, 0f, 0f), p.sha256, "", NOTE_TAP, src)
            assertNull(s.image(p.sha256)!!.orphanedAt)
        }
    }

    @Test
    fun 撤销恢复注解行并对账图片() {
        val ws = freshWs("undo")
        LibraryStore.open(ws).use { s ->
            val d = doc(s)
            val p = ImageAssets.prepare(android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888))
            s.insertImageIfAbsent(LibImage(p.sha256, p.ext, p.width, p.height, p.data.size.toLong(), Iso.now(), null))
            val src = JSONObject().put("kind", "file").put("name", "a.png")
            s.insertImageNote(d, "IMG", 0, floatArrayOf(0.5f, 0.5f, 0f, 0f), p.sha256, "", NOTE_TAP, src)
            s.insertHighlight(d, "HL", 0, floatArrayOf(0.1f, 0.1f, 0.2f, 0.02f), "q", listOf(floatArrayOf(0.1f, 0.1f, 0.2f, 0.02f)), intArrayOf(255, 214, 40))
            s.upsertTextNote(d, "TN", 0, 0.3f, 0.3f, "原文")
            val before = s.undoNoteRows(d)
            assertEquals(3, before.size)
            // 动手：删图片笔记、改高亮、改正文、新加一条
            s.deleteImageNote("IMG")
            assertNotNull(s.image(p.sha256)!!.orphanedAt)
            s.updateHighlight("HL", intArrayOf(1, 2, 3), HL_BOX)
            s.upsertTextNote(d, "TN", 0, 0.3f, 0.3f, "改过")
            s.upsertTextNote(d, "NEW", 0, 0.6f, 0.6f, "新的")
            // 撤销：整份恢复
            assertTrue(s.restoreUndoNoteRows(d, before) >= 4)
            val after = s.undoNoteRows(d).associateBy { it.id }
            assertEquals(before.map { it.id }.toSet(), after.keys)
            for (b in before) assertTrue("${b.id} 整行恢复", b.payload.contentEquals(after.getValue(b.id).payload))
            assertNull("图片引用回来了：待删除清零", s.image(p.sha256)!!.orphanedAt)
            assertEquals("没变的不写", 0, s.restoreUndoNoteRows(d, before))
        }
    }

    @Test
    fun 撤销能插回整条擦掉的笔迹也能改回平移() {
        LibraryStore.open(freshWs("undoink")).use { s ->
            val d = doc(s)
            s.ensureDefaultLayer(d)
            val pen = com.xvan.unireader.shared.Pen(0, 0, 0, 1f, 3f, 0)
            val pts = listOf(com.xvan.unireader.shared.Pt3(0.1f, 0.1f, 0.5f), com.xvan.unireader.shared.Pt3(0.2f, 0.2f, 0.5f))
            s.insertStroke(d, 0, pen, pts, LibInkLayer.DEFAULT_ID, "S1")
            s.insertStroke(d, 0, pen, pts.map { it.copy(y = it.y + 0.3f) }, LibInkLayer.DEFAULT_ID, "S2")
            val before = s.strokes(d)
            // 整条擦掉 S1、平移 S2（点数不变）
            s.deleteNote("S1")
            s.updateStrokePoints("S2", pts.map { it.copy(x = it.x + 0.4f, y = it.y + 0.3f) })
            val diff = s.restoreStrokes(d, before)
            assertEquals(1, diff.inserted)
            assertEquals(1, diff.updated)
            val after = s.strokes(d).associateBy { it.id }
            assertEquals(setOf("S1", "S2"), after.keys)
            assertEquals(before.first { it.id == "S2" }.pts, after.getValue("S2").pts)
            assertEquals(before.first { it.id == "S1" }.pts, after.getValue("S1").pts)
            assertEquals("没变的不写", 0, s.restoreStrokes(d, before).let { it.deleted + it.updated + it.inserted })
        }
    }

    @Test
    fun md_doc与目录对账() {
        val ws = freshWs("md")
        MdNotes.write(MdNotes.file(ws, "数学/极限.md"), "# 极限\n见 [[导数]]")
        MdNotes.write(MdNotes.file(ws, "导数.md"), "")
        File(MdNotes.root(ws), ".obsidian").mkdirs()
        val scan = MdNotes.scan(ws)
        assertEquals(setOf("数学/极限.md", "导数.md"), scan.notes.map { it.relPath }.toSet())
        LibraryStore.open(ws).use { s ->
            s.reconcileMdDocs(scan.notes.associate { it.relPath to it.title })
            assertEquals(2, s.allMdDocs().size)
            assertEquals("极限", s.allMdDocs().first { it.relPath == "数学/极限.md" }.title)
            assertTrue("id 大写", s.allMdDocs().all { it.id == it.id.uppercase() })
            File(MdNotes.root(ws), "导数.md").delete()
            s.reconcileMdDocs(MdNotes.scan(ws).notes.associate { it.relPath to it.title })
            assertEquals(listOf("数学/极限.md"), s.allMdDocs().map { it.relPath })
        }
    }
}
