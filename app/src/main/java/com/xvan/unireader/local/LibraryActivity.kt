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
import com.xvan.unireader.local.store.LibDocument
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.NoteKind
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
    private class Snapshot(val info: String, val rows: List<Row>)

    private lateinit var list: LinearLayout
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
        header = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF6B6B6B.toInt())
            setPadding(0, 0, 0, dp(12))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(header)
            addView(list)
        }
        val scroll = ScrollView(this).apply { addView(col) }
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
                info = "${store.workspaceName().ifEmpty { ws.name }}　${docs.size} 个文档",
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
        header.text = snap.info
        for (r in snap.rows) {
            list.addView(row(r.doc, r.ink, r.pdfOk))
            list.addView(divider())
        }
        if (snap.rows.isEmpty()) {
            list.addView(
                TextView(this).apply {
                    text = "这个工作区还没有文档。先在 Mac 上导入 PDF 并「拷进工作区」，再把整个 .unrd 搬过来。"
                    textSize = 14f
                    setTextColor(0xFF6B6B6B.toInt())
                },
            )
        }
    }

    private fun row(d: LibDocument, inkCount: Int, pdfOk: Boolean): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = pdfOk
            if (pdfOk) {
                setOnClickListener {
                    workspace?.let { ReaderActivity.start(this@LibraryActivity, it, d.id) }
                }
            }
        }
        col.addView(
            TextView(this).apply {
                text = d.title
                textSize = 17f
                setTextColor(if (pdfOk) 0xFF1A1A1A.toInt() else 0xFF9A9A9A.toInt())
            },
        )
        val progress =
            if (d.readPage > 0 || d.readFrac > 0)
                "读到第 ${d.readPage + 1} 页（${((d.readPage + d.readFrac) / d.pageCount * 100).toInt()}%）"
            else "未开始"
        col.addView(
            TextView(this).apply {
                text = "${d.pageCount} 页　$progress" + if (inkCount > 0) "　笔迹 $inkCount 条" else ""
                textSize = 13f
                setTextColor(0xFF6B6B6B.toInt())
                gravity = Gravity.START
            },
        )
        if (!pdfOk) {
            col.addView(
                TextView(this).apply {
                    text = "PDF 路径失效——需要在 Mac 上把文件拷进工作区（首版不解析外置卷相对路径）"
                    textSize = 12f
                    setTextColor(0xFFB3261E.toInt())
                },
            )
        }
        return col
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0xFFE4E4E4.toInt())
        layoutParams = LinearLayout.LayoutParams(-1, 1)
    }
}
