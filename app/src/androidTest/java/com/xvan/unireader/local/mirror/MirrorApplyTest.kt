package com.xvan.unireader.local.mirror

import androidx.test.platform.app.InstrumentationRegistry
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.local.store.LibNote
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.MirrorFp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **应用合并**（[MirrorApply]）。对应 Mac `spike/mirror-apply-test.swift`。
 *
 * 这是整个功能里唯一会大批量改用户数据的一段，所以重点验四件事：
 * ① 合并后两端**白名单表逐行一致**（这是"同步成功"的唯一硬定义）；
 * ② 合并前必须有备份，且备份是**合并前**的样子（删掉的东西救得回来）；
 * ③ **半途而废能自愈**：只应用一侧，再跑一次 diff 只剩另一侧的活；
 * ④ 外键孤儿（父文档被删、子行还要写）**不炸整次同步**，只丢那一行并报出来。
 *
 * 全程在 `cacheDir` 里做，不要任何存储权限。
 */
class MirrorApplyTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun freshRoot(name: String): File =
        File(ctx.cacheDir, "apply-$name").apply { deleteRecursively(); mkdirs() }

    private fun note(id: String, doc: String, w: Int, updated: String) = LibNote(
        id = id, documentId = doc, kind = 2, page = 86,
        anchorX = 0.1, anchorY = 0.2, anchorW = 0.3, anchorH = 0.4,
        payload = """{"w":$w}""".toByteArray(),
        createdAt = "2026-08-30T10:00:00.000Z", updatedAt = updated,
    )

    private class Pair2(
        val src: File, val store: LibraryStore,
        val dst: File, val mirror: LibraryStore,
        val docId: String, val ids: List<String>,
    )

    /** 造一对「源工作区 + 它的镜像」，源里先放 4 条笔迹 */
    private fun makePair(root: File, name: String): Pair2 {
        val ws = Workspace.create(root, name)
        val rel = "${Workspace.PDFS_DIR}/a.pdf"
        File(ws, rel).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(512))
        val store = LibraryStore.open(ws)
        val docId = store.findOrCreate("h-$name", "高等数学", 100, rel, inWorkspace = true).id
        val ids = (0 until 4).map { "$name-note-$it" }
        for ((i, id) in ids.withIndex()) store.upsertNote(note(id, docId, i, "2026-08-30T10:00:00.000Z"))
        val dst = File(root, "$name-镜像.unrd")
        MirrorBuilder.create(
            ws, store, dst, MirrorBuilder.Plan(setOf(docId)), "test-device",
            { loc -> Workspace.resolvePdf(ws, loc) },
        )
        return Pair2(ws, store, dst, LibraryStore.open(dst), docId, ids)
    }

    private fun planOf(mirror: LibraryStore, source: LibraryStore): MirrorDiff.Plan =
        MirrorDiff.compute(
            mirror.syncBase(), mirror.mirrorSnapshot(), source.mirrorSnapshot(),
            mirror.mirrorOcrKeys(), source.mirrorOcrKeys(),
            mirror.mirrorAlignStamps(), source.mirrorAlignStamps(),
        )

    /** 直接往 `page_align` 写一行（只有 Mac 写这张表，本端没有写的 DAO，测试里自己写） */
    private fun putAlign(store: LibraryStore, hash: String, enabled: Boolean, pageCount: Int, payload: String, updated: String) {
        store.withMirrorDb { db ->
            db.exec(
                "INSERT OR REPLACE INTO page_align(content_hash,enabled,page_count,payload,created_at,updated_at) " +
                    "VALUES(?,?,?,?,?,?)",
                arrayOf<Any?>(
                    hash, if (enabled) 1L else 0L, pageCount.toLong(), payload.toByteArray(),
                    "2026-09-17T07:00:00Z", updated,
                ),
            )
        }
    }

    /** 直接往 `ocr_page` 写一页（安卓端不跑 OCR，没有这张表的 DAO，测试里自己写） */
    private fun putOcr(store: LibraryStore, hash: String, page: Int, text: String) {
        store.withMirrorDb { db ->
            db.exec(
                "INSERT OR REPLACE INTO ocr_page(content_hash,page,provider,payload,lang,created_at) " +
                    "VALUES(?,?,?,?,?,?)",
                arrayOf<Any?>(
                    hash, page, "paddle-http", """{"t":"$text"}""".toByteArray(),
                    "ch", "2026-09-04T10:00:00.000Z",
                ),
            )
        }
    }

    /** 两端白名单表逐行一致 —— 「同步成功」的唯一硬定义 */
    private fun difference(a: LibraryStore, b: LibraryStore): String? {
        val sa = a.mirrorSnapshot()
        val sb = b.mirrorSnapshot()
        for (spec in MirrorFp.specs) {
            val fa = MirrorFp.fingerprints(sa[spec.table].orEmpty().values.toList(), spec)
            val fb = MirrorFp.fingerprints(sb[spec.table].orEmpty().values.toList(), spec)
            if (fa != fb) return "${spec.table} 不一致（各自 ${fa.size}/${fb.size} 行）"
        }
        return null
    }

    @Test
    fun 双向合并后两端逐行一致且干跑归零() {
        val root = freshRoot("A")
        val p = makePair(root, "A")
        val newId = "A-note-new"
        // 镜像侧：改 n0、删 n2、加一条
        p.mirror.upsertNote(note(p.ids[0], p.docId, 99, "2026-09-01T10:00:00.000Z"))
        p.mirror.deleteNote(p.ids[2])
        p.mirror.upsertNote(note(newId, p.docId, 7, "2026-09-01T10:00:00.000Z"))
        // 两端都改 n1，源盘较新
        p.mirror.upsertNote(note(p.ids[1], p.docId, 12, "2026-09-01T08:00:00.000Z"))
        p.store.upsertNote(note(p.ids[1], p.docId, 11, "2026-09-02T09:00:00.000Z"))
        // 源盘侧：改 n3
        p.store.upsertNote(note(p.ids[3], p.docId, 33, "2026-09-01T09:00:00.000Z"))

        val plan = planOf(p.mirror, p.store)
        assertEquals(5, plan.changes.size)
        assertEquals(1, plan.conflicts.size)

        val steps = ArrayList<String>()
        val r = MirrorApply.apply(plan, p.dst, p.mirror, p.src, p.store) { s, _ ->
            if (steps.lastOrNull() != s) steps.add(s)
        }
        assertEquals(2, r.sourceUpserts)
        assertEquals(1, r.sourceDeletes)
        assertEquals(2, r.mirrorUpserts)
        assertEquals(0, r.orphansSkipped)
        // 🔴 没有备份就动手 = 把最坏情况从回滚变成没得救
        assertEquals("正在备份硬盘上的资料库…", steps.firstOrNull())
        assertEquals("完成", steps.lastOrNull())

        assertNull("合并后两端必须逐行一致", difference(p.mirror, p.store))
        assertTrue("再跑一次干跑应当无事可做", planOf(p.mirror, p.store).isEmpty)

        val sn = p.store.notes(p.docId).associate { it.id to String(it.payload) }
        val mn = p.mirror.notes(p.docId).associate { it.id to String(it.payload) }
        assertNull("镜像删掉的那条源盘也没了", sn[p.ids[2]])
        assertEquals("""{"w":99}""", sn[p.ids[0]])
        assertNotNull("镜像新增的那条推到了源盘", sn[newId])
        assertEquals("源盘改的那条拉进了镜像", """{"w":33}""", mn[p.ids[3]])
        assertEquals("冲突那条两端都是较新的源盘版本", """{"w":11}""", mn[p.ids[1]])

        // 记账
        assertNotNull(p.mirror.meta(MirrorStore.META_MIRROR_LAST_SYNCED_AT))
        assertNotNull(
            MirrorStore.decodeCheckouts(p.store.meta(MirrorStore.META_CHECKOUTS)).first().lastSyncedAt,
        )

        // 备份是**合并前**的样子：被删那条还在里面
        val backup = r.backup!!
        assertTrue(backup.isFile)
        com.xvan.unireader.local.store.Db.open(backup, readOnly = true).use { b ->
            val hit = b.query("SELECT id FROM note WHERE id=?", arrayOf(p.ids[2])) { it.getString(0) }
            assertEquals("被删那条在备份里救得回来", 1, hit.size)
        }
        // 只留最近 3 份
        repeat(4) { MirrorApply.backupSource(p.store, p.src) }
        val backups = File(p.src, "UniReader/backup").listFiles()?.filter { it.name.endsWith(".sqlite") }
        assertEquals(3, backups?.size)

        p.store.close(); p.mirror.close()
    }

    @Test
    fun 半途而废能自愈() {
        val root = freshRoot("B")
        val p = makePair(root, "B")
        p.mirror.upsertNote(note(p.ids[0], p.docId, 98, "2026-09-01T10:00:00.000Z"))
        p.store.upsertNote(note(p.ids[1], p.docId, 97, "2026-09-01T10:00:00.000Z"))
        val plan = planOf(p.mirror, p.store)
        assertEquals(2, plan.changes.size)

        // 只把源盘那一侧写下去（模拟「写完源盘就被拔盘」，基线**没有**重算）
        val half = MirrorApply.Result()
        p.store.withMirrorDb { db ->
            db.transaction {
                MirrorApply.write(db, plan.changesTo(MirrorDiff.Side.SOURCE), half, MirrorDiff.Side.SOURCE)
            }
        }
        val after = planOf(p.mirror, p.store)
        // 🔴 已落到源盘的那条被判成「两端改成一样了」→ 无操作；不需要任何补偿逻辑
        assertEquals(1, after.changes.size)
        assertEquals(MirrorDiff.Side.MIRROR, after.changes[0].side)
        assertEquals(p.ids[1], after.changes[0].rowId)

        MirrorApply.apply(after, p.dst, p.mirror, p.src, p.store)
        assertNull("补完之后两端仍然一致", difference(p.mirror, p.store))
        p.store.close(); p.mirror.close()
    }

    @Test
    fun 外键孤儿不炸整次同步() {
        val root = freshRoot("C")
        val p = makePair(root, "C")
        // 源盘上把整本书删了；镜像上同时给它写了新笔迹
        // 安卓端没有 deleteDocument 的 DAO（生产代码里删书走别的路），测试里直接下 SQL——
        // 外键 ON DELETE CASCADE 会连带删掉它的 variant/note
        p.store.withMirrorDb { it.exec("DELETE FROM document WHERE id=?", arrayOf<Any?>(p.docId)) }
        p.mirror.upsertNote(note("C-orphan", p.docId, 1, "2026-09-01T10:00:00.000Z"))
        val plan = planOf(p.mirror, p.store)
        assertTrue(plan.changes.any { it.rowId == "C-orphan" && it.side == MirrorDiff.Side.SOURCE })

        val r = MirrorApply.apply(plan, p.dst, p.mirror, p.src, p.store)
        assertTrue("父文档已不在 → 丢掉那行并计数，而不是让外键把整次同步炸掉", r.orphansSkipped >= 1)
        assertNull(difference(p.mirror, p.store))
        p.store.close(); p.mirror.close()
    }

    @Test
    fun 文件补齐把镜像新加的书拷回源盘且幂等() {
        val root = freshRoot("D")
        val p = makePair(root, "D")
        val rel = "${Workspace.PDFS_DIR}/new.pdf"
        File(p.dst, rel).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(777))
        val newDoc = p.mirror.findOrCreate("h-new", "新加的书", 5, rel, inWorkspace = true).id

        val r = MirrorApply.apply(planOf(p.mirror, p.store), p.dst, p.mirror, p.src, p.store)
        assertEquals(1, r.filesCopiedToSource)
        assertTrue(
            "源盘上那本新书能解析到真实文件",
            p.store.locations(newDoc).any { it.inWorkspace && File(p.src, it.path).isFile },
        )
        assertNull(difference(p.mirror, p.store))
        // 🔴 补齐是幂等的
        assertEquals(0, MirrorApply.fillFilesToSource(p.dst, p.mirror, p.src, p.store))
        p.store.close(); p.mirror.close()
    }

    /** OCR 缓存双向补齐（方案 §4：纯 additive，只补不删不覆盖）。对应 Mac `spike/mirror-apply-test.swift` ⑦ */
    @Test
    fun OCR缓存双向补齐且不覆盖对面已有的那份() {
        val root = freshRoot("E")
        val p = makePair(root, "E")
        val hash = "h-E"
        // 离线期间：副本上识别了 1、2 页；硬盘上识别了 3 页
        putOcr(p.mirror, hash, 1, "副本上识别的")
        putOcr(p.mirror, hash, 2, "副本上识别的")
        putOcr(p.store, hash, 3, "硬盘上识别的")

        val plan = planOf(p.mirror, p.store)
        assertEquals(2, plan.ocrToSource.size)
        assertEquals(1, plan.ocrToMirror.size)
        assertTrue("OCR 缓存不走 Change 那条通道", plan.changes.isEmpty())
        assertFalse("只差 OCR 也算「有东西要同步」", plan.isEmpty)

        val r = MirrorApply.apply(plan, p.dst, p.mirror, p.src, p.store)
        assertEquals(2, r.ocrFilledToSource)
        assertEquals(1, r.ocrFilledToMirror)
        assertEquals(p.store.mirrorOcrKeys(), p.mirror.mirrorOcrKeys())
        assertTrue("再跑一次干跑没有剩活", planOf(p.mirror, p.store).isEmpty)

        // **不覆盖**：两边同一页各自识别过（内容不同）→ 硬写一次也一个字都不许动
        putOcr(p.store, hash, 9, "硬盘版")
        putOcr(p.mirror, hash, 9, "副本版")
        assertTrue("同一个键两边都有 → 不产生任何补齐动作", planOf(p.mirror, p.store).ocrToSource.isEmpty())
        MirrorApply.fillOcr(p.mirror, p.store, listOf(MirrorDiff.OcrKey(hash, 9, "paddle-http")))
        val kept = p.store.withMirrorDb { db ->
            db.query(
                "SELECT payload FROM ocr_page WHERE content_hash=? AND page=9 AND provider=?",
                arrayOf<Any?>(hash, "paddle-http"),
            ) { String(it.getBlob(0)) }.first()
        }
        assertTrue("🔴 INSERT OR IGNORE：覆盖不掉对面已有的那份（$kept）", kept.contains("硬盘版"))
        p.store.close(); p.mirror.close()
    }

    /**
     * 扫描页对齐参数（`../SCAN-ALIGN-PLAN.md §5`）：整行按 `updated_at` 取新、逐字搬、对面没表也能补、幂等。
     * 对应 Mac `spike/mirror-align-test.swift` ③④⑤。
     */
    @Test
    fun 扫描页对齐双向覆盖且对面没表也能补() {
        val root = freshRoot("F")
        val p = makePair(root, "F")
        val payload = """{"v":1,"w":497.5,"pages":[[0.01,-3,0,500,700]]}"""
        // 副本库当成「本端早于 v14 建的库」：没有 page_align 表
        p.mirror.withMirrorDb { it.exec("DROP TABLE IF EXISTS page_align") }
        assertTrue("表不存在 → 空、不抛", p.mirror.mirrorAlignStamps().isEmpty())
        assertNull("表不存在 → 按没开对齐、不抛", p.mirror.activePageAlign("h-F"))

        // 硬盘上（Mac 写的）：一本开着、一本关着；时间戳故意用别的端的格式，验证逐字搬
        putAlign(p.store, "h-F", true, 1, payload, "2026-09-17T08:00:00Z")
        putAlign(p.store, "h-off", false, 1, payload, "2026-09-17T08:00:00Z")
        putAlign(p.store, "h-bad", true, 2, payload, "2026-09-17T08:00:00Z")   // 页数列与 payload 对不上
        assertNotNull(p.store.activePageAlign("h-F"))
        assertNull("关着 → null", p.store.activePageAlign("h-off"))
        assertNull("page_count 与 payload 页数对不上 → null", p.store.activePageAlign("h-bad"))
        assertNull("没测过 → null", p.store.activePageAlign("nope"))

        val plan = planOf(p.mirror, p.store)
        assertEquals(listOf("h-F", "h-bad", "h-off").sorted(), plan.alignToMirror)
        assertTrue(plan.alignToSource.isEmpty())
        assertTrue("只有拉回本机：可自动推送", plan.isCleanPushToMirror)

        val r = MirrorApply.apply(plan, p.dst, p.mirror, p.src, p.store)
        assertEquals(3, r.alignToMirror)
        assertEquals(0, r.alignToSource)
        assertEquals(p.store.mirrorAlignStamps(), p.mirror.mirrorAlignStamps())
        assertEquals("时间戳字符串逐字搬过去", "2026-09-17T08:00:00Z", p.mirror.mirrorAlignStamps()["h-F"])
        val t = p.mirror.activePageAlign("h-F")
        assertNotNull("副本上补建了表、行也到了", t)
        assertEquals("payload 逐字节相同（戳按原字节算）", com.xvan.unireader.shared.ScanAlignTable.stamp(payload.toByteArray()), t!!.stamp)
        assertTrue("再跑一次干跑没有剩活", planOf(p.mirror, p.store).isEmpty)

        // 副本上（设想未来本端能切开关）关掉，且更新 → 写入硬盘，要人工确认
        p.mirror.withMirrorDb {
            it.exec("UPDATE page_align SET enabled=0, updated_at='2026-09-17T09:00:00Z' WHERE content_hash='h-F'")
        }
        val plan2 = planOf(p.mirror, p.store)
        assertEquals(listOf("h-F"), plan2.alignToSource)
        assertEquals(1, plan2.pendingToSource)
        assertFalse("写入硬盘方向挡自动推送", plan2.isCleanPushToMirror)
        assertEquals(1, MirrorApply.fillAlign(p.mirror, p.store, plan2.alignToSource))
        assertNull("硬盘上 h-F 的开关被副本那份覆盖", p.store.activePageAlign("h-F"))
        assertEquals("幂等：再搬一次结果一样", 1, MirrorApply.fillAlign(p.mirror, p.store, plan2.alignToSource))
        assertTrue(planOf(p.mirror, p.store).isEmpty)
        assertFalse("没有这一行 → false", p.store.copyPageAlign("missing", p.mirror))
        p.store.close(); p.mirror.close()
    }
}
