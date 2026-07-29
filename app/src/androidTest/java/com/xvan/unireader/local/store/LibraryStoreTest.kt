package com.xvan.unireader.local.store

import androidx.test.platform.app.InstrumentationRegistry
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Pt3
import com.xvan.unireader.shared.brushName
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 数据层的**插桩**测试：`android.database.sqlite` 是框架类，JVM 单测里是空壳（方法直接抛），
 * 所以这层只能跑在设备/模拟器上。
 *
 * fixture 用一个**Mac 真造的工作区**（`ANDROID-STANDALONE-PLAN.md §10 M1` 的要求）：
 * 自己造的库只能证明「我写的能被我读」，证明不了「Mac 写的能被我读」——而后者才是这个项目的
 * 全部风险所在。写路径一律在 cacheDir 的副本上做，绝不动原始工作区。
 *
 * 跑：`:app:connectedDebugAndroidTest`（需先把工作区推到 [FIXTURE]，且授予「所有文件访问权限」）
 */
class LibraryStoreTest {

    companion object {
        /** 推 fixture：`adb push <工作区>.unrd /sdcard/Download/` */
        const val FIXTURE = "/sdcard/Download"
    }

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixtureDir(): File? =
        File(FIXTURE).listFiles()?.firstOrNull { it.isDirectory && Workspace.looksLikeWorkspace(it) }

    /** 把 fixture 整个拷进 cacheDir——写测试改的是副本 */
    private fun copyOfFixture(): File {
        val src = fixtureDir()!!
        val dst = File(ctx.cacheDir, "wstest/${src.name}")
        dst.deleteRecursively()
        src.copyRecursively(dst, overwrite = true)
        return dst
    }

    // ---------- 读 ----------

    @Test
    fun 真工作区能打开且元数据对得上() {
        val dir = fixtureDir()
        assumeTrue("$FIXTURE 下没有 .unrd 工作区，跳过", dir != null)
        LibraryStore.open(dir!!, readOnly = true).use { store ->
            assertEquals("schema_version 必须是 7", "7", store.meta("schema_version"))
            assertTrue("工作区名不该为空", store.workspaceName().isNotEmpty())
            val docs = store.allDocuments()
            assertTrue("至少要有一个文档", docs.isNotEmpty())
            for (d in docs) {
                assertTrue("page_count 必须为正：${d.title}", d.pageCount > 0)
                assertTrue("read_frac 必须在 0~1：${d.readFrac}", d.readFrac in 0.0..1.0)
                assertTrue("read_page 不能越界", d.readPage in 0 until d.pageCount)
                assertNotNull("added_at 必须能按 ISO-8601 解析", Iso.instant(d.addedAt))
            }
        }
    }

    @Test
    fun 工作区内的PDF路径能解析到真实文件() {
        val dir = fixtureDir()
        assumeTrue("没有 fixture，跳过", dir != null)
        LibraryStore.open(dir!!, readOnly = true).use { store ->
            for (d in store.allDocuments()) {
                val locs = store.locations(d.id)
                assertTrue("每个文档至少一条 location", locs.isNotEmpty())
                val inWs = locs.filter { it.inWorkspace }
                assumeTrue("该文档没有拷进工作区的副本，跳过", inWs.isNotEmpty())
                assertTrue(
                    "in_workspace 的 path 必须是工作区相对路径",
                    inWs.all { it.path.startsWith("${Workspace.PDFS_DIR}/") },
                )
                val f = Workspace.firstOpenablePdf(dir, store, d.id)
                assertNotNull("拷进工作区的 PDF 必须能解析到真实文件", f)
                assertTrue("PDF 不能是空文件", f!!.length() > 0)
            }
        }
    }

    @Test
    fun 外置卷相对路径与Mac绝对路径都按失效处理() {
        val dir = fixtureDir()
        assumeTrue("没有 fixture，跳过", dir != null)
        val rel = LibLocation("x", "v", "../外面/a.pdf", true, null, inWorkspace = false, isRelative = true)
        val abs = LibLocation("y", "v", "/Users/someone/Books/a.pdf", true, null, inWorkspace = false, isRelative = false)
        assertNull("is_relative 首版不解析", Workspace.resolvePdf(dir!!, rel))
        assertNull("Mac 的绝对路径在安卓上不该解析出东西", Workspace.resolvePdf(dir, abs))
    }

    @Test
    fun 图层色名换算与Mac色板一致() {
        // 抄自 Mac 端 NoteType.palette，两边不一致就意味着同一图层两端色点不同色
        assertEquals(listOf(255, 59, 48), Palette.rgb("red").toList())
        assertEquals(listOf(0, 122, 255), Palette.rgb("blue").toList())
        assertEquals(listOf(142, 142, 147), Palette.rgb("gray").toList())
        assertEquals("未知 key 回落 gray", listOf(142, 142, 147), Palette.rgb("赤").toList())
        assertEquals("red", Palette.rotatingKey(0))
        assertEquals("轮换要回到第一个色", Palette.rotatingKey(8), Palette.rotatingKey(0))
    }

    @Test
    fun 时间戳一定带三位毫秒() {
        // Mac 的 ISO8601DateFormatter 开了 withFractionalSeconds：没有小数秒它解析不出来，
        // 会静默回落成「现在」，于是 created_at 排序（谁盖谁）就乱了。
        val s = Iso.string(java.time.Instant.ofEpochMilli(1_700_000_000_000))
        assertTrue("必须形如 2023-11-14T22:13:20.000Z，实际 $s", Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""").matches(s))
    }

    // ---------- 写（在 cacheDir 的副本上） ----------

    @Test
    fun 落一笔笔迹再读回来字段与payload键都对() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        val pen = Pen(r = 220, g = 40, b = 40, a = 0.95f, w = 9f, brush = 1)   // fountain
        val pts = listOf(Pt3(0.1f, 0.2f, 0.4f), Pt3(0.5f, 0.6f, 0.9f), Pt3(0.3f, 0.8f, 0.5f))
        val docId: String
        val noteId: String
        LibraryStore.open(dir).use { store ->
            docId = store.allDocuments().first().id
            store.ensureDefaultLayer(docId)
            noteId = store.insertStroke(docId, page = 3, pen = pen, pts = pts)!!
        }
        // 重新打开（确认真落盘、不是内存里的假象）
        LibraryStore.open(dir, readOnly = true).use { store ->
            val n = store.notes(docId).first { it.id == noteId }
            assertEquals("kind 必须是 2", NoteKind.INK, n.kind)
            assertEquals(3, n.page)
            // anchor = 归一化点集的包围盒
            assertEquals(0.1, n.anchorX, 1e-6)
            assertEquals(0.2, n.anchorY, 1e-6)
            assertEquals(0.4, n.anchorW, 1e-6)
            assertEquals(0.6000000238418579, n.anchorH, 1e-6)

            // payload 的键名必须与 Mac 端 InkStrokePayload 完全一致
            val o = JSONObject(String(n.payload))
            assertEquals(
                setOf("color", "width", "type", "points", "layerId"),
                o.keys().asSequence().toSet(),
            )
            assertEquals("fountain", o.getString("type"))
            assertEquals(brushName(pen.brush), o.getString("type"))
            assertEquals(LibInkLayer.DEFAULT_ID, o.getString("layerId"))
            val c = o.getJSONObject("color")
            assertEquals(220.0, c.getDouble("r"), 0.0)   // r/g/b 是 0~255
            assertEquals(0.95, c.getDouble("a"), 1e-6)   // a 是 0~1
            assertEquals(3, o.getJSONArray("points").length())

            // 经中立模型转回来，笔与点都还原
            val s = store.strokes(docId).first { it.id == noteId }
            assertEquals(pen, s.pen)
            assertEquals(pts, s.pts)
            assertEquals(LibInkLayer.DEFAULT_ID, s.layerId)
            assertEquals(3L, s.page)
        }
    }

    @Test
    fun 改点集保留未知键且不动createdAt() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        val docId: String
        val noteId: String
        val created: String
        LibraryStore.open(dir).use { store ->
            docId = store.allDocuments().first().id
            noteId = store.insertStroke(
                docId, page = 0,
                pen = Pen(24, 90, 210, 0.95f, 8f, 0),
                pts = listOf(Pt3(0f, 0f, 0.5f), Pt3(1f, 1f, 0.5f)),
            )!!
            val n0 = store.notes(docId).first { it.id == noteId }
            created = n0.createdAt
            // 塞一个「本端不认识」的键，模拟 Mac 先加字段的情形
            val o = JSONObject(String(n0.payload)).put("futureKey", "别把我弄丢")
            store.upsertNote(n0.copy(payload = o.toString().toByteArray()))
            // 局部擦除后回写存活段
            store.updateStrokePoints(noteId, listOf(Pt3(0.2f, 0.2f, 0.5f)))
        }
        LibraryStore.open(dir, readOnly = true).use { store ->
            val n = store.notes(docId).first { it.id == noteId }
            val o = JSONObject(String(n.payload))
            assertEquals("未知键必须原样保留", "别把我弄丢", o.getString("futureKey"))
            assertEquals(1, o.getJSONArray("points").length())
            assertEquals("anchor 要随新点集重算", 0.2, n.anchorX, 1e-6)
            assertEquals("created_at 不许被 upsert 改掉", created, n.createdAt)
        }
    }

    @Test
    fun 进度写入会被夹到合法区间() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        val docId: String
        LibraryStore.open(dir).use { store ->
            docId = store.allDocuments().first().id
            store.updateProgress(docId, page = 12, frac = 1.7, zoom = 2.5, hfrac = -0.3)
        }
        LibraryStore.open(dir, readOnly = true).use { store ->
            val d = store.document(docId)!!
            assertEquals(12, d.readPage)
            assertEquals(1.0, d.readFrac, 0.0)
            assertEquals(2.5, d.readZoom, 0.0)
            assertEquals(0.0, d.readHFrac, 0.0)
        }
    }

    @Test
    fun 新建图层的序号与轮换色同Mac规则() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        LibraryStore.open(dir).use { store ->
            val docId = store.allDocuments().first().id
            store.ensureDefaultLayer(docId)
            val before = store.inkLayers(docId)
            val l = store.addInkLayer(docId)
            assertEquals("序号紧接现有最大值", (before.maxOfOrNull { it.sortOrder } ?: -1) + 1, l.sortOrder)
            assertEquals(Palette.rotatingKey(before.size), l.colorKey)
            assertTrue(store.inkLayers(docId).any { it.id == l.id })
            // 中立模型里的 Layer 是 r/g/b，必须按色板换算过
            val ui = store.layersForUi(docId).last()
            val rgb = Palette.rgb(l.colorKey)
            assertEquals(rgb[0], ui.r)
            assertEquals(rgb[1], ui.g)
            assertEquals(rgb[2], ui.b)
        }
    }

    @Test
    fun checkpoint之后wal被截断() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        LibraryStore.open(dir).use { store ->
            val docId = store.allDocuments().first().id
            repeat(20) { store.insertStroke(docId, 0, Pen(0, 0, 0, 1f, 8f, 0), listOf(Pt3(0.5f, 0.5f, 0.5f))) }
            store.checkpoint()
        }
        // close 里还会再 checkpoint 一次；此时 -wal 应该已被截断（0 或很小）
        val wal = File(dir, "${Workspace.DB_REL}-wal")
        assertTrue("checkpoint 后 -wal 不该还留着大量数据（实际 ${wal.length()}B）", wal.length() < 8 * 1024)
    }
}
