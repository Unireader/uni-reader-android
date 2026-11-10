package com.xvan.unireader.local

import android.app.Activity
import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.xvan.unireader.R
import com.xvan.unireader.shared.Bg
import com.xvan.unireader.shared.PadPanels
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.TextRun
import com.xvan.unireader.shared.TextSearch
import com.xvan.unireader.shared.Ui
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全文搜索面板（模式1，同 Mac ⌘F 的「全文 + 结果列表」；规则在 `shared/TextSearch`）。
 * 边打字边搜（停手 300ms 才跑，上一轮作废）：OCR 层用画布里已装好的整本可见行，自带文字层逐页问 Pdfium（后台线程，一页只取一次）。
 * 点一处 → 关面板、跳过去（记进跳转历史）。本轮不在页面上标出命中的字。
 */
object SearchSheet {
    /** 上次搜的词（进程内记着：再打开面板接着翻结果）。 */
    private var lastQuery = ""

    fun show(a: Activity, pdf: PdfSource, ocr: Map<Int, List<TextRun>>, onPick: (TextSearch.Hit, String) -> Unit) {
        val edit = PadPanels.inputBox(a, a.getString(R.string.search_hint)).apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(lastQuery)
            setSelection(text.length)
        }
        val status = Ui.body(a, "").apply { setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 4)) }
        val results = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(edit)
            addView(status)
            addView(results)   // 弹层的内容区自己会滚（Sheet.content），这里不再套一层
        }
        var dlg: android.app.AlertDialog? = null
        var running: AtomicBoolean? = null   // 这一轮的作废标记
        val accent = Ui.accent(a)

        fun render(query: String, hits: List<TextSearch.Hit>) {
            results.removeAllViews()
            status.text = when {
                hits.isEmpty() -> a.getString(R.string.search_none)
                hits.size >= TextSearch.MAX_HITS -> a.getString(R.string.search_more, TextSearch.MAX_HITS)
                else -> a.getString(R.string.search_count, hits.size)
            }
            for (h in hits) {
                val text = SpannableString(h.snippet).apply {
                    setSpan(ForegroundColorSpan(accent), h.hitStart, h.hitEnd, 0)
                    setSpan(StyleSpan(Typeface.BOLD), h.hitStart, h.hitEnd, 0)
                }
                results.addView(
                    Ui.row(a) {
                        dlg?.dismiss()
                        onPick(h, query)
                    }.apply {
                        addView(Ui.groupTitle(a, a.getString(R.string.search_row_page, h.page + 1), top = 0))
                        addView(TextView(a).apply {
                            setText(text)
                            textSize = 15f
                            setTextColor(Ui.onSurface(a))
                            maxLines = 3
                        })
                    },
                )
            }
        }

        fun search(query: String) {
            running?.set(true)
            lastQuery = query
            val needle = TextSearch.foldQuery(query)
            results.removeAllViews()
            if (needle.isEmpty()) { status.text = ""; return }
            status.text = a.getString(R.string.search_busy)
            val cancelled = AtomicBoolean(false)
            running = cancelled
            Bg.submit("全文搜索「${query.take(12)}」") {
                val ocrHits = ArrayList<TextSearch.Hit>()
                for ((page, runs) in ocr) {
                    if (cancelled.get()) return@submit
                    TextSearch.searchOcrPage(page, runs, needle, ocrHits)
                }
                val plain = ArrayList<TextSearch.Hit>()
                for (page in 0 until pdf.pageCount) {
                    if (cancelled.get()) return@submit
                    TextSearch.searchPlainPage(page, pdf.pageText(page), needle, plain)
                }
                val all = TextSearch.merge(ocrHits, plain).take(TextSearch.MAX_HITS).map { h ->
                    if (cancelled.get()) return@submit
                    if (h.frac >= 0f) h else h.copy(frac = pdf.charTopFrac(h.page, h.origin))
                }.sortedWith(compareBy<TextSearch.Hit> { it.page }.thenBy { it.frac })
                Bg.main.post { if (!cancelled.get() && dlg?.isShowing == true) render(query, all) }
            }
        }

        val debounce = Runnable { search(edit.text.toString().trim()) }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                edit.removeCallbacks(debounce)
                edit.postDelayed(debounce, 300)
            }
        })
        dlg = Sheet(a).title(a.getString(R.string.search_title))
            .size(560, 0.8f)
            .content(body)
            .action(a.getString(R.string.act_cancel))
            .show()
        dlg.setOnDismissListener { running?.set(true); edit.removeCallbacks(debounce) }
        if (lastQuery.isNotEmpty()) search(lastQuery)
    }
}
