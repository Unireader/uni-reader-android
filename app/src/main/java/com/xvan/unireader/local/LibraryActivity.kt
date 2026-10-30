package com.xvan.unireader.local

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xvan.unireader.R
import android.app.AlertDialog
import com.xvan.unireader.local.exercise.ExGroupNode
import com.xvan.unireader.local.exercise.ExScope
import com.xvan.unireader.local.store.BoardNote
import com.xvan.unireader.local.store.Iso
import com.xvan.unireader.local.store.ScratchPad
import com.xvan.unireader.local.store.newBoardId
import com.xvan.unireader.shared.PadPanels
import com.xvan.unireader.local.store.LibDocument
import com.xvan.unireader.local.mirror.MirrorStore
import com.xvan.unireader.local.mirror.MirrorUi
import com.xvan.unireader.local.server.ServerAccounts
import com.xvan.unireader.local.server.ServerSync
import com.xvan.unireader.local.server.ServerSyncIO
import com.xvan.unireader.local.server.ServerUi
import com.xvan.unireader.local.server.SyncClient
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.NoteKind
import com.xvan.unireader.local.store.StoreQueue
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.onSystemBarInsets
import com.xvan.unireader.shared.runInBackground
import com.xvan.unireader.shared.showAlert
import java.io.File

/**
 * 工作区的文档列表（模式1）：标题、页数、阅读进度、笔迹条数、上次打开。
 *
 * 每次 `onResume` 重读一遍库——从阅读界面退回来时进度已经变了，缓存一份反而要自己同步。
 * 列表规模是「一个人的书架」，几十条，重读的开销可以忽略。
 *
 * 但**读库本身不能在主线程**（§9.5）：`LibraryStore.open` 在慢卷上是秒级，每个文档还要一次
 * `firstOpenablePdf`（逐条 location 做 `isFile`）。所以读盘在后台整批做完出一个 [Snapshot]，
 * 主线程只建 View。
 */
class LibraryActivity : Activity() {

    companion object {
        const val TAG = "UniReader/Library"
        const val EXTRA_WORKSPACE = "workspace"

        fun start(ctx: Context, workspaceDir: File) {
            ctx.startActivity(
                Intent(ctx, LibraryActivity::class.java)
                    .putExtra(EXTRA_WORKSPACE, workspaceDir.absolutePath),
            )
        }
    }

    /** 一行要显示的全部东西，全在后台备好（主线程不再碰库） */
    private class Row(val doc: LibDocument, val ink: Int, val pdfOk: Boolean, val opened: Boolean)

    /** 画板笔记一行（`../BOARD-NOTE-PLAN.md §5`）：[items] = 上面有几条东西（笔迹 + 图片） */
    private class BoardRow(val board: BoardNote, val items: Int, val opened: Boolean)

    /** 「习题」一组的一行（只列第一级：全部 / 错题本 / 收藏 / 每本有题的书；章节在阅读界面抽屉里选） */
    private class ExRow(val key: String, val title: String, val count: Int, val opened: Boolean)
    private class Snapshot(
        val name: String,
        val info: String,
        val rows: List<Row>,
        /** 这个工作区是不是离线副本（换角标、换「打不开」的文案、换右上那颗键的动作） */
        val isMirror: Boolean,
        val boards: List<BoardRow>,
        /** 工作区 Markdown 笔记（`Notes/` 里的相对路径 → 是否已在标签页里），按路径排 */
        val notes: List<Pair<String, Boolean>> = emptyList(),
        /** 服务器工作区连的那台（`meta.srv_url` / `srv_id`，`../SERVER-MODE-PLAN.md`）；本地工作区 = null */
        val serverUrl: String? = null,
        val serverId: String? = null,
        /** 习题（`../EXERCISE-PLAN.md`）：没有题 = 空，整组不显示（同 Mac 侧栏「有题才出现」） */
        val exercises: List<ExRow> = emptyList(),
    )

    private lateinit var list: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var header: TextView
    /** 服务器工作区：哪台服务器 + 同步状态（本地工作区不显示） */
    private lateinit var syncLine: TextView
    private var serverUrl: String? = null
    private var serverId: String? = null
    /** 同步状态变了：刷表头；一轮做完（或后台下好了 PDF）再重读列表，拉下来的改动要显示出来（攒 0.3 秒，别连着读） */
    private val syncListener: () -> Unit = {
        renderSyncLine()
        val ws = workspace
        if (serverUrl != null && ws != null && ServerSync.status(ws) !is ServerSync.Status.Syncing) {
            list.removeCallbacks(reloadSoon)
            list.postDelayed(reloadSoon, 300)
        }
    }
    private val reloadSoon = Runnable { reload() }
    private var workspace: File? = null

    /** 只认最后一次 reload 的结果：`onResume` 可能在前一次读盘还没回来时又触发一次 */
    private var loadToken: Any? = null

    /** 当前工作区是不是离线副本（`read()` 里取，决定右上那颗键给哪条动作） */
    private var isMirror = false

    /**
     * 「添加 PDF」期间那条**可写**连接（用完即关，见 [startAdd]）。列书单用的是另一条只读连接，
     * 两者不共用：只读那条连 `wal_checkpoint` 都做不了，写不了库。
     */
    private var addQueue: StoreQueue? = null

    /** 这一轮添加里每个文件的状态（路径 → 尾标）：加过的行标出来，别让人重复点同一本 */
    private val addNotes = HashMap<String, String>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_WORKSPACE)
        if (path == null) {
            Log.e(TAG, "缺 workspace 参数")
            finish()
            return
        }
        workspace = File(path)
        header = Ui.body(this, "").apply { setPadding(0, 0, 0, dp(4)) }
        syncLine = Ui.body(this, "").apply {
            textSize = 13f
            visibility = View.GONE
            setOnClickListener { workspace?.let { ServerSync.syncNow(this@LibraryActivity, it) } }
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = Ui.title(this, "", 26f)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
            addView(headerRow())
            addView(Ui.spacer(this@LibraryActivity, 12))
            addView(list)
        }
        val scroll = ScrollView(this).apply {
            addView(col)
            setBackgroundColor(Ui.surface(this@LibraryActivity))
        }
        setContentView(scroll)
        scroll.onSystemBarInsets { top, bottom -> scroll.setPadding(0, top, 0, bottom) }
    }

    /** 标题 + 一行统计占左边，右边一颗「添加 PDF」——整屏只有这一个新增入口 */
    private fun headerRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(header)
                addView(syncLine)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(Ui.iconButton(this@LibraryActivity, R.drawable.ic_plus, "添加 PDF") { startAdd() })
        addView(Ui.iconButton(this@LibraryActivity, R.drawable.ic_more, "更多") { showMore() })
    }

    override fun onResume() {
        super.onResume()
        ServerSync.addListener(syncListener)
        reload()
    }

    override fun onPause() {
        ServerSync.removeListener(syncListener)
        super.onPause()
    }

    private fun renderSyncLine() {
        val ws = workspace
        val url = serverUrl
        if (ws == null || url == null) {
            syncLine.visibility = View.GONE
            return
        }
        val label = ServerAccounts.find(this, serverId, url)?.label ?: url.substringAfter("://")
        syncLine.text = ServerUi.statusLine(this, label, ServerSync.status(ws))
        syncLine.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        closeAddQueue()
        // 退出书库 = 用完这个工作区：服务器工作区同步一轮（阅读界面还开着它的话只推一次，见 ServerSync.syncNow）
        if (isFinishing && serverUrl != null) workspace?.let { ServerSync.syncNow(applicationContext, it) }
        super.onDestroy()
    }

    private fun reload() {
        val ws = workspace ?: return
        val token = Any()
        loadToken = token
        // 首次才显示「正在读取」：从阅读界面退回来时保留旧列表，读完再整体换掉——
        // 先清空的话每次返回都会闪一下白屏（慢卷上闪好几秒）
        if (list.childCount == 0) header.text = "正在读取工作区…"
        runInBackground(
            what = "读库 ${ws.name}",
            work = { read(ws) },
            ok = { snap ->
                if (loadToken === token) render(ws, snap)
            },
            fail = { e ->
                if (loadToken === token) {
                    Log.e(TAG, "读库失败", e)
                    list.removeAllViews()
                    header.text = "打不开工作区：${e.message}"
                }
            },
        )
    }

    /** 后台线程：一次把库读干净就关掉连接，`store` 不外泄给主线程（它非线程安全） */
    private fun read(ws: File): Snapshot =
        LibraryStore.open(ws, readOnly = true).use { store ->
            val docs = store.allDocuments()
            // 这个工作区的标签页组（§13）：阅读界面开着哪几篇，列表上标出来——点已开着的那篇
            // 是切过去而不是重新打开，先说清楚免得以为点错了
            val opened = TabSet.openDocIds(this, ws.absolutePath)
            val mirror = MirrorUi.isMirror(store)
            val srvUrl = store.meta(SyncClient.META_SERVER_URL)?.takeIf { it.isNotEmpty() }
            // 借出记录只在源盘那边有；一行足矣（「是信息不是锁」，方案 §5.2）
            val lent = MirrorStore.decodeCheckouts(store.meta(MirrorStore.META_CHECKOUTS)).size
            // 画板笔记（只读连接：没有这两张表就是「还没有画板」，补建留给可写打开）
            val counts = store.boardItemCounts()
            val boards = store.boards().map { b ->
                BoardRow(b, counts[b.id] ?: 0, TabSet.boardKey(b.id) in opened)
            }
            Snapshot(
                name = store.workspaceName().ifEmpty { ws.name },
                isMirror = mirror,
                serverUrl = srvUrl,
                serverId = store.meta(SyncClient.META_SERVER_ID)?.takeIf { it.isNotEmpty() },
                boards = boards,
                // 只扫目录不读正文（同 Mac 侧栏）；md_doc 对账留给阅读界面那条可写连接
                notes = MdNotes.scan(ws).notes.map { it.relPath to (TabSet.mdKey(it.relPath) in opened) },
                exercises = readExercises(store, docs, opened),
                info = buildString {
                    append("${docs.size} 个文档")
                    if (opened.isNotEmpty()) append("　${opened.size} 个在标签页里")
                    if (mirror) append("　离线副本")
                    if (lent > 0) append("　借出 $lent 份")
                },
                rows = docs.map { d ->
                    Row(
                        doc = d,
                        ink = store.noteCount(d.id, NoteKind.INK),
                        pdfOk = Workspace.firstOpenablePdf(ws, store, d.id) != null,
                        opened = d.id in opened,
                    )
                },
            )
        }

    /**
     * 习题分组的第一级（哪几行、什么顺序只在 [ExGroupNode.build] 一份，同抽屉 / Mac 侧栏）。
     * 某组已经在标签页里（公用的或单独开的都算）就标出来。
     */
    private fun readExercises(store: LibraryStore, docs: List<LibDocument>, opened: Set<String>): List<ExRow> {
        val openKeys = opened.filter(TabSet::isExercise).map(TabSet::exerciseKeyOf).toSet()
        val title: (ExScope) -> String = { sc ->
            when (sc) {
                ExScope.Wrong -> getString(R.string.ex_mistakes)
                ExScope.Starred -> getString(R.string.ex_starred)
                else -> getString(R.string.ex_all)
            }
        }
        return ExGroupNode.build(store.exerciseSummary(), docs.map { it.id to it.title }, title, getString(R.string.ex_no_chapter))
            .map { ExRow(it.scope.key, it.title, it.count, it.scope.key in openKeys) }
    }

    private fun render(ws: File, snap: Snapshot) {
        isMirror = snap.isMirror
        serverUrl = snap.serverUrl
        serverId = snap.serverId
        renderSyncLine()
        list.removeAllViews()
        title = ws.name
        titleView.text = snap.name
        header.text = snap.info
        for ((i, r) in snap.rows.withIndex()) {
            if (i > 0) list.addView(Ui.divider(this))
            list.addView(row(r.doc, r.ink, r.pdfOk, r.opened, snap.isMirror, snap.serverUrl != null))
        }
        if (snap.rows.isEmpty()) {
            list.addView(
                Ui.card(this).apply {
                    addView(Ui.title(this@LibraryActivity, "还没有文档", 17f))
                    addView(
                        Ui.body(this@LibraryActivity, "从平板上挑几个 PDF 加进来。")
                            .apply { setPadding(0, dp(6), 0, dp(14)) },
                    )
                    addView(
                        Ui.button(this@LibraryActivity, "添加 PDF", filled = true) { startAdd() },
                    )
                    addView(
                        Ui.tip(this@LibraryActivity, "也可以在 Mac 上导入，再把整个 .unrd 搬过来")
                            .apply { setPadding(0, dp(8), 0, 0) },
                    )
                },
            )
        }
        renderBoards(ws, snap.boards)
        renderNotes(ws, snap.notes)
        renderExercises(ws, snap.exercises)
    }

    /** 「习题」一组：点一行 = 在阅读界面的习题标签里打开这一组（公用的习题标签就地换组，见 `ReaderActivity`） */
    private fun renderExercises(ws: File, rows: List<ExRow>) {
        if (rows.isEmpty()) return
        list.addView(Ui.groupTitle(this, getString(R.string.ex_title), top = 24))
        for ((i, r) in rows.withIndex()) {
            if (i > 0) list.addView(Ui.divider(this))
            val col = Ui.row(this) { ReaderActivity.start(this, ws, TabSet.exerciseTab(r.key, separate = false)) }
            col.addView(
                Ui.title(this, r.title, 17f).apply { if (r.opened) setTextColor(Ui.accent(this@LibraryActivity)) },
            )
            val sub = buildString {
                append(getString(R.string.ex_count, r.count))
                if (r.opened) append("　已在标签页里")
            }
            col.addView(Ui.body(this, sub).apply { setPadding(0, dp(4), 0, 0) })
            list.addView(col)
        }
    }

    /** 「笔记」一组（`../MARKDOWN-NOTES-PLAN.md`）：每篇一行，次行是所在目录；点开 = 在阅读界面的标签页里打开 */
    private fun renderNotes(ws: File, notes: List<Pair<String, Boolean>>) {
        if (notes.isEmpty()) return
        list.addView(Ui.groupTitle(this, getString(R.string.md_group), top = 24))
        for ((i, n) in notes.withIndex()) {
            val (rel, opened) = n
            if (i > 0) list.addView(Ui.divider(this))
            val item = MdNotes.Item(rel)
            val col = Ui.row(this) { ReaderActivity.start(this, ws, TabSet.mdKey(rel)) }
            col.addView(
                Ui.title(this, item.title, 17f).apply { if (opened) setTextColor(Ui.accent(this@LibraryActivity)) },
            )
            val sub = buildString {
                append(item.folder.ifEmpty { MdNotes.DIR })
                if (opened) append("　已在标签页里")
            }
            col.addView(Ui.body(this, sub).apply { setPadding(0, dp(4), 0, 0) })
            list.addView(col)
        }
    }

    // ---------- 画板笔记（`../BOARD-NOTE-PLAN.md §5`） ----------

    /** 书库页的「画板笔记」一组：每篇一行（点开 = 在阅读界面的标签页里打开）+「新建」 */
    private fun renderBoards(ws: File, boards: List<BoardRow>) {
        list.addView(Ui.groupTitle(this, getString(R.string.board_group), top = 24))
        for ((i, r) in boards.withIndex()) {
            if (i > 0) list.addView(Ui.divider(this))
            list.addView(boardRow(ws, r))
        }
        list.addView(
            PadPanels.iconRow(this, R.drawable.ic_plus, getString(R.string.board_new)) { createBoard(ws) },
        )
    }

    private fun boardName(b: BoardNote) = b.title.ifEmpty { getString(R.string.board_untitled) }

    private fun boardRow(ws: File, r: BoardRow): View {
        val col = Ui.row(this) { ReaderActivity.start(this, ws, TabSet.boardKey(r.board.id)) }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            Ui.title(this, boardName(r.board), 17f).apply {
                if (r.opened) setTextColor(Ui.accent(this@LibraryActivity))
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        head.addView(
            Ui.iconButton(this, R.drawable.ic_more, getString(R.string.board_more), Ui.onVariant(this)) {
                showBoardMenu(ws, r.board)
            },
        )
        col.addView(head)
        col.addView(
            Ui.body(
                this,
                buildString {
                    append(getString(R.string.board_items, r.items))
                    if (r.opened) append("　已在标签页里")
                },
            ).apply { textSize = 12f },
        )
        return col
    }

    /** 改名 / 删除（与阅读界面抽屉里那份同一套文案） */
    private fun showBoardMenu(ws: File, b: BoardNote) {
        var dlg: AlertDialog? = null
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            PadPanels.iconRow(this, R.drawable.ic_text, getString(R.string.board_rename)) {
                dlg?.dismiss()
                val edit = PadPanels.inputBox(this, getString(R.string.board_name_hint)).apply {
                    setText(b.title)
                    setSelection(text.length)
                }
                Sheet(this).title(getString(R.string.board_rename_title))
                    .content(edit)
                    .action(getString(R.string.common_cancel))
                    .action(getString(R.string.common_save), primary = true) {
                        val t = edit.text.toString().trim()
                        if (t != b.title) {
                            writeBoard(ws, "画板改名", body = { s ->
                                s.upsertBoard(b.copy(title = t, updatedAt = Iso.now()))
                            })
                        }
                    }
                    .show()
            },
        )
        col.addView(
            PadPanels.iconRow(this, R.drawable.ic_delete, getString(R.string.board_delete), Ui.col(this, R.color.danger)) {
                dlg?.dismiss()
                Sheet(this)
                    .title(getString(R.string.board_delete_confirm, boardName(b)))
                    .subtitle(getString(R.string.board_delete_msg))
                    .action(getString(R.string.common_cancel))
                    .action(getString(R.string.common_delete), primary = true) {
                        // 删 board_note 行，board_item 随外键 CASCADE 一起走（foreign_keys=ON 在 Db.open 里开着）
                        // 先归档进回收站、成了才删（红线 11）；归档失败会抛出 → writeBoard 弹「写不进去」，什么都没删
                        writeBoard(ws, "删除画板", body = { s -> Trash.deleteBoard(s, ws, b, boardName(b)) })
                    }
                    .show()
            },
        )
        dlg = Sheet(this).title(boardName(b)).content(col).action(getString(R.string.common_cancel)).show()
    }

    /** 新建一篇空画板，建好直接在阅读界面里打开 */
    /** 新建画板：先选模式（无限画布 / 分页 + 页面大小、背景、页数，`../BOARD-NOTE-PLAN.md §9.4`），再写库 */
    private fun createBoard(ws: File) {
        com.xvan.unireader.shared.NewBoardSheet.show(this) { spec -> createBoard(ws, spec) }
    }

    private fun createBoard(ws: File, spec: com.xvan.unireader.shared.NewBoardSheet.Spec) {
        val now = Iso.now()
        val b = BoardNote(
            id = newBoardId(), title = "", bg = ScratchPad.DEFAULT_BG, pattern = ScratchPad.DEFAULT_PATTERN,
            groupName = "", createdAt = now, updatedAt = now, lastOpenedAt = now,
        )
        writeBoard(
            ws, "新建画板",
            body = { s ->
                BoardController.createBoard(s, b, spec)
                // 表补不上（卷只读）时 upsert 已经抛了；这里再核一次，别打开一篇不存在的画板
                check(s.board(b.id) != null) { getString(R.string.board_create_failed_msg) }
            },
            failTitle = R.string.board_create_failed,
            then = { ReaderActivity.start(this, ws, TabSet.boardKey(b.id)) },
        )
    }

    /**
     * 画板的写：开一条**可写**连接（打开时顺带补建两张表，见 `LibraryStore.open`）→ 写 → 关，全在后台。
     * 列书单那条是只读连接写不了；与「添加 PDF」那条同一个道理，只是这里一次一写、用完即关。
     * 写成功才调 [then]；失败弹一句（原因在正文）。
     */
    private fun writeBoard(
        ws: File,
        what: String,
        body: (LibraryStore) -> Unit,
        failTitle: Int = R.string.board_write_failed,
        then: (() -> Unit)? = null,
    ) {
        runInBackground(
            what = what,
            work = { LibraryStore.open(ws, readOnly = false).use { body(it) } },
            ok = {
                then?.invoke()
                reload()
            },
            fail = {
                alert(getString(failTitle), it.message ?: getString(R.string.board_create_failed_msg))
                reload()
            },
        )
    }

    /**
     * 一条书。整行可点（涟漪铺满行），不是行里塞个按钮——列表项的点按目标就该是整行。
     * 进度做成一条细进度条：数字要读，条一眼就看得到读到哪儿了。
     */
    private fun row(d: LibDocument, inkCount: Int, pdfOk: Boolean, opened: Boolean, isMirror: Boolean, onServer: Boolean): View {
        val onClick: (() -> Unit)? = when {
            pdfOk -> ({ workspace?.let { ReaderActivity.start(this, it, d.id) } })
            // 服务器工作区里还没下载的书：点一下从服务器下载，下完直接打开（方案 §4.1：大 PDF 用到再下）
            onServer -> ({
                workspace?.let { ws ->
                    ServerUi.downloadPdf(this, ws, d.id, d.title) { ok ->
                        reload()
                        if (ok) ReaderActivity.start(this, ws, d.id)
                    }
                }
            })
            else -> null
        }
        val col = Ui.row(this, onClick)
        col.setOnLongClickListener { workspace?.let { showDocMenu(it, d) }; true }
        col.addView(
            Ui.title(this, d.title, 17f).apply {
                if (!pdfOk) setTextColor(Ui.onVariant(this@LibraryActivity))
                else if (opened) setTextColor(Ui.accent(this@LibraryActivity))
            },
        )
        val ratio = if (d.pageCount > 0) ((d.readPage + d.readFrac) / d.pageCount).toFloat() else 0f
        val started = d.readPage > 0 || d.readFrac > 0
        col.addView(
            Ui.body(
                this,
                buildString {
                    append("${d.pageCount} 页")
                    append(if (started) "　读到第 ${d.readPage + 1} 页（${(ratio * 100).toInt()}%）" else "　未开始")
                    if (inkCount > 0) append("　笔迹 $inkCount 条")
                    if (opened) append("　已在标签页里")
                },
            ).apply { setPadding(0, dp(4), 0, dp(8)) },
        )
        if (started) col.addView(progressBar(ratio))
        if (!pdfOk && onServer) {
            // 服务器工作区本来就只自动下小 PDF，没下的不是出错
            col.addView(Ui.body(this, getString(R.string.srv_pdf_not_downloaded)).apply { textSize = 12f })
        } else if (!pdfOk) {
            col.addView(
                // 镜像里「没带 PDF」是刻意的（库全量、PDF 选择性，方案 §7），不是出错——
                // 说成「重新关联」会把用户引到一条根本不该走的路上
                Ui.body(this, if (isMirror) "没有离线——插回源盘才能看" else "找不到 PDF 文件——重新添加一次，或回 Mac 上重新关联")
                    .apply { setTextColor(Ui.col(this@LibraryActivity, R.color.danger)); textSize = 12f },
            )
        }
        return col
    }

    // ---------- 添加 PDF ----------

    /**
     * 「添加 PDF」：开一条**可写**连接 → 浏览本机文件挑 PDF → 每挑一本就拷进 `PDFs/` 并入库
     * （[PdfImport]）→ 关窗时收掉连接、重读列表。
     *
     * **窗不关就一直开着那条连接**：一次多半要加好几本，每本重开一次库等于每本都付一遍
     * 慢卷上的开库 + `wal_checkpoint`（秒级）。作业全排在 [StoreQueue] 的独占线程上，
     * 串行、有序，主线程一行 I/O 都不做（§9.5）。
     *
     * 阅读界面（[ReaderActivity]）可能同时开着同一个工作区的另一条连接——那是**同一个进程内的
     * 两条 SQLite 连接**，WAL + `busy_timeout=3000` 管得住；跨端的单写者约束（§9.2）说的是
     * 两台设备，不是这个。
     */
    private fun startAdd() {
        val ws = workspace ?: return
        if (addQueue != null) return
        runInBackground(
            what = "开可写库 ${ws.name}",
            work = { LibraryStore.open(ws, readOnly = false) },
            ok = { store ->
                addQueue = StoreQueue(store)
                addNotes.clear()
                browseForPdf(ws)
            },
            fail = { alert("加不了 PDF", "打不开工作区的库：${it.message}") },
            // 界面已经关了：开出来的库必须收掉，否则 WAL 一直挂着（§9.2）
            discard = { runCatching { it.close() } },
        )
    }

    private fun browseForPdf(ws: File) {
        var browser: FileBrowser? = null
        browser = FileBrowser(
            this,
            FileBrowser.Mode.PDF,
            title = "添加 PDF",
            tipText = "点一个就加一本，可以接着加下一本；文件会拷进工作区",
            fileNote = { f -> addNotes[f.absolutePath] },
            onDismiss = {
                closeAddQueue()
                reload()      // 加进来的书要出现在列表里
            },
        ) { f -> ingest(ws, f) { browser?.refresh() } }
        browser.show()
    }

    private fun ingest(ws: File, f: File, onChanged: () -> Unit) {
        val q = addQueue ?: return
        val key = f.absolutePath
        addNotes[key] = "添加中…"
        onChanged()
        q.submit(
            "导入 ${f.name}",
            { store -> runCatching { PdfImport.ingest(this, ws, store, f) } },
        ) { r ->
            r.fold(
                onSuccess = { res ->
                    addNotes[key] = when (res) {
                        is PdfImport.Result.Added -> "已添加"
                        is PdfImport.Result.Duplicate -> "已在库中"
                    }
                },
                onFailure = { e ->
                    // 失败的行把标记去掉，让用户能原地再点一次（换个文件、或等 U 盘缓过来）
                    addNotes.remove(key)
                    Log.e(TAG, "导入 ${f.name} 失败", e)
                    alert("加不进来：${f.name}", e.message ?: "未知错误")
                },
            )
            onChanged()
        }
    }

    /**
     * 收掉那条可写连接。[StoreQueue.close] 自己就把关库排在队尾（还没跑完的导入会先落盘，
     * `wal_checkpoint(TRUNCATE)` 在队列线程上做），所以这里直接调用即可，不用再往后台甩一层。
     */
    private fun closeAddQueue() {
        addQueue?.close()
        addQueue = null
    }

    /**
     * 右上「更多」：镜像与源盘**互斥**地给一条动作——镜像不能再做镜像（[MirrorBuilder] 也会拦），
     * 源盘也没有「同步回去」这回事。只出现该出现的那条，不给用户做无效选择的机会。
     */
    private fun showMore() {
        val ws = workspace ?: return
        val name = titleView.text.toString().ifEmpty { ws.name }
        // 动作做成一列行（原先排在弹层底部一行按钮里；加了「上传到服务器」之后一行放不下）
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dlg: AlertDialog? = null
        fun item(icon: Int, label: String, detail: String? = null, act: () -> Unit) {
            val onClick = { dlg?.dismiss(); act() }
            list.addView(
                if (detail == null) PadPanels.iconRow(this, icon, label, Ui.accent(this), onClick = onClick)
                else PadPanels.twoLineRow(this, icon, Ui.accent(this), label, detail, onClick = onClick),
            )
        }
        val sheet = Sheet(this)
        when {
            // 服务器工作区：本机缓存本身就是离线副本，不再给「做成离线副本」（`../SERVER-MODE-PLAN.md §1`）
            serverUrl != null -> {
                sheet.title(getString(R.string.srv_workspace_title)).subtitle(syncLine.text.toString())
                item(R.drawable.ic_redo, getString(R.string.srv_sync_now)) { ServerSync.syncNow(this, ws) }
                // 只给就地变成缓存的原件（不在 App 缓存目录里的），同 Mac：缓存目录里的变回本地也还藏在那儿
                if (!ServerSyncIO.isInCachesFolder(ws)) {
                    item(R.drawable.ic_folder, getString(R.string.srv_detach)) {
                        ServerUi.confirmDetach(this, ws, name) { reload() }
                    }
                }
            }
            // 离线副本不能再做副本，也不能上传（传原来那份）
            isMirror -> {
                sheet.title("离线副本")
                item(R.drawable.ic_eye, "同步预览…", "插上源盘就能预览同步会做什么") { MirrorUi.syncPreview(this, ws) }
            }
            else -> {
                sheet.title("工作区")
                item(R.drawable.ic_tablet, "做成离线副本…", "复制一份到内部存储，拔了盘也能看和写") {
                    MirrorUi.makeMirror(this, ws) { reload() }
                }
                item(R.drawable.ic_monitor, getString(R.string.srv_upload)) { ServerUi.upload(this, ws, name) { reload() } }
            }
        }
        item(R.drawable.ic_delete, getString(R.string.trash_open)) { showTrash(ws) }
        dlg = sheet.content(list).action("取消").show()
    }

    /** 回收站：每个操作临时开一条可写连接（书库页平时只开只读的） */
    private fun showTrash(ws: File) {
        TrashSheet(this, ws, { what, work, then ->
            runInBackground(
                what = what,
                work = { LibraryStore.open(ws, readOnly = false).use { work(it) } },
                ok = { then(it) },
                fail = { e -> Log.e(TAG, "$what 失败", e); then(null) },
            )
        }) { reload() }.show()
    }

    /** 长按一本书：删除（进回收站）。确认框里说清有多少条会一起进去 */
    private fun showDocMenu(ws: File, d: LibDocument) {
        Sheet(this).title(d.title)
            .action(getString(R.string.act_cancel))
            .action(getString(R.string.doc_delete), primary = true) {
                // 确认框里的数 = 这篇全部笔记行（笔迹 / 文字笔记 / 高亮 / 书签 / 图片…），不只是笔迹
                runInBackground(
                    what = "数这篇的笔记",
                    work = { LibraryStore.open(ws, readOnly = true).use { it.notes(d.id).size } },
                    ok = { n -> confirmDeleteDoc(ws, d, n) },
                    fail = { confirmDeleteDoc(ws, d, 0) },
                )
            }
            .show()
    }

    private fun confirmDeleteDoc(ws: File, d: LibDocument, total: Int) {
                Sheet(this).title(getString(R.string.doc_delete_confirm, d.title))
                    .subtitle(getString(R.string.doc_delete_msg, total))
                    .action(getString(R.string.act_cancel))
                    .action(getString(R.string.act_delete), primary = true) {
                        runInBackground(
                            what = "删除文档《${d.title}》",
                            work = { LibraryStore.open(ws, readOnly = false).use { Trash.deleteDocument(it, ws, d) } },
                            ok = { reload() },
                            fail = { e ->
                                Log.e(TAG, "文档归档失败，没删", e)
                                alert(getString(R.string.trash_title), getString(R.string.trash_failed))
                                reload()
                            },
                        )
                    }
                    .show()
    }

    private fun alert(title: String, msg: String) = showAlert(title, msg)

    /** 3dp 高的读进度条：底槽 outline、进度 accent，纯色无渐变 */
    private fun progressBar(ratio: Float): View {
        val bar = View(this).apply { setBackgroundColor(Ui.accent(this@LibraryActivity)) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.round(Ui.outline(this@LibraryActivity), 2, this@LibraryActivity)
            layoutParams = LinearLayout.LayoutParams(-1, dp(3))
            addView(bar, LinearLayout.LayoutParams(0, -1, ratio.coerceIn(0.02f, 1f)))
            addView(View(this@LibraryActivity), LinearLayout.LayoutParams(0, -1, 1f - ratio.coerceIn(0.02f, 1f)))
        }
    }
}
