package com.xvan.unireader.shared

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.xvan.unireader.R
import kotlin.math.min

/**
 * 全 App 唯一的弹层外观（`ANDROID-STANDALONE-PLAN.md §9.6`）。
 *
 * 从前十处弹窗全是裸 `AlertDialog.Builder`：框架默认的直角白底 + 全大写文字按钮 + 各自
 * 一套内边距，界面外壳换新之后它们就成了"点开哪个都掉回 2015 年"。现在统一成
 * **圆角 surface 卡片 + 标题 + 主体 + 右下操作行**，颜色走语义名，深浅色自动跟随。
 *
 * **仍然用 `AlertDialog` 托着**（而不是自绘一个浮层）：外点关闭、返回键、输入法顶起、
 * 焦点与无障碍焦点、屏幕旋转后的重建——这些系统对话框都已经处理好了，自绘一遍只会漏。
 * 这里只是把它的窗口背景设成透明，把外观完全接管过来。
 *
 * 用法：
 * ```
 * Sheet(a).title("图层")
 *     .content(myView)
 *     .action("完成", primary = true)
 *     .show()
 * ```
 */
class Sheet(private val a: Activity) {

    companion object {
        /** 卡片最大宽度：平板上不让它撑满整屏（一行几十个字读起来累） */
        const val MAX_W = 420

        /** 主体最高占屏高的比例，超了内部滚动——不限的话长图层表会把操作行顶出屏幕 */
        const val MAX_H_RATIO = 0.6f
    }

    private val titleView = Ui.title(a, "", 20f).apply { visibility = View.GONE }
    private val subtitleView = Ui.body(a, "").apply {
        visibility = View.GONE
        setPadding(0, Ui.dp(a, 6), 0, 0)
    }

    /** 主体容器。动态刷新的弹层（目录浏览器）直接往这里塞/清 */
    val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    private val actions = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, Ui.dp(a, 12), 0, 0)
    }

    private var cancelable = true
    private var dialog: AlertDialog? = null
    private var maxWDp = MAX_W
    private var maxHRatio = MAX_H_RATIO

    /** 大弹层（工具栏编辑之类）放宽卡片的最大宽度 / 主体最大高度占比 */
    fun size(maxWidthDp: Int, maxHeightRatio: Float) = apply {
        maxWDp = maxWidthDp
        maxHRatio = maxHeightRatio
    }

    /** 主体的限高滚动容器：内容不高时按内容走，高了才滚 */
    private val scroll = object : ScrollView(a) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val max = (a.resources.displayMetrics.heightPixels * maxHRatio).toInt()
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
        }
    }.apply {
        isFillViewport = false
        addView(body)
    }

    fun title(s: String) = apply {
        titleView.text = s
        titleView.visibility = View.VISIBLE
    }

    /** 标题下的一句说明（原先都是塞进 `setMessage`，那会和主体挤在一起） */
    fun subtitle(s: String) = apply {
        subtitleView.text = s
        subtitleView.visibility = View.VISIBLE
    }

    fun content(v: View) = apply {
        body.addView(
            v,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    fun cancelable(b: Boolean) = apply { cancelable = b }

    /**
     * 右下角一个操作。[primary] 是 accent 色的那个（一屏只该有一个）。
     * [dismiss] 为 false 时**不自动关窗**——「连接」失败要留在原地改 IP，关掉就得重填。
     * 回调拿得到 dialog，自己决定什么时候关。
     */
    fun action(
        label: String,
        primary: Boolean = false,
        dismiss: Boolean = true,
        onClick: (AlertDialog) -> Unit = {},
    ) = apply {
        actions.addView(
            Ui.textButton(a, label, primary) {
                val d = dialog ?: return@textButton
                if (dismiss) d.dismiss()
                onClick(d)
            },
        )
    }

    fun show(): AlertDialog {
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(Ui.surface(a), 24, a)
            val p = Ui.dp(a, 22)
            setPadding(p, p, p, Ui.dp(a, 14))
            addView(titleView)
            addView(subtitleView)
            addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 14) })
            if (actions.childCount > 0) addView(actions)
        }
        val d = AlertDialog.Builder(a)
            .setView(root)
            .setCancelable(cancelable)
            .create()
        dialog = d
        // 窗口背景设透明，外观完全由上面那张卡片接管（不然圆角外面会露出框架的白底直角）
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
        val dm = a.resources.displayMetrics
        d.window?.setLayout(
            min(dm.widthPixels - Ui.dp(a, 48), Ui.dp(a, maxWDp)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        return d
    }

    /** 不可取消的「正在…」：一个转圈 + 一行字，没有操作行 */
    fun busy(msg: String): AlertDialog {
        cancelable(false)
        content(
            LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    ProgressBar(a).apply { isIndeterminate = true },
                    LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)).apply {
                        marginEnd = Ui.dp(a, 14)
                    },
                )
                addView(Ui.title(a, msg, 15f))
            },
        )
        return show()
    }
}

/** 一句话提示：标题 + 说明 + 一个「好」。原先散在各处的 `alert()` 收敛到这儿 */
fun Activity.showAlert(title: String, msg: String, onDismiss: () -> Unit = {}) {
    Sheet(this).title(title).subtitle(msg).action("好", primary = true).show()
        .setOnDismissListener { onDismiss() }
}
