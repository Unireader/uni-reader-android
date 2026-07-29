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
import java.io.File

/**
 * 工作区的文档列表（模式1）：标题、页数、阅读进度、笔迹条数、上次打开。
 *
 * 每次 `onResume` 重读一遍库——从阅读界面退回来时进度已经变了，缓存一份反而要自己同步。
 * 列表规模是「一个人的书架」，几十条，重读的开销可以忽略。
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

    private lateinit var list: LinearLayout
    private lateinit var header: TextView
    private var workspace: File? = null

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
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val ws = workspace ?: return
        list.removeAllViews()
        val docs: List<LibDocument>
        val info: String
        try {
            LibraryStore.open(ws, readOnly = true).use { store ->
                docs = store.allDocuments()
                info = "${store.workspaceName().ifEmpty { ws.name }}　${docs.size} 个文档"
                for (d in docs) {
                    val ink = store.noteCount(d.id, NoteKind.INK)
                    val pdfOk = Workspace.firstOpenablePdf(ws, store, d.id) != null
                    list.addView(row(d, ink, pdfOk))
                    list.addView(divider())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "读库失败", e)
            header.text = "打不开工作区：${e.message}"
            return
        }
        title = ws.name
        header.text = info
        if (docs.isEmpty()) {
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
