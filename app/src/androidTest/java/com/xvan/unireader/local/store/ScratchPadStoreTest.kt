package com.xvan.unireader.local.store

import androidx.test.platform.app.InstrumentationRegistry
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Pt3
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 草稿纸数据层的**插桩**测试，对应 Mac 侧 `spike/scratch-store-test.swift`（44 条断言）的
 * 持久化部分；跑法与 [LibraryStoreTest] 相同（fixture + cacheDir 副本，细节见那个文件头）。
 *
 * fixture 可能是 **v7 老库（没有 scratch_pad 表）**——安卓不建表，所以需要表的用例先按
 * Mac 端 `LibraryStore.swift` 的 DDL 原文把表补上（模拟「这个工作区被 v9 的 Mac 打开过一次」），
 * 缺表/缺列本身也各是一条要测的边界（handoff §2.3）。
 */
class ScratchPadStoreTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixtureDir(): File? =
        File(LibraryStoreTest.FIXTURE).listFiles()
            ?.firstOrNull { it.isDirectory && Workspace.looksLikeWorkspace(it) }

    private fun copyOfFixture(): File {
        val src = fixtureDir()!!
        val dst = File(ctx.cacheDir, "sptest/${src.name}")
        dst.deleteRecursively()
        src.copyRecursively(dst, overwrite = true)
        return dst
    }

    /** 直接用框架 API 改副本的 schema（同 LibraryStoreTest 塞 meta 的先例，不为测试给生产代码加写口） */
    private fun rawSql(dir: File, vararg statements: String) {
        android.database.sqlite.SQLiteDatabase.openDatabase(
            File(dir, Workspace.DB_REL).absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        ).use { db -> statements.forEach { db.execSQL(it) } }
    }

    /**
     * 按 Mac 端 DDL 原文补 scratch_pad 表（`Sources/Store/LibraryStore.swift` v8 建表 +
     * v9 `pattern` 列 + v10 `show_page` 列）。`withPattern=false` 造 **v8 形状**、
     * `withShowPage=false` 造 **v9 形状**——安卓不建表不迁移，两种老库都得受得住。
     */
    private fun ensureScratchTable(dir: File, withPattern: Boolean = true, withShowPage: Boolean = true) {
        val patternCol = if (withPattern) ", pattern TEXT NOT NULL DEFAULT 'dots'" else ""
        val showPageCol = if (withShowPage) ", show_page INTEGER NOT NULL DEFAULT 0" else ""
        rawSql(
            dir,
            "DROP TABLE IF EXISTS scratch_pad",
            """
            CREATE TABLE scratch_pad (
              id TEXT PRIMARY KEY,
              document_id TEXT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
              title TEXT NOT NULL DEFAULT '',
              anchor_page INTEGER NOT NULL DEFAULT 0,
              anchor_x REAL NOT NULL DEFAULT 0, anchor_y REAL NOT NULL DEFAULT 0,
              bg TEXT NOT NULL DEFAULT 'rgba(255,255,255,1.0)'
              $patternCol$showPageCol,
              created_at TEXT NOT NULL, updated_at TEXT NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS idx_scratch_pad_document ON scratch_pad(document_id)",
        )
    }

    private fun pad(
        docId: String,
        id: String,
        title: String = "",
        createdAt: String,
        bg: String = ScratchPad.DEFAULT_BG,
        pattern: String = ScratchPad.DEFAULT_PATTERN,
        showPage: Boolean = true,   // 新建的纸默认垫着它锚定的那一页（同 Mac / ScratchController.createAt）
    ) = ScratchPad(
        id = id, documentId = docId, title = title,
        anchorPage = 3, anchorX = 0.25, anchorY = 0.5,
        bg = bg, pattern = pattern, showPage = showPage,
        createdAt = createdAt, updatedAt = createdAt,
    )

    private val pen = Pen(20, 20, 20, 1f, 10f, 3)   // pencil

    /** 画布坐标的关键性质：可负、可远超 1——页内归一化那套 0~1 的假设在这里全不成立 */
    private val canvasPts = listOf(
        Pt3(-120.5f, 64.25f, 0.5f),
        Pt3(512f, -8.125f, 1f),
        Pt3(2048.75f, 900f, 0.25f),
    )

    // ---------- ① scratch_pad 表 CRUD ----------

    @Test
    fun 建纸读回且按创建序排序() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        ensureScratchTable(dir)
        LibraryStore.open(dir).use { store ->
            val docId = store.allDocuments().first().id
            // 故意倒序插入，排序必须按 created_at 而不是插入序/行号
            store.upsertScratchPad(pad(docId, "pad-b", createdAt = "2026-08-02T00:00:00.000Z"))
            store.upsertScratchPad(pad(docId, "pad-a", title = "推导", createdAt = "2026-08-01T00:00:00.000Z"))
            val pads = store.scratchPads(docId).filter { it.id == "pad-a" || it.id == "pad-b" }
            assertEquals(listOf("pad-a", "pad-b"), pads.map { it.id })
            val a = pads.first()
            assertEquals("推导", a.title)
            assertEquals(3, a.anchorPage)
            assertEquals(0.25, a.anchorX, 1e-9)
            assertEquals(0.5, a.anchorY, 1e-9)
            assertEquals("默认底色 = 纯白", ScratchPad.DEFAULT_BG, a.bg)
            assertEquals("默认底纹 = 点阵", "dots", a.pattern)
            assertEquals("空标题原样保留（显示名由 UI 兜底）", "", pads[1].title)
            assertTrue("按文档隔离", store.scratchPads("别的文档").isEmpty())
        }
    }

    @Test
    fun 改纸样与改名都走upsert不新增行且plain不会被兜底吃掉() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        ensureScratchTable(dir)
        LibraryStore.open(dir).use { store ->
            val docId = store.allDocuments().first().id
            store.upsertScratchPad(pad(docId, "pad-a", createdAt = "2026-08-01T00:00:00.000Z"))
            // 改纸样：牛皮 + 小格
            store.upsertScratchPad(
                pad(docId, "pad-a", createdAt = "2026-08-01T00:00:00.000Z",
                    bg = "rgba(246,236,214,1.0)", pattern = "grid"),
            )
            var back = store.scratchPads(docId).filter { it.id == "pad-a" }
            assertEquals("改纸样不新增行", 1, back.size)
            assertEquals("rgba(246,236,214,1.0)", back[0].bg)
            assertEquals("grid", back[0].pattern)
            // plain 编码为 0/字符串 "plain"，是最容易被 ?: dots 这类兜底悄悄改掉的值，单独试
            store.upsertScratchPad(
                pad(docId, "pad-a", createdAt = "2026-08-01T00:00:00.000Z",
                    bg = "rgba(246,236,214,1.0)", pattern = "plain"),
            )
            back = store.scratchPads(docId).filter { it.id == "pad-a" }
            assertEquals("plain 底纹不能被默认值吃掉", "plain", back[0].pattern)
            // 改名
            store.upsertScratchPad(
                pad(docId, "pad-a", title = "改过的名字", createdAt = "2026-08-01T00:00:00.000Z"),
            )
            back = store.scratchPads(docId).filter { it.id == "pad-a" }
            assertEquals(1, back.size)
            assertEquals("改过的名字", back[0].title)
            // 未知底纹串（未来 Mac 加的）回落 dots，同 Mac 的 ?? .dots
            store.upsertScratchPad(
                pad(docId, "pad-a", createdAt = "2026-08-01T00:00:00.000Z", pattern = "crosshatch"),
            )
            assertEquals("dots", store.scratchPads(docId).single { it.id == "pad-a" }.pattern)
        }
    }

    // ---------- ② 草稿纸笔迹（note kind=4，画布坐标）与 kind 分流 ----------

    @Test
    fun 草稿纸笔迹画布坐标无损且与页内笔迹互不串台() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        ensureScratchTable(dir)
        LibraryStore.open(dir).use { store ->
            val docId = store.allDocuments().first().id
            store.upsertScratchPad(pad(docId, "pad-a", createdAt = "2026-08-01T00:00:00.000Z"))
            store.upsertScratchPad(pad(docId, "pad-b", createdAt = "2026-08-02T00:00:00.000Z"))
            val s1 = store.insertScratchStroke(docId, "pad-a", pen, canvasPts)!!
            store.insertScratchStroke(docId, "pad-b", pen, listOf(Pt3(0f, 0f, 0.5f), Pt3(1f, 1f, 0.5f)))
            // 页内笔迹（kind=2）与页内加载路径
            val pageStroke = store.insertStroke(
                docId, 5, pen, listOf(Pt3(0.1f, 0.2f, 0.5f), Pt3(0.3f, 0.4f, 0.6f)),
            )!!

            val n1 = store.notes(docId).first { it.id == s1 }
            assertEquals("草稿纸笔迹落 kind=4", NoteKind.SCRATCH_INK, n1.kind)
            assertEquals("page 列固定 0", 0, n1.page)

            val byPad = store.scratchStrokes(docId)
            val r = byPad.getValue("pad-a").single()
            assertEquals(s1, r.id)
            assertEquals("padId 保留（否则这笔就成了无处可归的孤儿）", "pad-a", r.padId)
            assertEquals("负数/大数画布坐标逐点无损", canvasPts, r.pts)
            assertEquals(pen, r.pen)

            // 页内读取只拿到 kind=2，padId 为空，kind=4 的行一条都不许混进来
            val pageOnly = store.strokes(docId).filter { it.id == pageStroke || it.id == s1 }
            assertEquals(1, pageOnly.size)
            assertEquals(pageStroke, pageOnly[0].id)
            assertEquals("", pageOnly[0].padId)
            assertFalse(store.strokes(docId).any { it.padId.isNotEmpty() })
        }
    }

    @Test
    fun 页内笔迹payload不写padId键而草稿纸笔迹写() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        ensureScratchTable(dir)
        LibraryStore.open(dir).use { store ->
            val docId = store.allDocuments().first().id
            store.upsertScratchPad(pad(docId, "pad-a", createdAt = "2026-08-01T00:00:00.000Z"))
            val pageId = store.insertStroke(docId, 0, pen, listOf(Pt3(0.1f, 0.1f, 0.5f)))!!
            val scratchId = store.insertScratchStroke(docId, "pad-a", pen, listOf(Pt3(1f, 1f, 0.5f)))!!
            val notes = store.notes(docId).associateBy { it.id }
            // 页内笔迹不写 padId 键（键不存在 = 不是草稿纸笔迹），既有 payload 形状逐字节不变
            assertFalse(JSONObject(String(notes.getValue(pageId).payload)).has("padId"))
            assertEquals("pad-a", JSONObject(String(notes.getValue(scratchId).payload)).getString("padId"))
        }
    }

    @Test
    fun kind4缺padId的孤儿行被判坏跳过而不混进页内() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        ensureScratchTable(dir)
        LibraryStore.open(dir).use { store ->
            val docId = store.allDocuments().first().id
            // 手工造一条 kind=4 但 payload 没有 padId 的坏行（升级中途/手改坏的库）
            val now = Iso.now()
            store.upsertNote(
                LibNote(
                    id = "orphan-note", documentId = docId, kind = NoteKind.SCRATCH_INK, page = 0,
                    anchorX = 0.0, anchorY = 0.0, anchorW = 0.0, anchorH = 0.0,
                    payload = JSONObject()
                        .put("color", JSONObject().put("r", 24.0).put("g", 90.0).put("b", 210.0).put("a", 0.95))
                        .put("width", 8.0)
                        .put("points", org.json.JSONArray().put(org.json.JSONArray().put(0.1).put(0.2).put(0.5)))
                        .toString().toByteArray(),
                    createdAt = now, updatedAt = now,
                ),
            )
            assertTrue(
                "孤儿行不进草稿纸读取",
                store.scratchStrokes(docId).values.flatten().none { it.id == "orphan-note" },
            )
            assertFalse(
                "孤儿行也绝不许混进页内笔迹",
                store.strokes(docId).any { it.id == "orphan-note" },
            )
        }
    }

    // ---------- ③ 删纸连带删笔迹（handoff §2.4） ----------

    @Test
    fun 删纸连带删掉纸上全部笔迹而页内笔迹不动() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        ensureScratchTable(dir)
        LibraryStore.open(dir).use { store ->
            val docId = store.allDocuments().first().id
            store.upsertScratchPad(pad(docId, "pad-a", createdAt = "2026-08-01T00:00:00.000Z"))
            store.upsertScratchPad(pad(docId, "pad-b", createdAt = "2026-08-02T00:00:00.000Z"))
            store.insertScratchStroke(docId, "pad-a", pen, canvasPts)
            store.insertScratchStroke(docId, "pad-a", pen, listOf(Pt3(7f, 7f, 0.5f)))
            store.insertScratchStroke(docId, "pad-b", pen, listOf(Pt3(9f, 9f, 0.5f)))
            val pageStroke = store.insertStroke(docId, 1, pen, listOf(Pt3(0.5f, 0.5f, 0.5f)))!!

            store.deleteScratchPad("pad-a")

            assertFalse(
                "纸行删掉了",
                store.scratchPads(docId).any { it.id == "pad-a" },
            )
            assertTrue(store.scratchPads(docId).any { it.id == "pad-b" })
            val rest = store.scratchStrokes(docId)
            assertFalse("pad-a 的两笔要连带删掉", rest.containsKey("pad-a"))
            assertEquals(1, rest.getValue("pad-b").size)
            assertNotNull(
                "页内笔迹一条都不许动",
                store.notes(docId).firstOrNull { it.id == pageStroke },
            )
        }
    }

    // ---------- ④ 老库边界（handoff §2.3） ----------

    @Test
    fun 表缺失时当没有草稿纸且开文档流程不炸() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        rawSql(dir, "DROP TABLE IF EXISTS scratch_pad")   // v7 老库的形状
        LibraryStore.open(dir, readOnly = true).use { store ->
            val docId = store.allDocuments().first().id
            assertEquals("表不存在 = 没有草稿纸，而不是抛错", emptyList<ScratchPad>(), store.scratchPads(docId))
            assertTrue("页内笔迹照常读（kind=4 分流不依赖 scratch_pad 表）", store.strokes(docId).size >= 0)
        }
        // 写路径也不许炸：表不存在时落库跳过（记日志），deleteScratchPad 照样能跑（顺带清孤儿笔迹）
        LibraryStore.open(dir).use { store ->
            val docId = store.allDocuments().first().id
            store.upsertScratchPad(pad(docId, "pad-x", createdAt = "2026-08-01T00:00:00.000Z"))
            assertTrue(store.scratchPads(docId).isEmpty())
            store.deleteScratchPad("pad-x")
        }
    }

    @Test
    fun pattern列缺失时读兜底dots且其余字段照写() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        ensureScratchTable(dir, withPattern = false)   // v8 形状
        LibraryStore.open(dir).use { store ->
            val docId = store.allDocuments().first().id
            store.upsertScratchPad(
                pad(docId, "pad-a", title = "v8 的纸", createdAt = "2026-08-01T00:00:00.000Z",
                    bg = "rgba(233,243,234,1.0)", pattern = "grid"),
            )
            val back = store.scratchPads(docId).single()
            assertEquals("底纹写不进去，读回兜底 dots", "dots", back.pattern)
            assertEquals("纸色照写", "rgba(233,243,234,1.0)", back.bg)
            assertEquals("v8 的纸", back.title)
        }
    }

    @Test
    fun 页面底图开关round_trip() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        ensureScratchTable(dir)
        LibraryStore.open(dir).use { store ->
            val docId = store.allDocuments().first().id
            store.upsertScratchPad(pad(docId, "pad-a", createdAt = "2026-08-01T00:00:00.000Z"))
            assertTrue("新建的纸默认垫页", store.scratchPads(docId).single().showPage)
            // 关掉：默认值是 true，最容易被兜底写回成「开」
            store.upsertScratchPad(
                pad(docId, "pad-a", createdAt = "2026-08-01T00:00:00.000Z", showPage = false),
            )
            assertFalse("关掉要真的存下来", store.scratchPads(docId).single().showPage)
            store.upsertScratchPad(pad(docId, "pad-a", createdAt = "2026-08-01T00:00:00.000Z"))
            assertTrue("再开回来", store.scratchPads(docId).single().showPage)
        }
    }

    @Test
    fun show_page列缺失时读兜底关且其余字段照写() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        ensureScratchTable(dir, withShowPage = false)   // v9 形状
        LibraryStore.open(dir).use { store ->
            val docId = store.allDocuments().first().id
            store.upsertScratchPad(
                pad(
                    docId, "pad-a", title = "v9 的纸", createdAt = "2026-08-01T00:00:00.000Z",
                    pattern = "grid", showPage = true,
                ),
            )
            val back = store.scratchPads(docId).single()
            assertFalse("页面底图写不进去，读回兜底关（同 Mac 的迁移口径）", back.showPage)
            assertEquals("底纹照写", "grid", back.pattern)
            assertEquals("v9 的纸", back.title)
        }
    }

    // ---------- ⑤ payload 原地改（handoff §2.3③） ----------

    @Test
    fun 改点集保留padId与未知键() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        ensureScratchTable(dir)
        LibraryStore.open(dir).use { store ->
            val docId = store.allDocuments().first().id
            store.upsertScratchPad(pad(docId, "pad-a", createdAt = "2026-08-01T00:00:00.000Z"))
            val id = store.insertScratchStroke(docId, "pad-a", pen, canvasPts)!!
            // 塞一个「本端不认识」的键，模拟 Mac 先加字段的情形
            val n0 = store.notes(docId).first { it.id == id }
            val o = JSONObject(String(n0.payload)).put("futureKey", "别把我弄丢")
            store.upsertNote(n0.copy(payload = o.toString().toByteArray()))
            // 局部擦除后回写存活段：withPoints 是原地改，padId 与未知键都必须还在
            store.updateStrokePoints(id, listOf(Pt3(-120.5f, 64.25f, 0.5f)))

            val n = store.notes(docId).first { it.id == id }
            val back = JSONObject(String(n.payload))
            assertEquals("padId 不许在回写里丢", "pad-a", back.getString("padId"))
            assertEquals("未知键必须原样保留", "别把我弄丢", back.getString("futureKey"))
            assertEquals(1, back.getJSONArray("points").length())
            // 读回中立模型仍归 pad-a
            assertEquals(id, store.scratchStrokes(docId).getValue("pad-a").single().id)
        }
    }
}
