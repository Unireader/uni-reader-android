package com.xvan.unireader.local.mirror

import androidx.test.platform.app.InstrumentationRegistry
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.local.store.LibNote
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.MirrorFp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **多镜像**（M6）：一个源盘 + 两份镜像 A/B 轮流同步。对应 Mac `spike/mirror-multi-test.swift`。
 *
 * 方案 §8.4 说「多镜像天然可用」——每份镜像有自己的基线、UUID 不重用。
 * **这份用例是来验它的，不是来复述它的。**
 */
class MirrorMultiTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var src: File
    private lateinit var dirA: File
    private lateinit var dirB: File
    private lateinit var s: LibraryStore
    private lateinit var a: LibraryStore
    private lateinit var b: LibraryStore
    private lateinit var docId: String
    private lateinit var ids: List<String>

    private fun note(id: String, w: Int, ts: String) = LibNote(
        id = id, documentId = docId, kind = 2, page = 86,
        anchorX = 0.1, anchorY = 0.2, anchorW = 0.3, anchorH = 0.4,
        payload = """{"w":$w}""".toByteArray(),
        createdAt = "2026-08-30T10:00:00.000Z", updatedAt = ts,
    )

    private fun setUpTrio(tag: String) {
        val root = File(ctx.cacheDir, "multi-$tag").apply { deleteRecursively(); mkdirs() }
        src = Workspace.create(root, "源")
        val rel = "${Workspace.PDFS_DIR}/a.pdf"
        File(src, rel).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(512))
        s = LibraryStore.open(src)
        docId = s.findOrCreate("h0", "高等数学", 100, rel, inWorkspace = true).id
        ids = (0 until 4).map { "n$it" }
        for ((i, id) in ids.withIndex()) s.upsertNote(note(id, i, "2026-08-30T10:00:00.000Z"))
        dirA = File(root, "A.unrd")
        dirB = File(root, "B.unrd")
        for (d in listOf(dirA, dirB)) {
            MirrorBuilder.create(
                src, s, d, MirrorBuilder.Plan(setOf(docId)), "test-device",
                { loc -> Workspace.resolvePdf(src, loc) },
            )
        }
        a = LibraryStore.open(dirA)
        b = LibraryStore.open(dirB)
    }

    private fun planFor(m: LibraryStore) =
        MirrorDiff.compute(m.syncBase(), m.mirrorSnapshot(), s.mirrorSnapshot())

    private fun sync(m: LibraryStore, dir: File) =
        MirrorApply.apply(planFor(m), dir, m, src, s)

    private fun difference(x: LibraryStore, y: LibraryStore): String? {
        val sx = x.mirrorSnapshot()
        val sy = y.mirrorSnapshot()
        for (spec in MirrorFp.specs) {
            val fx = MirrorFp.fingerprints(sx[spec.table].orEmpty().values.toList(), spec)
            val fy = MirrorFp.fingerprints(sy[spec.table].orEmpty().values.toList(), spec)
            if (fx != fy) return "${spec.table}（各自 ${fx.size}/${fy.size} 行）"
        }
        return null
    }

    private fun payloads(st: LibraryStore) = st.notes(docId).associate { it.id to String(it.payload) }

    @Test
    fun 二手传播_A改的加的删的B都要拿到() {
        setUpTrio("prop")
        assertEquals(
            "源库记两条借出，互不覆盖",
            2, MirrorStore.decodeCheckouts(s.meta(MirrorStore.META_CHECKOUTS)).size,
        )
        assertTrue(planFor(a).isEmpty && planFor(b).isEmpty)

        a.upsertNote(note(ids[0], 99, "2026-09-01T10:00:00.000Z"))   // 改
        a.deleteNote(ids[2])                                         // 删 ← 最容易漏的那条
        a.upsertNote(note("newA", 7, "2026-09-01T10:00:00.000Z"))    // 加
        sync(a, dirA)
        assertNull(difference(a, s))

        val pb = planFor(b)
        assertTrue("B 自己没动过，活全是拉回本机", pb.changes.all { it.side == MirrorDiff.Side.MIRROR })
        assertTrue(
            "🔴 删除的二手传播：A 删的那条，B 也要跟着删",
            pb.changes.any { it.rowId == ids[2] && it.op == MirrorDiff.Op.DELETE },
        )
        sync(b, dirB)
        assertNull(difference(b, s))
        val p = payloads(b)
        assertEquals("""{"w":99}""", p[ids[0]])
        assertNotNull(p["newA"])
        assertNull("🔴 A 删的那条在 B 上也没了", p[ids[2]])
        assertNull("A、B、源盘三者一致", difference(a, b))

        s.close(); a.close(); b.close()
    }

    @Test
    fun 跨镜像冲突按时间戳收敛() {
        setUpTrio("conflict")
        a.upsertNote(note(ids[1], 111, "2026-09-03T08:00:00.000Z"))  // 较旧
        b.upsertNote(note(ids[1], 222, "2026-09-03T20:00:00.000Z"))  // 较新
        sync(a, dirA)
        assertEquals("""{"w":111}""", payloads(s)[ids[1]])

        val pb = planFor(b)
        // B 的基线还是老的，源盘那份对它就是「变过了」→ 判成两端都改
        assertEquals(1, pb.conflicts.size)
        assertEquals(MirrorDiff.ConflictKind.BOTH_MODIFIED, pb.conflicts[0].kind)
        assertEquals(MirrorDiff.Side.MIRROR, pb.conflicts[0].kept)
        sync(b, dirB)
        assertEquals("🔴 收敛到较新的那份", """{"w":222}""", payloads(s)[ids[1]])
        sync(a, dirA)
        assertEquals("""{"w":222}""", payloads(a)[ids[1]])
        assertNull(difference(a, b))
        assertTrue(
            "两条借出记录各自记 lastSyncedAt",
            MirrorStore.decodeCheckouts(s.meta(MirrorStore.META_CHECKOUTS)).all { it.lastSyncedAt != null },
        )
        s.close(); a.close(); b.close()
    }

    @Test
    fun 同一份PDF在两份镜像上各自入库_不让UNIQUE炸掉整次同步() {
        setUpTrio("hash")
        // 用户在 A 和 B 上分别把同一个文件加进来：内容 hash 相同、variant id 不同
        for ((m, dir, tag) in listOf(Triple(a, dirA, "A"), Triple(b, dirB, "B"))) {
            val rel = "${Workspace.PDFS_DIR}/same-$tag.pdf"
            File(dir, rel).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(321) { 0x5A })
            m.findOrCreate("same-hash", "两边都加的书", 3, rel, inWorkspace = true)
        }
        sync(a, dirA)
        assertNull(difference(a, s))

        // 硬插就是 `UNIQUE constraint failed: variant.content_hash` → 整次同步炸掉
        val r = sync(b, dirB)
        assertTrue("🔴 撞 content_hash 的 variant 被跳过并计数", r.hashClashesSkipped >= 1)
        val grouped = s.withMirrorDb { db ->
            db.query("SELECT content_hash, COUNT(*) AS n FROM variant GROUP BY content_hash") { it.getInt(1) }
        }
        assertTrue("源盘上每个 content_hash 仍然只有一行", grouped.all { it == 1 })

        // ⚠️ 这条**刻意**不收敛：「这两本是不是同一本书」是用户的语义判断，同步不该替他决定。
        // 断言它仍然待写，是为了把这个已知取舍钉死——哪天有人"顺手修好"它，得先来改这条用例。
        assertTrue(
            "已知取舍：重复内容那条仍然待写，不自动合并",
            planFor(b).changes.any { it.table == "variant" },
        )
        s.close(); a.close(); b.close()
    }
}
