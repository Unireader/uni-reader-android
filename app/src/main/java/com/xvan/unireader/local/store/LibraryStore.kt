package com.xvan.unireader.local.store

import android.util.Log
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.shared.InkEdit
import com.xvan.unireader.shared.Layer
import com.xvan.unireader.shared.PadConst
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Pt3
import com.xvan.unireader.shared.Stroke
import com.xvan.unireader.shared.TextFill
import com.xvan.unireader.shared.TextNote
import java.io.Closeable
import java.io.File
import java.util.UUID

/**
 * 一个工作区的持久层，对应 Mac 端 `Sources/Store/LibraryStore.swift`（schema v7）。
 *
 * 与 Mac 端的**唯一区别**：这里不建表、不迁移（见 [Db.open] 的说明）。SQL 语句逐条照抄 Mac，
 * 包括 `ORDER BY`——列表顺序不一致会让「Mac 上第 3 个文档」和平板上的第 3 个不是同一本。
 *
 * **非线程安全，同一时刻只能有一个线程用它**。所有权的交接是：在后台线程 [open]（开库 +
 * `wal_checkpoint` 在慢卷上是秒级，不能放主线程——§9.5），交给 [StoreQueue] 的独占线程读写，
 * 界面销毁时由同一条队列 [close]（同样含 checkpoint）。每次交接都经过 `Handler`/`Executor`，
 * 有 happens-before，不需要额外加锁；但**主线程一次都不该直接碰它**——读也一样，读同样走磁盘。
 * 后台渲染线程只碰 [com.xvan.unireader.local.PdfSource]，不碰这里。
 *
 * 例外只有一处：[com.xvan.unireader.local.LibraryActivity] 列书单时自己开一个**只读**连接，
 * 在后台线程上用完即关，不外泄——那是另一个连接，不是这一个。
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

    /** 这个卷上 WAL 是否真的启用（false = FAT32/exFAT 之类，界面要提示，见 [Db.walEnabled]） */
    val walEnabled: Boolean get() = db.walEnabled

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

    // ---------- 文字注解（note kind=0）与高亮（kind=3） ----------

    /**
     * 读出一篇文档的全部文字注解。
     *
     * `nx`/`ny` 取 `anchor_x`/`anchor_y`——**与 Mac `broadcastNotes` 发给平板的两列完全相同**，
     * 所以同一条注解的标记在模式1 与模式2 落在同一处；Mac 自己阅读区把选区注解的图钉挪到行末
     * 那是它的显示偏好，不是数据。坏 payload 单条跳过（同 [strokes] 的口径）。
     */
    fun textNotes(documentId: String): List<TextNote> {
        val out = ArrayList<TextNote>()
        var bad = 0
        for (n in notes(documentId)) {
            if (n.kind != NoteKind.TEXT) continue
            val p = TextNotePayload.parse(n.payload)
            if (p == null) { bad++; continue }
            out.add(TextNote(n.id, n.page.toLong(), n.anchorX.toFloat(), n.anchorY.toFloat(), p.text))
        }
        if (bad > 0) Log.w(TAG, "$documentId：$bad 条文字注解 payload 坏掉已跳过")
        return out
    }

    /**
     * 新建或改写一条文字注解，语义逐条对齐 Mac `AppModel.applyTextNote`：
     * - 已存在（含 Mac 建的**选区注解**）：**只改正文**，anchor/quote/rects/color/type_id 一律不动。
     *   ——平板上编辑一条选区注解不该把它退化成点注解，那是不可逆的丢数据。
     * - 不存在：建一条零尺寸 anchor 的点注解（anchor=落点，quote/rects 空）。
     * - 空文本等价删除（同 Mac 丢弃空点注解的语义），调用方也可直接调 [deleteNote]。
     */
    fun upsertTextNote(documentId: String, id: String, page: Int, nx: Float, ny: Float, text: String) {
        if (text.isBlank()) { deleteNote(id); return }
        val old = db.query("SELECT * FROM note WHERE id=?", arrayOf(id)) { note(it) }.firstOrNull()
        val now = nowIso()
        if (old != null && old.kind == NoteKind.TEXT) {
            val p = TextNotePayload.parse(old.payload) ?: TextNotePayload.ofPointNote(text)
            upsertNote(old.copy(payload = p.withText(text).bytes(), updatedAt = now))
            return
        }
        upsertNote(
            LibNote(
                id = id, documentId = documentId, kind = NoteKind.TEXT, page = page,
                anchorX = nx.toDouble(), anchorY = ny.toDouble(), anchorW = 0.0, anchorH = 0.0,
                payload = TextNotePayload.ofPointNote(text).bytes(),
                createdAt = now, updatedAt = now,
            ),
        )
    }

    /** 全部文字注解的锚定框（框选移动的命中判定用，见 [NoteAnchor]） */
    fun noteAnchors(documentId: String): List<NoteAnchor> = db.query(
        "SELECT id,page,anchor_x,anchor_y,anchor_w,anchor_h FROM note WHERE document_id=? AND kind=?",
        arrayOf(documentId, NoteKind.TEXT),
    ) {
        NoteAnchor(
            it.str("id"), it.int("page"),
            it.dbl("anchor_x").toFloat(), it.dbl("anchor_y").toFloat(),
            it.dbl("anchor_w").toFloat(), it.dbl("anchor_h").toFloat(),
        )
    }

    /**
     * 平移一条文字注解（框选移动）：anchor 与 payload 里的每个 rect 一起 +(dx, dy)，
     * 各角 clamp 到 0~1——与 Mac `InkEdit.translated(TextNote)` 同一实现（[InkEdit.translatedRect]）。
     * 点注解的零尺寸 anchor 照样平移，rects 为空则只动 anchor。
     */
    fun translateTextNote(noteId: String, dx: Float, dy: Float) {
        val n = db.query("SELECT * FROM note WHERE id=?", arrayOf(noteId)) { note(it) }.firstOrNull()
            ?: return
        val p = TextNotePayload.parse(n.payload) ?: return
        val a = InkEdit.translatedRect(
            doubleArrayOf(n.anchorX, n.anchorY, n.anchorW, n.anchorH), dx.toDouble(), dy.toDouble(),
        )
        val rs = p.rects().map { InkEdit.translatedRect(it, dx.toDouble(), dy.toDouble()) }
        upsertNote(
            n.copy(
                anchorX = a[0], anchorY = a[1], anchorW = a[2], anchorH = a[3],
                payload = if (rs.isEmpty()) p.bytes() else p.withRects(rs).bytes(),
                updatedAt = nowIso(),
            ),
        )
    }

    /**
     * 缩放一条文字注解（框选缩放）：anchor 与 payload 里的每个 rect 绕锚点 `(ax, ay)` 按轴缩放，
     * 各角 clamp 到 0~1——与 Mac `InkEdit.scaled(TextNote)` 同一实现（[InkEdit.scaledRect]）。
     * **字号不缩**（注解是文字不是图形），点注解的零尺寸 anchor 照样缩放，rects 为空则只动 anchor。
     */
    fun scaleTextNote(noteId: String, ax: Float, ay: Float, sx: Float, sy: Float) {
        val n = db.query("SELECT * FROM note WHERE id=?", arrayOf(noteId)) { note(it) }.firstOrNull()
            ?: return
        val p = TextNotePayload.parse(n.payload) ?: return
        val a = InkEdit.scaledRect(
            doubleArrayOf(n.anchorX, n.anchorY, n.anchorW, n.anchorH),
            ax.toDouble(), ay.toDouble(), sx.toDouble(), sy.toDouble(),
        )
        val rs = p.rects().map {
            InkEdit.scaledRect(it, ax.toDouble(), ay.toDouble(), sx.toDouble(), sy.toDouble())
        }
        upsertNote(
            n.copy(
                anchorX = a[0], anchorY = a[1], anchorW = a[2], anchorH = a[3],
                payload = if (rs.isEmpty()) p.bytes() else p.withRects(rs).bytes(),
                updatedAt = nowIso(),
            ),
        )
    }

    /**
     * 页面上要铺的所有色块：高亮（kind=3）按自身颜色 0.38，选区注解（kind=0 且有 rects）按
     * 类型色/通用暖黄 0.32——透明度口径见 [PadConst.FILL]，与 Mac `PageCellView` 一致。
     *
     * 返回顺序 = 绘制顺序：高亮在下、注解底色在上（同 Mac 的层序）。
     * 首版**只渲染不新建**（新建高亮要先有文字选择，属下一版），所以这里没有写入口。
     */
    fun textFills(documentId: String): List<TextFill> {
        val types = noteTypeColors()
        val hi = ArrayList<TextFill>()
        val nt = ArrayList<TextFill>()
        for (n in notes(documentId)) {
            when (n.kind) {
                NoteKind.HIGHLIGHT -> {
                    val p = TextNotePayload.parse(n.payload) ?: continue
                    val rs = p.rects()
                    if (rs.isEmpty()) continue
                    val c = p.color() ?: doubleArrayOf(255.0, 214.0, 40.0, 1.0)   // 同 Mac 默认荧光黄
                    hi.add(fillOf(n.page, rs, c[0].toInt(), c[1].toInt(), c[2].toInt(), PadConst.FILL.HIGHLIGHT_A))
                }
                NoteKind.TEXT -> {
                    val p = TextNotePayload.parse(n.payload) ?: continue
                    val rs = p.rects()
                    if (rs.isEmpty()) continue   // 点注解只有图钉，没有底色
                    val rgb = types[p.typeId?.uppercase()] ?: PadConst.FILL.NOTE_RGB
                    // 带上 note.id：框选拖动时底色要跟着图钉走（高亮不参与移动，故不填）
                    nt.add(fillOf(n.page, rs, rgb[0], rgb[1], rgb[2], PadConst.FILL.NOTE_A, n.id))
                }
            }
        }
        return hi + nt
    }

    private fun fillOf(
        page: Int,
        rs: List<DoubleArray>,
        r: Int,
        g: Int,
        b: Int,
        a: Float,
        noteId: String = "",
    ) = TextFill(
        page = page.toLong(),
        rects = rs.map { floatArrayOf(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat()) },
        r = r.coerceIn(0, 255), g = g.coerceIn(0, 255), b = b.coerceIn(0, 255), a = a,
        noteId = noteId,
    )

    /**
     * 工作区自定义笔记类型的色板（Mac `NoteType`：落 `meta(key='note_types')` 的 JSON 数组，
     * 键 `id`/`color_key`）。**没有这张表**——它就是一条 meta，坏掉/缺失按空处理，
     * 那样所有注解按通用暖黄铺色，而不是整篇不画。
     * 键统一大写：Swift 的 `UUID.uuidString` 是大写，手写进库的可能不是。
     */
    fun noteTypeColors(): Map<String, IntArray> {
        val raw = meta("note_types") ?: return emptyMap()
        return try {
            val arr = org.json.JSONArray(raw)
            val out = HashMap<String, IntArray>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").ifEmpty { continue }
                out[id.uppercase()] = Palette.rgb(o.optString("color_key"))
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "meta.note_types 解析失败，注解一律按通用色渲染", e)
            emptyMap()
        }
    }

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
     * 把库里的笔迹对齐到 [local]——擦除后**期望**的状态，按 `note.id` 分组（一条原笔迹被切成
     * 几段就有几个元素，段的先后即点集顺序）。差异按 id 比对：
     * - 库里有、[local] 里没有 → 整条被擦掉了，删。
     * - 只剩一段且点数没变 → 没被擦到，不动（擦除只会减少点，不会持平）。
     * - 其余 → **头一段改写原来那条**（[updateStrokePoints]，走 upsert 所以 `created_at` 不动、
     *   绘制顺序不变），多出来的段各插一条新的。
     *
     * **头一段必须沿用原 id，不能「删原条 + 全部新插」**：写库改到后台队列之后（§9.5），
     * 「擦一笔 → 落库 → 重读回推」这条链变长了，用户完全可能在回推到达之前又擦一笔——那一次的
     * 期望状态是照着**旧 id** 算出来的。只要原 id 还在库里，第二次比对就仍然对得上（多余的新段
     * 会被删掉重插，内容照样收敛）；要是原 id 已经被删掉换成了随机新 id，第二次比对会发现
     * 「库里这些 id 期望状态里一个都没有」，于是把整页笔迹删光——静默丢数据，最难查的那一类。
     *
     * **隐藏图层上的笔迹一条都不碰**：[local] 是画布上看得见的那些（`applyStrokes` 按可见性滤过），
     * 隐藏层的笔迹根本不在里面，照上面的规则会被当成「被擦光了」全删掉——藏一个图层再随便擦一下，
     * 那一层就没了，而界面上什么都看不出来。同 Mac `eraseNear` 的 `vis.contains(layerId)` 口径。
     *
     * 调用方在队列线程上跑（见 [StoreQueue]），整批一个事务：半途崩掉会留下切了一半的笔迹（§9.3）。
     */
    fun reconcileStrokes(documentId: String, local: Map<String, List<Stroke>>): InkDiff {
        var deleted = 0
        var updated = 0
        var inserted = 0
        val hidden = inkLayers(documentId).filter { !it.visible }.map { it.id }.toSet()
        transaction {
            for (old in strokes(documentId)) {
                if (old.layerId in hidden) continue
                val segs = local[old.id]
                if (segs == null) {
                    deleteNote(old.id)
                    deleted++
                    continue
                }
                if (segs.size == 1 && segs[0].pts.size == old.pts.size) continue
                updateStrokePoints(old.id, segs[0].pts)
                updated++
                for (i in 1 until segs.size) {
                    val seg = segs[i]
                    insertStroke(documentId, seg.page.toInt(), seg.pen, seg.pts, seg.layerId)
                    inserted++
                }
            }
        }
        return InkDiff(deleted, updated, inserted)
    }

    /**
     * 改一条已有笔迹的点集（局部擦除切段后的存活段 / 框选平移 / 框选缩放）。
     * 原 payload 的其余键（含本端还不认识的）原样保留，anchor 随新点集重算；
     * [width] 非空时同步改线宽（框选缩放：笔宽 ×√(sx·sy)，调用方已按 Mac 口径 clamp）。
     */
    fun updateStrokePoints(noteId: String, pts: List<Pt3>, width: Float? = null) {
        val n = db.query("SELECT * FROM note WHERE id=?", arrayOf(noteId)) { note(it) }.firstOrNull()
            ?: return
        val p = InkPayload.parse(n.payload) ?: return
        val b = boundsOf(pts)
        upsertNote(
            n.copy(
                anchorX = b[0], anchorY = b[1], anchorW = b[2], anchorH = b[3],
                payload = (if (width != null) p.withWidth(width.toDouble()) else p).withPoints(pts).bytes(),
                updatedAt = nowIso(),
            ),
        )
    }

    // ---------- 草稿纸（scratch_pad 表：v8 建表 / v9 加 pattern 列 / v10 加 show_page 列；笔迹在 note kind=4） ----------

    /**
     * `scratch_pad` 的列名集；**表不存在**（v7 老库，还没被 v8+ 的 Mac 打开过）→ null。
     * 安卓不建表不迁移，于是每个入口都先探一次再决定怎么办（结果缓存：schema 不会在本端手里变）。
     * `PRAGMA table_info` 对不存在的表返回零行而不是抛错，正好拿来做这件事。
     */
    private val scratchPadCols: Set<String>? by lazy {
        db.query("PRAGMA table_info(scratch_pad)") { it.str("name") }
            .toSet().ifEmpty { null }
    }

    /**
     * 一篇文档的全部草稿纸，**`ORDER BY created_at ASC` 照抄 Mac**——顺序不一致会让
     * 「第 2 张纸」在两端不是同一张。v7 老库没有这张表 → 当「没有草稿纸」返回空，
     * 绝不能让它把开文档流程炸掉（handoff §2.3①）。
     */
    fun scratchPads(documentId: String): List<ScratchPad> {
        if (scratchPadCols == null) return emptyList()
        return db.query(
            "SELECT * FROM scratch_pad WHERE document_id=? ORDER BY created_at ASC",
            arrayOf(documentId),
        ) { scratchPad(it) }
    }

    /**
     * 新建/改名/改纸样都走这一个 upsert（SQL 照抄 Mac）。两个老库边界：
     * - 表不存在（v7）：写不进去，记日志后**跳过**——不建表是红线，调用方按「建不了纸」处理；
     * - `pattern` 列不存在（v8）：底纹写不进去，其余字段照写，读回来兜底 dots（§2.3②）。
     */
    fun upsertScratchPad(p: ScratchPad) {
        val cols = scratchPadCols
        if (cols == null) {
            Log.w(TAG, "scratch_pad 表不存在（v7 老库），草稿纸 id=${p.id.take(8)} 未落库——用 v8+ 的 Mac 打开一次此工作区即可补上")
            return
        }
        if ("show_page" in cols) {
            // v10+：整套列都在
            db.exec(
                """
                INSERT INTO scratch_pad(id,document_id,title,anchor_page,anchor_x,anchor_y,bg,pattern,show_page,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET title=excluded.title, anchor_page=excluded.anchor_page,
                  anchor_x=excluded.anchor_x, anchor_y=excluded.anchor_y, bg=excluded.bg,
                  pattern=excluded.pattern, show_page=excluded.show_page, updated_at=excluded.updated_at
                """.trimIndent(),
                arrayOf(
                    p.id, p.documentId, p.title, p.anchorPage, p.anchorX, p.anchorY,
                    p.bg, p.pattern, if (p.showPage) 1 else 0, p.createdAt, p.updatedAt,
                ),
            )
        } else if ("pattern" in cols) {
            // v9 老库：没有 show_page 列，页面底图开关写不进去（读回来兜底 false，界面跟着退回去）
            db.exec(
                """
                INSERT INTO scratch_pad(id,document_id,title,anchor_page,anchor_x,anchor_y,bg,pattern,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET title=excluded.title, anchor_page=excluded.anchor_page,
                  anchor_x=excluded.anchor_x, anchor_y=excluded.anchor_y, bg=excluded.bg,
                  pattern=excluded.pattern, updated_at=excluded.updated_at
                """.trimIndent(),
                arrayOf(
                    p.id, p.documentId, p.title, p.anchorPage, p.anchorX, p.anchorY,
                    p.bg, p.pattern, p.createdAt, p.updatedAt,
                ),
            )
        } else {
            db.exec(
                """
                INSERT INTO scratch_pad(id,document_id,title,anchor_page,anchor_x,anchor_y,bg,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET title=excluded.title, anchor_page=excluded.anchor_page,
                  anchor_x=excluded.anchor_x, anchor_y=excluded.anchor_y, bg=excluded.bg,
                  updated_at=excluded.updated_at
                """.trimIndent(),
                arrayOf(
                    p.id, p.documentId, p.title, p.anchorPage, p.anchorX, p.anchorY,
                    p.bg, p.createdAt, p.updatedAt,
                ),
            )
        }
    }

    /**
     * 删除一张草稿纸，**连带删掉纸上全部笔迹**（`note` 里 kind=4 且 payload.padId 指向它的行）。
     * Mac 端靠内存对账删笔迹，安卓这边得显式删（handoff §2.4）——漏了第二步就是一堆无处可归的
     * 孤儿笔迹留在库里。两步一个事务（同擦除的理由，§9.3）。表不存在时纸行没得删，
     * 孤儿笔迹照清（防的就是「纸没了、笔迹还在」这一种状态）。
     */
    fun deleteScratchPad(id: String) {
        transaction {
            if (scratchPadCols != null) {
                db.exec("DELETE FROM scratch_pad WHERE id=?", arrayOf(id))
            }
            val orphans = db.query(
                "SELECT id,payload FROM note WHERE kind=?",
                arrayOf(NoteKind.SCRATCH_INK),
            ) { c -> c.str("id") to c.blob("payload") }
                .filter { (_, payload) -> InkPayload.parse(payload)?.padId == id }
            for ((noteId, _) in orphans) deleteNote(noteId)
        }
    }

    /**
     * 读出一篇文档的全部草稿纸笔迹（note kind=4），**按 payload.padId 分到各张纸**。
     * 与 [strokes]（kind=2 页内）互不串台——两条读取路径各按 kind 一刀切干净。
     * kind=4 但 payload 缺 padId 的是坏数据（无处可归的孤儿），跳过并记日志
     * （同 Mac `InkStroke(note:)` 的丢弃口径），绝不混进页内笔迹。
     */
    fun scratchStrokes(documentId: String): Map<String, List<Stroke>> {
        val out = LinkedHashMap<String, MutableList<Stroke>>()
        var bad = 0
        for (n in notes(documentId)) {
            if (n.kind != NoteKind.SCRATCH_INK) continue
            val p = InkPayload.parse(n.payload)
            val padId = p?.padId
            if (p == null || padId == null) { bad++; continue }
            out.getOrPut(padId) { ArrayList() }.add(p.toStroke(n.id, 0))
        }
        if (bad > 0) Log.w(TAG, "$documentId：$bad 条草稿纸笔迹坏掉/缺 padId 已跳过")
        return out
    }

    /**
     * 落一笔草稿纸笔迹（kind=4，page 固定 0——画布不属于任何一页，payload 带 padId）。
     * anchor 是**画布坐标**包围盒（可负，只作检索/调试用，没有页内语义），同 Mac `InkStroke.toNote`。
     * 草稿纸不分图层，`layerId` 照 Mac 落库时仍写默认层。
     */
    fun insertScratchStroke(
        documentId: String,
        padId: String,
        pen: Pen,
        pts: List<Pt3>,
        id: String = UUID.randomUUID().toString(),
    ): String? {
        if (pts.isEmpty()) return null   // 空笔画不落库（同 insertStroke 的 guard）
        val b = boundsOf(pts)
        val now = nowIso()
        upsertNote(
            LibNote(
                id = id, documentId = documentId, kind = NoteKind.SCRATCH_INK, page = 0,
                anchorX = b[0], anchorY = b[1], anchorW = b[2], anchorH = b[3],
                payload = InkPayload.of(pen, pts, LibInkLayer.DEFAULT_ID, padId).bytes(),
                createdAt = now, updatedAt = now,
            ),
        )
        return id
    }

    /**
     * 草稿纸擦除的对账落库：[reconcileStrokes] 的 kind=4 变体，只比对 [padId] 这张纸上的笔迹。
     * 两处有意的不同：
     * - **没有隐藏图层过滤**——草稿纸不分图层（handoff §2.3 顺带提醒），那套过滤在这里无对象；
     * - 多出来的段走 [insertScratchStroke]（kind=4 + 带 padId），不是页内的 insertStroke。
     * 「头一段沿用原 id」的理由与 [reconcileStrokes] 完全相同（回推在途时的二次擦除要对得上），
     * 整批一个事务（§9.3）。调用方在队列线程上跑。
     */
    fun reconcileScratchStrokes(documentId: String, padId: String, local: Map<String, List<Stroke>>): InkDiff {
        var deleted = 0
        var updated = 0
        var inserted = 0
        transaction {
            for (old in scratchStrokes(documentId)[padId].orEmpty()) {
                val segs = local[old.id]
                if (segs == null) {
                    deleteNote(old.id)
                    deleted++
                    continue
                }
                if (segs.size == 1 && segs[0].pts.size == old.pts.size) continue
                updateStrokePoints(old.id, segs[0].pts)
                updated++
                for (i in 1 until segs.size) {
                    insertScratchStroke(documentId, padId, segs[i].pen, segs[i].pts)
                    inserted++
                }
            }
        }
        return InkDiff(deleted, updated, inserted)
    }

    // ---------- ink_layer ----------

    fun inkLayers(documentId: String): List<LibInkLayer> = db.query(
        "SELECT * FROM ink_layer WHERE document_id=? ORDER BY sort_order ASC",
        arrayOf(documentId),
    ) { inkLayer(it) }

    /** 图层表 → 中立模型（供面板与线格式共用同一形状）。转换本身见 [toUiLayers] */
    fun layersForUi(documentId: String): List<Layer> = inkLayers(documentId).toUiLayers()

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

    private fun scratchPad(c: android.database.Cursor) = ScratchPad(
        id = c.str("id"), documentId = c.str("document_id"), title = c.str("title"),
        anchorPage = c.int("anchor_page"), anchorX = c.dbl("anchor_x"), anchorY = c.dbl("anchor_y"),
        bg = c.str("bg").ifEmpty { ScratchPad.DEFAULT_BG },
        // v8 的库没有 pattern 列：cursor 按名取列取不到 → 空串 → 兜底 dots（同 Mac `?? "dots"`）；
        // 未知取值（未来的 Mac 加了新底纹）同样回落 dots，同 Mac `?? .dots`。
        pattern = ScratchPad.patternOrDefault(c.str("pattern")),
        // v9 的库没有 show_page 列：取不到 → false（与 Mac 的迁移口径一致：老纸一律不垫页）
        showPage = c.bool("show_page", false),
        createdAt = c.str("created_at"), updatedAt = c.str("updated_at"),
    )
}
