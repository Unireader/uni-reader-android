package com.xvan.unireader.local

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.shared.MODE_ERASE
import com.xvan.unireader.shared.onSystemBarInsets
import com.xvan.unireader.shared.MODE_NOTE
import com.xvan.unireader.shared.MODE_PAGE
import java.io.File

/**
 * 模式1 的阅读界面：本机 Pdfium 出图 + `LocalCanvasView`（与模式2 同一套几何/手势）。
 *
 * 进度**双向续接**（`ANDROID-STANDALONE-PLAN.md §11.3`）：打开时按 `document` 的四列复原
 * （先缩放再滚——顺序反了 offY 还是旧的，滚到的位置会偏），退出/切后台时写回。
 */
class ReaderActivity : Activity() {

    companion object {
        const val TAG = "UniReader/Reader"
        const val EXTRA_WORKSPACE = "workspace"
        const val EXTRA_DOC_ID = "docId"

        /** 进度落库节流：滚动上报是 16ms 一次，直接写库等于一秒几十次事务 */
        const val SAVE_INTERVAL_MS = 2000L

        fun start(ctx: Context, workspaceDir: File, documentId: String) {
            ctx.startActivity(
                Intent(ctx, ReaderActivity::class.java)
                    .putExtra(EXTRA_WORKSPACE, workspaceDir.absolutePath)
                    .putExtra(EXTRA_DOC_ID, documentId),
            )
        }
    }

    private lateinit var canvas: LocalCanvasView
    private lateinit var pageLabel: TextView
    private lateinit var modeBtn: Button
    private lateinit var penBtn: Button
    private var store: LibraryStore? = null
    private var pdf: PdfSource? = null
    private var workspace: File? = null
    private var docId = ""
    private val handler = Handler(Looper.getMainLooper())

    // 待落库的进度（滚动线程外只有主线程写，无需加锁）
    private var curPage = 0
    private var curFrac = 0f
    private var dirty = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val wsPath = intent.getStringExtra(EXTRA_WORKSPACE)
        docId = intent.getStringExtra(EXTRA_DOC_ID) ?: ""
        if (wsPath == null || docId.isEmpty()) {
            Log.e(TAG, "缺参数：workspace=$wsPath docId=$docId")
            finish()
            return
        }
        val ws = File(wsPath)
        workspace = ws
        buildUi()
        if (!open(ws)) finish()
    }

    private fun buildUi() {
        val barH = dp(44)
        canvas = LocalCanvasView(this).apply {
            onProgress = { page, frac ->
                curPage = page
                curFrac = frac
                dirty = true
            }
            onHud = { refreshHud() }
        }
        pageLabel = TextView(this).apply {
            text = "— / —"
            textSize = 13f
            setTextColor(Color.WHITE)
            setOnClickListener { showGotoPage() }
        }
        modeBtn = barBtn("翻页") { cycleMode() }
        penBtn = barBtn("笔") { canvas.cyclePen(); refreshHud() }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true   // 挡住触摸穿透到画布
            setBackgroundColor(0xE6161B22.toInt())
            setPadding(dp(10), 0, dp(10), 0)
            addView(barBtn("◀") { canvas.turn(prev = true) })
            addView(barBtn("▶") { canvas.turn(prev = false) })
            addView(modeBtn)
            addView(penBtn)
            addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(pageLabel)
        }
        val root = FrameLayout(this).apply {
            addView(canvas, FrameLayout.LayoutParams(-1, -1))
            addView(bar, FrameLayout.LayoutParams(-1, barH, Gravity.TOP))
        }
        setContentView(root)
        canvas.setBarHeight(barH.toFloat())
        // 顶栏避开状态栏：不处理的话按钮压在时钟上，点击会被系统栏吃掉（见 shared/Insets.kt）
        root.onSystemBarInsets { top, _ ->
            val lp = bar.layoutParams as FrameLayout.LayoutParams
            if (lp.height != barH + top) {
                lp.height = barH + top
                bar.layoutParams = lp
                bar.setPadding(dp(10), top, dp(10), 0)
                canvas.setBarHeight((barH + top).toFloat())
            }
        }
    }

    private fun open(ws: File): Boolean {
        val s = try {
            LibraryStore.open(ws)
        } catch (e: Exception) {
            Log.e(TAG, "打开库失败", e)
            return false
        }
        store = s
        val doc = s.document(docId) ?: run {
            Log.e(TAG, "库里没有这个文档：$docId")
            return false
        }
        title = doc.title
        val file = Workspace.firstOpenablePdf(ws, s, docId) ?: run {
            Log.e(TAG, "${doc.title}：没有能打开的 PDF（location 全部失效）")
            return false
        }
        val src = try {
            PdfSource(this, file)
        } catch (e: Exception) {
            Log.e(TAG, "Pdfium 打开失败：${file.name}", e)
            return false
        }
        pdf = src
        src.logPageSizes()   // §9.1 的比对凭据：与 tools/dump-page-sizes.swift 的输出逐行 diff
        canvas.imageSource = src
        canvas.setPages(
            src.pageCount,
            src.pageSizes.map { it[0] to it[1] },
            reset = true,
        )
        // 落库上下文 + 当前作画图层（老文档可能没有图层行，补一条默认层，同 Mac 的行为）
        s.ensureDefaultLayer(docId)
        canvas.store = s
        canvas.documentId = docId
        canvas.activeLayerId =
            s.inkLayers(docId).firstOrNull()?.id ?: com.xvan.unireader.local.store.LibInkLayer.DEFAULT_ID
        canvas.setMode(MODE_PAGE)
        canvas.reloadStrokes()
        s.updateLastOpened(docId)

        // 进度复原：等**首次真实布局**之后再做（否则 offY 还是 width=0 时算的，滚过去等于滚到页顶），
        // 且顺序必须是先缩放再滚——反了 offY 是旧内容宽下的值，落点会偏。
        curPage = doc.readPage
        curFrac = doc.readFrac.toFloat()
        canvas.onFirstGeometry = {
            if (doc.readZoom > 0) canvas.applyZoom(doc.readZoom.toFloat())
            canvas.applyHFrac(doc.readHFrac.toFloat())
            val ok = canvas.scrollToPageFrac(doc.readPage, doc.readFrac.toFloat())
            Log.i(TAG, "复原滚动 → 第 ${doc.readPage + 1} 页 ${"%.3f".format(doc.readFrac)} ok=$ok")
            refreshHud()
        }
        Log.i(
            TAG,
            "打开《${doc.title}》${src.pageCount} 页，复原到 第 ${doc.readPage + 1} 页 " +
                "${"%.3f".format(doc.readFrac)} zoom=${doc.readZoom} hfrac=${doc.readHFrac}",
        )
        handler.postDelayed(saver, SAVE_INTERVAL_MS)
        return true
    }

    private fun barBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    /**
     * 阅读默认 `MODE_PAGE`（笔也用来平移）——一打开就是写字模式的话，随手一碰就是一道墨。
     * 只在 翻页/笔记/擦除 三态间轮换：框选移动是 M4 的事（它的提交钩子还没落库）。
     */
    private fun cycleMode() {
        val next = when (canvas.mode) {
            MODE_PAGE -> MODE_NOTE
            MODE_NOTE -> MODE_ERASE
            else -> MODE_PAGE
        }
        canvas.setMode(next)
        refreshHud()
    }

    private fun refreshHud() {
        // 每帧都会调（滚动/缩放都触发），文本没变就别碰：十来个控件乘 60fps 就是白烧的一帧
        val s = "${canvas.hudPage()}　${canvas.hudZoom()}"
        if (pageLabel.text?.toString() != s) pageLabel.text = s
        val m = canvas.modeLabel()
        if (modeBtn.text?.toString() != m) modeBtn.text = m
        val p = if (canvas.mode == MODE_NOTE) canvas.penLabel() else "笔"
        if (penBtn.text?.toString() != p) penBtn.text = p
    }

    private fun showGotoPage() {
        val n = canvas.pageCountOrZero()
        if (n <= 0) return
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "1 ~ $n"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("跳到第几页")
            .setView(input)
            .setPositiveButton("跳转") { _, _ ->
                input.text.toString().toIntOrNull()?.let { canvas.gotoPage(it) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 进度落库 ----------

    private val saver = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    private fun saveProgress() {
        if (!dirty) return
        val s = store ?: return
        dirty = false
        try {
            s.updateProgress(
                docId, curPage, curFrac.toDouble(),
                canvas.zoomLevel().toDouble(), canvas.hFrac().toDouble(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "写进度失败", e)
        }
    }

    override fun onPause() {
        super.onPause()
        saveProgress()   // 切后台立刻落盘：进程随时可能被杀
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        saveProgress()
        pdf?.close()
        store?.close()   // 内含 wal_checkpoint(TRUNCATE)
        super.onDestroy()
    }
}
