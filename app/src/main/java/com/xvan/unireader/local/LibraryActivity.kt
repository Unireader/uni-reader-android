package com.xvan.unireader.local

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xvan.unireader.R
import com.xvan.unireader.local.store.LibDocument
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.NoteKind
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.onSystemBarInsets
import com.xvan.unireader.shared.runInBackground
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
    private class Row(val doc: LibDocument, val ink: Int, val pdfOk: Boolean)
    private class Snapshot(val name: String, val info: String, val rows: List<Row>)

    private lateinit var list: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var header: TextView
    private var workspace: File? = null

    /** 只认最后一次 reload 的结果：`onResume` 可能在前一次读盘还没回来时又触发一次 */
    private var loadToken: Any? = null

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
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = Ui.title(this, "", 26f)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
            addView(titleView)
            addView(header)
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

    override fun onResume() {
        super.onResume()
        reload()
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
            Snapshot(
                name = store.workspaceName().ifEmpty { ws.name },
                info = "${docs.size} 个文档",
                rows = docs.map { d ->
                    Row(
                        doc = d,
                        ink = store.noteCount(d.id, NoteKind.INK),
                        pdfOk = Workspace.firstOpenablePdf(ws, store, d.id) != null,
                    )
                },
            )
        }

    private fun render(ws: File, snap: Snapshot) {
        list.removeAllViews()
        title = ws.name
        titleView.text = snap.name
        header.text = snap.info
        for ((i, r) in snap.rows.withIndex()) {
            if (i > 0) list.addView(Ui.divider(this))
            list.addView(row(r.doc, r.ink, r.pdfOk))
        }
        if (snap.rows.isEmpty()) {
            list.addView(
                Ui.card(this).apply {
                    addView(
                        Ui.body(
                            this@LibraryActivity,
                            "这个工作区还没有文档。先在 Mac 上导入 PDF 并「拷进工作区」，再把整个 .unrd 搬过来。",
                        ),
                    )
                },
            )
        }
    }

    /**
     * 一条书。整行可点（涟漪铺满行），不是行里塞个按钮——列表项的点按目标就该是整行。
     * 进度做成一条细进度条：数字要读，条一眼就看得到读到哪儿了。
     */
    private fun row(d: LibDocument, inkCount: Int, pdfOk: Boolean): View {
        val col = Ui.row(this, if (pdfOk) ({ workspace?.let { ReaderActivity.start(this, it, d.id) } }) else null)
        col.addView(
            Ui.title(this, d.title, 17f).apply {
                if (!pdfOk) setTextColor(Ui.onVariant(this@LibraryActivity))
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
                },
            ).apply { setPadding(0, dp(4), 0, dp(8)) },
        )
        if (started) col.addView(progressBar(ratio))
        if (!pdfOk) {
            col.addView(
                Ui.body(this, "PDF 路径失效——文件不在了，或存的是 Mac 本机绝对路径，需要在 Mac 上重新关联")
                    .apply { setTextColor(Ui.col(this@LibraryActivity, R.color.danger)); textSize = 12f },
            )
        }
        return col
    }

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
