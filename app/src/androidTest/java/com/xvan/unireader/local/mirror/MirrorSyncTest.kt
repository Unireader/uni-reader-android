package com.xvan.unireader.local.mirror

import androidx.test.platform.app.InstrumentationRegistry
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.local.store.Db
import com.xvan.unireader.local.store.Iso
import com.xvan.unireader.local.store.LibNote
import com.xvan.unireader.local.store.LibraryStore
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三方合并**端到端**：建工作区 → 建镜像 → 两边各改一通 → 干跑。
 * 对应 Mac `spike/mirror-diff-test.swift` 的 ⑤⑥ 段。
 *
 * 判定表本身在 JVM 单测 `MirrorDiffTest` 里逐格验过；这里验的是**接到真 SQLite 上还对不对**
 * ——快照怎么读、基线从哪来、`meta` 白名单有没有漏掉。这些在 JVM 里是空壳，只能上设备。
 *
 * 全程在 `cacheDir` 里做，不要任何存储权限。
 * 跑：`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.xvan.unireader.local.mirror.MirrorSyncTest`
 */
class MirrorSyncTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun freshRoot(): File =
        File(ctx.cacheDir, "mirrorsync").apply { deleteRecursively(); mkdirs() }

    private fun note(id: String, doc: String, w: Int, updated: String) = LibNote(
        id = id, documentId = doc, kind = 2, page = 86,
        anchorX = 0.1, anchorY = 0.2, anchorW = 0.3, anchorH = 0.4,
        payload = """{"w":$w}""".toByteArray(),
        createdAt = "2026-08-30T10:00:00.000Z", updatedAt = updated,
    )

    @Test
    fun 建镜像后两边各改一通干跑结论逐条正确() {
        val root = freshRoot()
        val ws = Workspace.create(root, "源")
        val relA = "${Workspace.PDFS_DIR}/a.pdf"
        File(ws, relA).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(512))

        val ids = (0 until 4).map { "0000000$it-0000-4000-8000-00000000000$it" }
        val dst = File(root, "镜像.unrd")
        val docId: String

        LibraryStore.open(ws).use { store ->
            docId = store.findOrCreate("hA", "高等数学", 100, relA, inWorkspace = true).id
            // n0 镜像改 / n1 两端都改 / n2 镜像删 / n3 源盘改
            for ((i, id) in ids.withIndex()) {
                store.upsertNote(note(id, docId, i, "2026-08-30T10:00:00.000Z"))
            }
            MirrorBuilder.create(
                ws, store, dst, MirrorBuilder.Plan(setOf(docId)), "test-device",
                { loc -> Workspace.resolvePdf(ws, loc) },
            )
        }

        // 🔴 刚建好的镜像，干跑必须为空。这是整个合并方案的地基，先验它。
        LibraryStore.open(dst, readOnly = true).use { mine ->
            Db.open(File(ws, Workspace.DB_REL), readOnly = true).use { srcDb ->
                val plan = MirrorDiff.compute(mine.syncBase(), mine.mirrorSnapshot(), MirrorStore.snapshot(srcDb))
                assertTrue("刚建好就该两端一致：${MirrorReport.headline(plan)}", plan.isEmpty)
            }
        }

        // —— 镜像侧：改 n0、删 n2、加一条新的 ——
        val newId = UUID.randomUUID().toString()
        LibraryStore.open(dst).use { m ->
            m.upsertNote(note(ids[0], docId, 99, "2026-09-01T10:00:00.000Z"))
            m.deleteNote(ids[2])
            m.upsertNote(note(newId, docId, 7, "2026-09-01T10:00:00.000Z"))
            // 两端都改 n1，镜像这份较旧
            m.upsertNote(note(ids[1], docId, 12, "2026-09-01T08:00:00.000Z"))
        }
        // —— 源盘侧：改 n3、改 n1（较新）——
        LibraryStore.open(ws).use { store ->
            store.upsertNote(note(ids[3], docId, 33, "2026-09-01T09:00:00.000Z"))
            store.upsertNote(note(ids[1], docId, 11, "2026-09-02T09:00:00.000Z"))
        }

        LibraryStore.open(dst, readOnly = true).use { mine ->
            Db.open(File(ws, Workspace.DB_REL), readOnly = true).use { srcDb ->
                val mineSnap = mine.mirrorSnapshot()
                val theirs = MirrorStore.snapshot(srcDb)
                val plan = MirrorDiff.compute(mine.syncBase(), mineSnap, theirs)
                fun one(id: String) = plan.changes.firstOrNull { it.rowId == id }

                assertEquals("镜像删的 → 源盘也删", MirrorDiff.Op.DELETE, one(ids[2])!!.op)
                assertEquals(MirrorDiff.Side.SOURCE, one(ids[2])!!.side)
                assertEquals("镜像改的 → 写进源盘", MirrorDiff.Reason.MIRROR_MODIFIED, one(ids[0])!!.reason)
                assertEquals("镜像新增的 → 写进源盘", MirrorDiff.Reason.MIRROR_ADDED, one(newId)!!.reason)
                assertEquals("源盘改的 → 拉进镜像", MirrorDiff.Reason.SOURCE_MODIFIED, one(ids[3])!!.reason)
                assertEquals("两端都改 → 源盘 09-02 较新", MirrorDiff.Reason.CONFLICT_NEWER, one(ids[1])!!.reason)
                assertEquals(MirrorDiff.Side.MIRROR, one(ids[1])!!.side)
                assertEquals(1, plan.conflicts.size)
                assertEquals(5, plan.changes.size)
                assertEquals(1, plan.count(MirrorDiff.Side.SOURCE, MirrorDiff.Op.DELETE))
                assertEquals(2, plan.count(MirrorDiff.Side.SOURCE, MirrorDiff.Op.UPSERT))
                assertEquals(2, plan.count(MirrorDiff.Side.MIRROR, MirrorDiff.Op.UPSERT))

                val titles = MirrorStore.titles(mineSnap, theirs)
                assertEquals("高等数学", titles[docId])
                val summary = MirrorReport.summary(plan, titles)
                assertTrue(summary.any { it.text.startsWith("写入硬盘") })
                assertTrue(summary.any { it.text == "冲突 1 条" })
                assertTrue(
                    "冲突要说清保留了哪份",
                    MirrorReport.conflictLines(plan, titles).single().contains("保留了较新的那份"),
                )

                // 干跑真的一个字都没写
                val again = MirrorDiff.compute(mine.syncBase(), mine.mirrorSnapshot(), MirrorStore.snapshot(srcDb))
                assertEquals("连跑两次结论一致", plan.changes.size, again.changes.size)
                assertEquals("基线还是建镜像那一刻的 4 条", 4, mine.syncBase()["note"]?.size)
            }
        }
    }

    @Test
    fun 按workspaceId找源盘_不认路径也不认名字() {
        val root = freshRoot()
        val ws = Workspace.create(root, "源")
        val dst = File(root, "同名副本.unrd")
        LibraryStore.open(ws).use { store ->
            MirrorBuilder.create(ws, store, dst, MirrorBuilder.Plan(emptySet()), "test-device", { null })
        }
        LibraryStore.open(dst, readOnly = true).use { m ->
            val sourceId = m.meta(MirrorStore.META_MIRROR_OF)!!
            LibraryStore.open(ws, readOnly = true).use { s ->
                assertEquals("mirror_of 指的就是源库的 workspace_id", s.workspaceId(), sourceId)
            }
            // 🔴 镜像自己也带 workspace_id；判据里必须排掉「带 mirror_of 的」，
            // 否则扫一圈盘会把镜像自己认成源
            assertTrue("镜像有自己的 id", m.workspaceId() != sourceId)
        }
    }
}
