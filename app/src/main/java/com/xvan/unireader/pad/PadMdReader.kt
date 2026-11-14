package com.xvan.unireader.pad

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.xvan.unireader.R
import com.xvan.unireader.shared.MdDisplay
import com.xvan.unireader.shared.MdRender
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Ui

/**
 * 模式2 的 Markdown 笔记阅读（`../docs/PROTOCOL.md §4.10`，`../docs/MARKDOWN-NOTES-PLAN.md §21`）：
 * Mac 当前标签是 Markdown 笔记时，显示 `mdNote` 送来的那篇正文。**只读**——不能编辑、不回传。
 *
 * 排版与模式1 阅读态同一份（[MdRender]）；图按 Mac 给的 sha 走 `/image?h=`（[images]，与画板的图共用一份缓存）。
 * 同一篇重发（Mac 上存了一次）保持滚动位置；换了一篇回到顶部。
 */
class PadMdReader(private val a: Activity) {

    val view = FrameLayout(a).apply {
        setBackgroundColor(Ui.surface(a))
        isClickable = true   // 吃掉触摸：下面那块页面视图是上一篇 PDF 的，别还能往上写
        visibility = View.GONE
    }

    private val titleView = Ui.title(a, "", 20f).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(a).apply { addView(box) }

    /** 图从哪取（连上 Mac 之后由宿主设；没设 = 图先空着） */
    var images: BoardImageFetcher? = null

    /** 收到过的那一篇（null = 还没收到过：老 Mac 不发，宿主照旧显示空状态） */
    var key: String? = null
        private set
    private var text = ""
    private var shas: Map<String, String> = emptyMap()

    init {
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(a, 20)
            setPadding(p, Ui.dp(a, 12), p, 0)
            addView(titleView, LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        view.addView(column, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        // 同模式1：正文栏最宽 760dp、居中（平板上一行太长读起来累）
        view.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val maxW = Ui.dp(a, 760)
            val lp = column.layoutParams as FrameLayout.LayoutParams
            val want = if (r - l > maxW) maxW else -1
            if (lp.width != want) { lp.width = want; column.layoutParams = lp }
        }
    }

    fun show(m: WireCodec.Msg.MdNote) {
        val same = m.key == key
        if (same && m.text == text && m.images == shas && titleView.text.toString() == m.title) return
        val y = if (same) scroll.scrollY else 0
        key = m.key
        text = m.text
        shas = m.images
        titleView.text = m.title
        MdRender.into(a, box, m.text, ::imageView, ::onLink, a.getString(R.string.md_empty_readonly))
        // 重排完再滚：同一篇停在原处，换篇回到顶部
        scroll.post { scroll.scrollTo(0, y) }
    }

    /** 断开 / 换了 Mac：忘掉上一篇（重连后 Mac 会随 `boards` 重发） */
    fun clear() {
        key = null
        text = ""
        shas = emptyMap()
        box.removeAllViews()
    }

    private fun load(sha: String, done: (Bitmap?) -> Unit) {
        val f = images ?: return done(null)
        f.load(sha) { bmp -> a.runOnUiThread { done(bmp) } }
    }

    /** 单独一行的 `![[图]]`：Mac 解析得到的才有 sha，没有就写一行「找不到」 */
    private fun imageView(target: String): View {
        val sha = shas[target] ?: return Ui.body(a, a.getString(R.string.md_not_found, target))
        val iv = ImageView(a).apply {
            adjustViewBounds = true
            maxWidth = Ui.dp(a, 760)
            scaleType = ImageView.ScaleType.FIT_START
            setOnClickListener { showImage(target, sha) }
        }
        load(sha) { bmp -> if (bmp != null) iv.setImageBitmap(bmp) }
        return iv
    }

    private fun onLink(link: String) {
        when {
            // `[[笔记]]` / `[[书#page=]]`：平板上只读，不跳转
            link.startsWith(MdDisplay.WIKI_SCHEME) ->
                Toast.makeText(a, a.getString(R.string.md_link_on_mac), Toast.LENGTH_SHORT).show()
            link.startsWith(MdDisplay.IMG_SCHEME) -> {
                val target = MdDisplay.decode(link.removePrefix(MdDisplay.IMG_SCHEME))
                val sha = shas[target]
                if (sha == null) Toast.makeText(a, a.getString(R.string.md_not_found, target), Toast.LENGTH_SHORT).show()
                else showImage(target, sha)
            }
            else -> runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                .onFailure { Log.w(TAG, "打不开链接 $link", it) }
        }
    }

    /** 看大图（同模式1 的附件大图） */
    private fun showImage(target: String, sha: String) {
        load(sha) { bmp ->
            val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            if (bmp != null) {
                root.addView(ImageView(a).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                })
            } else {
                root.addView(Ui.body(a, a.getString(R.string.img_missing)))
            }
            Sheet(a).size(720, 0.8f).title(target.substringAfterLast('/')).content(root)
                .action(a.getString(R.string.act_done), primary = true).show()
        }
    }

    private companion object {
        const val TAG = "UniReader/MdPad"
    }
}
