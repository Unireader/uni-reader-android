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

    // ---------- 文字注解 / 高亮（M5） ----------

    /**
     * 造一条 **Mac 形状的选区注解**（kind=0，有 quote/rects/color/type_id、anchor 带宽高）。
     * fixture 里只有笔迹，而这一版最要紧的就是「Mac 建的注解在平板上编辑后不丢字段」——
     * 所以按 `Sources/App/TextNoteModel.swift` 的编码形态手工造一条来当被测对象。
     */
    private fun seedSelectionNote(store: LibraryStore, docId: String, id: String, typeId: String? = null) {
        val o = JSONObject()
            .put("quote", "被选中的原文")
            .put("text", "Mac 上写的批注")
            .put(
                "rects",
                org.json.JSONArray()
                    .put(org.json.JSONArray().put(0.2).put(0.30).put(0.5).put(0.02))
                    .put(org.json.JSONArray().put(0.2).put(0.33).put(0.3).put(0.02)),
            )
            .put("color", JSONObject().put("r", 255.0).put("g", 214.0).put("b", 40.0).put("a", 1.0))
        if (typeId != null) o.put("type_id", typeId)
        val now = Iso.now()
        store.upsertNote(
            LibNote(
                id = id, documentId = docId, kind = NoteKind.TEXT, page = 2,
                anchorX = 0.2, anchorY = 0.30, anchorW = 0.5, anchorH = 0.05,
                payload = o.toString().toByteArray(), createdAt = now, updatedAt = now,
            ),
        )
    }

    @Test
    fun 新建点注解的落库形状与Mac一致() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        val docId: String
        val id = java.util.UUID.randomUUID().toString()
        LibraryStore.open(dir).use { store ->
            docId = store.allDocuments().first().id
            store.upsertTextNote(docId, id, page = 5, nx = 0.4f, ny = 0.6f, text = "平板上记的")
        }
        LibraryStore.open(dir, readOnly = true).use { store ->
            val n = store.notes(docId).first { it.id == id }
            assertEquals("kind 必须是 0", NoteKind.TEXT, n.kind)
            assertEquals(5, n.page)
            // 点注解 = 零尺寸 anchor，anchor 就是落点（同 Mac applyTextNote）
            assertEquals(0.4, n.anchorX, 1e-6)
            assertEquals(0.6, n.anchorY, 1e-6)
            assertEquals(0.0, n.anchorW, 0.0)
            assertEquals(0.0, n.anchorH, 0.0)
            val o = JSONObject(String(n.payload))
            assertEquals(setOf("quote", "text", "rects"), o.keys().asSequence().toSet())
            assertEquals("平板上记的", o.getString("text"))
            assertEquals("", o.getString("quote"))
            assertEquals(0, o.getJSONArray("rects").length())
            // 中立模型：nx/ny 取 anchor 左上角，与 Mac broadcastNotes 发给平板的两列相同
            val t = store.textNotes(docId).first { it.id == id }
            assertEquals(5L, t.page)
            assertEquals(0.4f, t.nx, 1e-6f)
            assertEquals(0.6f, t.ny, 1e-6f)
            assertEquals("平板上记的", t.text)
        }
    }

    @Test
    fun 编辑Mac建的选区注解只改正文其余字段一个不丢() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        val docId: String
        val id = java.util.UUID.randomUUID().toString()
        LibraryStore.open(dir).use { store ->
            docId = store.allDocuments().first().id
            seedSelectionNote(store, docId, id, typeId = "11111111-2222-3333-4444-555555555555")
            // 平板上点开它改一句话——nx/ny 来自画布（= anchor 左上角），text 是新的
            store.upsertTextNote(docId, id, page = 2, nx = 0.2f, ny = 0.30f, text = "平板改过的")
        }
        LibraryStore.open(dir, readOnly = true).use { store ->
            val n = store.notes(docId).first { it.id == id }
            val o = JSONObject(String(n.payload))
            assertEquals("平板改过的", o.getString("text"))
            assertEquals("引文不许丢", "被选中的原文", o.getString("quote"))
            assertEquals("逐行框不许丢", 2, o.getJSONArray("rects").length())
            assertEquals("类型不许丢", "11111111-2222-3333-4444-555555555555", o.getString("type_id"))
            assertEquals("高亮色不许丢", 214.0, o.getJSONObject("color").getDouble("g"), 0.0)
            // 关键：选区注解不能被退化成点注解
            assertEquals("anchor 宽度不许被抹平", 0.5, n.anchorW, 1e-6)
            assertEquals("anchor 高度不许被抹平", 0.05, n.anchorH, 1e-6)
        }
    }

    @Test
    fun 空文本等于删除() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        val docId: String
        val id = java.util.UUID.randomUUID().toString()
        LibraryStore.open(dir).use { store ->
            docId = store.allDocuments().first().id
            store.upsertTextNote(docId, id, 0, 0.1f, 0.1f, "先写点东西")
            assertEquals(1, store.textNotes(docId).count { it.id == id })
            store.upsertTextNote(docId, id, 0, 0.1f, 0.1f, "   ")   // 全空白 = 空
        }
        LibraryStore.open(dir, readOnly = true).use { store ->
            assertEquals("空文本 upsert 等价删除（同 Mac）", 0, store.textNotes(docId).count { it.id == id })
        }
    }

    @Test
    fun 框选平移注解时anchor与逐行框一起动并逐角clamp() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        val docId: String
        val id = java.util.UUID.randomUUID().toString()
        LibraryStore.open(dir).use { store ->
            docId = store.allDocuments().first().id
            seedSelectionNote(store, docId, id)
            store.translateTextNote(id, dx = 0.1f, dy = -0.5f)   // 向上推出页顶
        }
        LibraryStore.open(dir, readOnly = true).use { store ->
            val n = store.notes(docId).first { it.id == id }
            assertEquals("x 正常平移", 0.3, n.anchorX, 1e-6)
            assertEquals("顶边 clamp 到 0", 0.0, n.anchorY, 1e-6)
            assertEquals("高度收缩 0.05 → 0（0.30-0.5 全在页外）", 0.0, n.anchorH, 1e-6)
            val rects = JSONObject(String(n.payload)).getJSONArray("rects")
            assertEquals(2, rects.length())
            assertEquals("逐行框也要跟着平移", 0.3, rects.getJSONArray(0).getDouble(0), 1e-6)
            assertEquals("逐行框同样逐角 clamp", 0.0, rects.getJSONArray(0).getDouble(1), 1e-6)
            assertEquals("引文不许在平移里丢", "被选中的原文", JSONObject(String(n.payload)).getString("quote"))
        }
    }

    @Test
    fun 铺色按Mac的透明度口径分层() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        val docId: String
        val noteId = java.util.UUID.randomUUID().toString()
        val hlId = java.util.UUID.randomUUID().toString()
        val pointId = java.util.UUID.randomUUID().toString()
        LibraryStore.open(dir).use { store ->
            docId = store.allDocuments().first().id
            seedSelectionNote(store, docId, noteId)
            store.upsertTextNote(docId, pointId, 2, 0.5f, 0.5f, "点注解")
            // 一条高亮（kind=3）：payload 键与 Mac HighlightPayload 一致（quote/rects/color）
            val now = Iso.now()
            store.upsertNote(
                LibNote(
                    id = hlId, documentId = docId, kind = NoteKind.HIGHLIGHT, page = 2,
                    anchorX = 0.1, anchorY = 0.1, anchorW = 0.4, anchorH = 0.02,
                    payload = JSONObject()
                        .put("quote", "高亮的原文")
                        .put(
                            "rects",
                            org.json.JSONArray()
                                .put(org.json.JSONArray().put(0.1).put(0.1).put(0.4).put(0.02)),
                        )
                        .put("color", JSONObject().put("r", 150.0).put("g", 220.0).put("b", 120.0).put("a", 1.0))
                        .toString().toByteArray(),
                    createdAt = now, updatedAt = now,
                ),
            )
        }
        LibraryStore.open(dir, readOnly = true).use { store ->
            // 只看本用例造的那一页：fixture 里可能本来就有注解/高亮（工作区是活的，别假设它是空的）
            val fills = store.textFills(docId).filter { it.page == 2L }
            assertEquals("点注解不产生铺色，只有高亮 + 选区注解两片", 2, fills.size)
            // 顺序 = 绘制顺序：高亮在下、注解底色在上（同 Mac PageCellView 的层序）
            val hi = fills[0]
            assertEquals(0.38f, hi.a, 1e-6f)
            assertEquals("高亮用 payload 里的自身颜色", listOf(150, 220, 120), listOf(hi.r, hi.g, hi.b))
            val nt = fills[1]
            assertEquals(0.32f, nt.a, 1e-6f)
            assertEquals("无类型的注解铺通用暖黄", listOf(255, 209, 38), listOf(nt.r, nt.g, nt.b))
            assertEquals("两行选区 = 两个框", 2, nt.rects.size)
            assertEquals(0.2f, nt.rects[0][0], 1e-6f)
        }
    }

    /**
     * 笔记类型是**一条 meta**（`note_types` 的 JSON 数组），不是表。有类型的注解按类型色铺，
     * 查不到的 `type_id`（Mac 上刚建的类型还没同步过来、或 meta 坏了）回落通用暖黄——
     * 而不是整篇不铺色/崩掉。
     *
     * meta 行直接用框架 API 塞进副本：`LibraryStore` 只读 meta 不写（模式1 没有类型管理界面），
     * **不为了测试往生产代码里加一个没人调的写口**。
     */
    @Test
    fun 注解按工作区笔记类型的色板铺色查不到则回落通用色() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        val typed = java.util.UUID.randomUUID().toString()
        val orphan = java.util.UUID.randomUUID().toString()
        val typeId = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
        val docId: String
        LibraryStore.open(dir).use { store ->
            docId = store.allDocuments().first().id
            seedSelectionNote(store, docId, typed, typeId = typeId)
            seedSelectionNote(store, docId, orphan, typeId = "11111111-0000-0000-0000-000000000000")
        }
        // 键名同 Mac `NoteType` 的 CodingKeys（id / name / color_key / icon_name）
        val types = org.json.JSONArray().put(
            JSONObject().put("id", typeId).put("name", "重点")
                .put("color_key", "red").put("icon_name", "flag"),
        )
        android.database.sqlite.SQLiteDatabase.openDatabase(
            File(dir, Workspace.DB_REL).absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        ).use { it.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES('note_types',?)", arrayOf(types.toString())) }

        LibraryStore.open(dir, readOnly = true).use { store ->
            assertEquals("meta 里就一个类型", 1, store.noteTypeColors().size)
            // 两条注解同页同秒落库，先后不定 → 按颜色认，不按下标认（下标断言会随机挂）
            val colors = store.textFills(docId).map { listOf(it.r, it.g, it.b) }.toSet()
            assertTrue("有类型的要按色板铺色，实际 $colors", colors.contains(Palette.rgb("red").toList()))
            assertTrue("查不到的类型要回落通用暖黄，实际 $colors", colors.contains(listOf(255, 209, 38)))
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
