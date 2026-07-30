package com.xvan.unireader.local.store

import android.util.Log
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.shared.Layer
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Pt3
import com.xvan.unireader.shared.Stroke
import java.io.Closeable
import java.io.File
import java.util.UUID

/**
 * 一个工作区的持久层，对应 Mac 端 `Sources/Store/LibraryStore.swift`（schema v7）。
 *
 * 与 Mac 端的**唯一区别**：这里不建表、不迁移（见 [Db.open] 的说明）。SQL 语句逐条照抄 Mac，
 * 包括 `ORDER BY`——列表顺序不一致会让「Mac 上第 3 个文档」和平板上的第 3 个不是同一本。
 *
 * **非线程安全，同一时刻只能有一个线程用它**。实际的交接是：在后台线程 [open]（开库 +
 * `wal_checkpoint` 在慢卷上是秒级，不能放主线程——§9.5），交给主线程独占使用（读写笔迹、写进度），
 * 界面销毁时再由后台线程 [close]（同样含 checkpoint）。每次交接都经过 `Handler`/`Executor`，
 * 有 happens-before，不需要额外加锁；但**别在主线程还用着的时候另起线程读它**。
 * 后台渲染线程只碰 [com.xvan.unireader.local.PdfSource]，不碰这里。
 */
class LibraryStore(private val db: Db) : Closeable {

    companion object {
        const val TAG = "UniReader/Store"
        const val SCHEMA_VERSION = 7

        /** 打开工作区里的库。`readOnly` 用于「库文件不可写」的场景（U 盘只读挂载等）。 */
        fun open(workspaceDir: File, readOnly: Boolean = false): LibraryStore {
            val store = LibraryStore(Db.open(File(workspaceDir, Workspace.DB_REL), readOnly))
            val v = store.meta("schema_version")
            if (v != SCHEMA_VERSION.toString()) {
                // 不拦：读得动就先读（Mac 升版后平板可能还没更新）。但必须留痕，
                // 否则「某些笔记不显示」会被当成渲染 bug 查很久。
                Log.w(TAG, "schema_version=$v，本端按 v$SCHEMA_VERSION 解析——字段可能对不上")
            }
            store.checkpoint()   // 打开即把 Mac 留下的 -wal 合并进主库（§9.2；只读连接自动跳过）
            return store
        }
    }

    /** 打开/关闭各做一次；搬运工作区时只拷 .sqlite 也不会丢最近的写入 */
    fun checkpoint() = db.walCheckpointTruncate()

    override fun close() = db.close()

    // ---------- meta ----------

    fun meta(key: String): String? =
        db.query("SELECT value FROM meta WHERE key=?", arrayOf(key)) { it.strOrNull("value") }
            .firstOrNull()

    fun workspaceName(): String = meta("workspace_name") ?: ""

    // ---------- document ----------

    fun allDocuments(): List<LibDocument> =
        db.query("SELECT * FROM document ORDER BY sort_order ASC, last_opened_at DESC") { doc(it) }

    fun document(id: String): LibDocument? =
        db.query("SELECT * FROM document WHERE id=?", arrayOf(id)) { doc(it) }.firstOrNull()

    fun updateLastOpened(documentId: String, at: String = nowIso()) {
        db.exec("UPDATE document SET last_opened_at=? WHERE id=?", arrayOf(at, documentId))
    }

    /**
     * 记录阅读进度（顶部所在页 + 页内比例 + 缩放倍率 + 横向滚动比例）。
     * frac/hfrac 夹到 0~1，与 Mac 端 `updateProgress` 一致——越界值会让另一端复原时跳到页外。
     */
    fun updateProgress(documentId: String, page: Int, frac: Double, zoom: Double, hfrac: Double) {
        db.exec(
            "UPDATE document SET read_page=?, read_frac=?, read_zoom=?, read_hfrac=? WHERE id=?",
            arrayOf(page, frac.coerceIn(0.0, 1.0), zoom, hfrac.coerceIn(0.0, 1.0), documentId),
        )
    }

    // ---------- variant / location ----------

    fun variants(documentId: String): List<LibVariant> =
        db.query(
            "SELECT * FROM variant WHERE document_id=? ORDER BY added_at ASC",
            arrayOf(documentId),
        ) { variant(it) }

    /** 逻辑文档的全部路径（跨版本），有效优先——同 Mac 的 `locations(documentId:)` */
    fun locations(documentId: String): List<LibLocation> = db.query(
        """
        SELECT location.* FROM location
        JOIN variant ON location.variant_id = variant.id
        WHERE variant.document_id=? ORDER BY location.is_valid DESC
        """.trimIndent(),
        arrayOf(documentId),
    ) { location(it) }

    // ---------- note ----------

    fun notes(documentId: String): List<LibNote> = db.query(
        "SELECT * FROM note WHERE document_id=? ORDER BY page ASC, created_at ASC",
        arrayOf(documentId),
    ) { note(it) }

    fun noteCount(documentId: String, kind: Int): Int = db.query(
        "SELECT COUNT(*) AS n FROM note WHERE document_id=? AND kind=?",
        arrayOf(documentId, kind),
    ) { it.int("n") }.firstOrNull() ?: 0

    /**
     * 插入或更新一条笔记。SQL 与 Mac 的 `upsertNote` 逐字一致：`ON CONFLICT(id)` 不改
     * `created_at`（只更新 `updated_at`），否则每次局部擦除都会把原笔迹的创建时间刷成现在，
     * 按 `created_at` 排序的绘制顺序（谁盖谁）就乱了。
     */
    fun upsertNote(n: LibNote) {
        db.exec(
            """
            INSERT INTO note(id,document_id,kind,page,anchor_x,anchor_y,anchor_w,anchor_h,payload,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET kind=excluded.kind, page=excluded.page,
              anchor_x=excluded.anchor_x, anchor_y=excluded.anchor_y,
              anchor_w=excluded.anchor_w, anchor_h=excluded.anchor_h,
              payload=excluded.payload, updated_at=excluded.updated_at
            """.trimIndent(),
            arrayOf(
                n.id, n.documentId, n.kind, n.page,
                n.anchorX, n.anchorY, n.anchorW, n.anchorH,
                n.payload, n.createdAt, n.updatedAt,
            ),
        )
    }

    fun deleteNote(id: String) = db.exec("DELETE FROM note WHERE id=?", arrayOf(id))

    /** 擦除是「删若干 + 插若干」，必须一个事务——半途崩掉会留下切了一半的笔迹（§9.3） */
    fun <T> transaction(body: () -> T): T = db.transaction(body)

    // ---------- 笔迹（note kind=2 的读写门面） ----------

    /**
     * 读出一篇文档的全部笔迹。payload 坏掉的单条**跳过并记日志**，不让一条坏数据把整篇
     * 笔迹拖没（这类问题不打点就只能看到「笔迹少了几笔」）。
     */
    fun strokes(documentId: String): List<Stroke> {
        val out = ArrayList<Stroke>()
        var bad = 0
        for (n in notes(documentId)) {
            if (n.kind != NoteKind.INK) continue
            val p = InkPayload.parse(n.payload)
            if (p == null) { bad++; continue }
            out.add(p.toStroke(n.id, n.page))
        }
        if (bad > 0) Log.w(TAG, "$documentId：$bad 条笔迹 payload 坏掉已跳过")
        return out
    }

    /** 落一笔新笔迹（一笔一次 INSERT，别每帧写——同 Mac 的增量落库） */
    fun insertStroke(
        documentId: String,
        page: Int,
        pen: Pen,
        pts: List<Pt3>,
        layerId: String = LibInkLayer.DEFAULT_ID,
        id: String = UUID.randomUUID().toString(),
    ): String? {
        if (pts.isEmpty()) return null   // 空笔画不落库（同 Mac `toNote` 的 guard）
        val b = boundsOf(pts)
        val now = nowIso()
        upsertNote(
            LibNote(
                id = id, documentId = documentId, kind = NoteKind.INK, page = page,
                anchorX = b[0], anchorY = b[1], anchorW = b[2], anchorH = b[3],
                payload = InkPayload.of(pen, pts, layerId).bytes(),
                createdAt = now, updatedAt = now,
            ),
        )
        return id
    }

    /**
     * 改一条已有笔迹的点集（局部擦除切段后的存活段 / 框选平移）。
     * 原 payload 的其余键（含本端还不认识的）原样保留，anchor 随新点集重算。
     */
    fun updateStrokePoints(noteId: String, pts: List<Pt3>) {
        val n = db.query("SELECT * FROM note WHERE id=?", arrayOf(noteId)) { note(it) }.firstOrNull()
            ?: return
        val p = InkPayload.parse(n.payload) ?: return
        val b = boundsOf(pts)
        upsertNote(
            n.copy(
                anchorX = b[0], anchorY = b[1], anchorW = b[2], anchorH = b[3],
                payload = p.withPoints(pts).bytes(),
                updatedAt = nowIso(),
            ),
        )
    }

    // ---------- ink_layer ----------

    fun inkLayers(documentId: String): List<LibInkLayer> = db.query(
        "SELECT * FROM ink_layer WHERE document_id=? ORDER BY sort_order ASC",
        arrayOf(documentId),
    ) { inkLayer(it) }

    /** 图层表 → 中立模型（色点用色板换算，见 [Palette]），供面板与线格式共用同一形状 */
    fun layersForUi(documentId: String): List<Layer> = inkLayers(documentId).map {
        val rgb = Palette.rgb(it.colorKey)
        Layer(rgb[0], rgb[1], rgb[2], it.visible, it.name)
    }

    fun upsertInkLayer(l: LibInkLayer) {
        db.exec(
            """
            INSERT INTO ink_layer(id,document_id,name,color_key,sort_order,visible,created_at)
            VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET name=excluded.name, color_key=excluded.color_key,
              sort_order=excluded.sort_order, visible=excluded.visible
            """.trimIndent(),
            arrayOf(l.id, l.documentId, l.name, l.colorKey, l.sortOrder, if (l.visible) 1 else 0, l.createdAt),
        )
    }

    /**
     * 新建图层（同 Mac `InkLayer.next`）：序号紧接现有最大 sortOrder，颜色轮换取色板。
     * 名字用 `Layer N` 的英文形态——Mac 那边走 `L("Layer %d")` 本地化，两端文案不必一致，
     * 但**不能各自改名字规则**，否则同一个图层在两端显示成两个名字。
     */
    fun addInkLayer(documentId: String): LibInkLayer {
        val existing = inkLayers(documentId)
        val l = LibInkLayer(
            id = UUID.randomUUID().toString(),
            documentId = documentId,
            name = "Layer ${existing.size + 1}",
            colorKey = Palette.rotatingKey(existing.size),
            sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            visible = true,
            createdAt = nowIso(),
        )
        upsertInkLayer(l)
        return l
    }

    /**
     * 确保默认图层存在：Mac 那边「文档首次在新版本打开时会自动补建一条同 id 的图层 1」
     * （`InkLayer.defaultID` 的注释）。平板先打开一个 v6 时代的老文档时同样要补，
     * 否则笔迹的 layerId 指向一个不存在的图层，图层面板里它就消失了。
     */
    fun ensureDefaultLayer(documentId: String) {
        val has = db.query(
            "SELECT id FROM ink_layer WHERE id=? AND document_id=?",
            arrayOf(LibInkLayer.DEFAULT_ID, documentId),
        ) { it.str("id") }.isNotEmpty()
        if (has) return
        if (inkLayers(documentId).isNotEmpty()) return   // 已有别的图层：不插队占位
        Log.i(TAG, "$documentId 缺默认图层，补建一条")
        upsertInkLayer(
            LibInkLayer(
                id = LibInkLayer.DEFAULT_ID, documentId = documentId, name = "Layer 1",
                colorKey = Palette.rotatingKey(0), sortOrder = 0, visible = true, createdAt = nowIso(),
            ),
        )
    }

    // ---------- 行 → 模型 ----------

    private fun doc(c: android.database.Cursor) = LibDocument(
        id = c.str("id"), title = c.str("title"), pageCount = c.int("page_count"),
        addedAt = c.str("added_at"), lastOpenedAt = c.str("last_opened_at"),
        sortOrder = c.int("sort_order"), readPage = c.int("read_page"),
        readFrac = c.dbl("read_frac"), readZoom = c.dbl("read_zoom", 1.0),
        readHFrac = c.dbl("read_hfrac"),
    )

    private fun variant(c: android.database.Cursor) = LibVariant(
        id = c.str("id"), documentId = c.str("document_id"), contentHash = c.str("content_hash"),
        pageCount = c.int("page_count"), addedAt = c.str("added_at"),
    )

    private fun location(c: android.database.Cursor) = LibLocation(
        id = c.str("id"), variantId = c.str("variant_id"), path = c.str("path"),
        isValid = c.bool("is_valid", true), lastValidatedAt = c.strOrNull("last_validated_at"),
        inWorkspace = c.bool("in_workspace"), isRelative = c.bool("is_relative"),
    )

    private fun note(c: android.database.Cursor) = LibNote(
        id = c.str("id"), documentId = c.str("document_id"), kind = c.int("kind"), page = c.int("page"),
        anchorX = c.dbl("anchor_x"), anchorY = c.dbl("anchor_y"),
        anchorW = c.dbl("anchor_w"), anchorH = c.dbl("anchor_h"),
        payload = c.blob("payload"), createdAt = c.str("created_at"), updatedAt = c.str("updated_at"),
    )

    private fun inkLayer(c: android.database.Cursor) = LibInkLayer(
        id = c.str("id"), documentId = c.str("document_id"), name = c.str("name"),
        colorKey = c.str("color_key"), sortOrder = c.int("sort_order"),
        visible = c.bool("visible", true), createdAt = c.str("created_at"),
    )
}
