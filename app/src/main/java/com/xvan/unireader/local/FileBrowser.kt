package com.xvan.unireader.local

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.xvan.unireader.R
import com.xvan.unireader.shared.PadPanels
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.runInBackground
import java.io.File

/**
 * 极简目录浏览器：**先列存储卷**，再一级级点进去。三种模式共用同一份（[Mode]）。
 *
 * **为什么不用系统的 `ACTION_OPEN_DOCUMENT_TREE` / `ACTION_OPEN_DOCUMENT`**：它们返回 SAF 的
 * `content://` Uri，而裸 `SQLiteDatabase` 与 Pdfium 都要真实文件路径，把 Uri 反推成路径是各家
 * ROM 各不相同的猜谜游戏。既然「打开工作区」本来就要全盘文件权限，直接用 `File` 列目录最可预期。
 *
 * 两条口径（2026-08-03 用户定，见 `ANDROID-STANDALONE-PLAN.md §9.8`）：
 * - **第一层是存储卷**（[StorageScan.volumes]）——外部存储上的工作区才是常态，靠"一级级往上点"
 *   去够 `/storage/XXXX-XXXX` 既不直观、部分 ROM 还够不到；
 * - **能选中什么由模式决定**，其余目录只能点进去继续找。
 *
 * 列目录走后台（§9.5）：`listFiles` 加上逐个 `isDirectory` 的 stat，在 U 盘/同步盘上是秒级，
 * 而用户是一级一级点进去的——每一级都卡一下，整个过程就在反复触发 ANR 观察窗。
 */
class FileBrowser(
    private val a: Activity,
    private val mode: Mode,
    private val title: String,
    /** 底部那句一行提示（[Ui.tip]）。别写成一段说明书——那种字没人读 */
    private val tipText: String? = null,
    /**
     * 右下主操作：文本 + 回调（拿到当前目录，null = 还停在卷列表那一层；外加窗口本身）。
     * **点它默认不关窗**——「还没选到位置」这种情况要能原地提示一句、让用户接着点，
     * 关掉就得从卷列表重走一遍。要关由回调自己 `dismiss()`。
     */
    private val primary: Pair<String, (File?, AlertDialog) -> Unit>? = null,
    /** [Mode.PDF] 专用：给一行文件加个尾标（如「已添加」）。非 null 即该行不可点 */
    private val fileNote: ((File) -> String?)? = null,
    /**
     * 关窗时的收尾（[Mode.PDF] 用它收掉那条可写连接 + 刷新列表）。
     * **别在外面对 `show()` 的返回值再挂一次 `setOnDismissListener`**——那会顶掉本类自己那一个，
     * 令牌就复位不了（`AlertDialog` 只存一个监听器）。
     */
    private val onDismiss: (() -> Unit)? = null,
    /** 选中了一项：`.unrd` 文件夹（[Mode.WORKSPACE]）或一个 PDF（[Mode.PDF]） */
    private val onPick: (File) -> Unit,
) {

    enum class Mode {
        /** 只有 `.unrd` 能选中，点它 = 打开并关掉浏览器 */
        WORKSPACE,

        /** 什么都选不中，靠右下的主操作对"当前目录"下手（新建工作区选存放位置） */
        FOLDER,

        /** 列出 `.pdf` 文件，点一个 = 加进来；**不关窗**，好接着加下一本 */
        PDF,
    }

    companion object {
        const val TAG = "UniReader/Browse"
    }

    private var cur: File? = null                          // null = 停在存储卷这一层
    private var vols: List<StorageScan.Volume> = emptyList()

    /** 当前请求令牌：用户点得比慢卷读得快，回来的旧结果要丢掉 */
    private var token: Any? = null

    private val pathText = Ui.body(a, "").apply { textSize = 12f }
    private val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private var dlg: AlertDialog? = null

    private fun dp(v: Int) = Ui.dp(a, v)

    private fun hint(s: String) = Ui.body(a, s).apply { setPadding(0, 0, 0, dp(8)) }

    fun show(): AlertDialog {
        val sheet = Sheet(a).title(title)
        sheet.content(pathText)
        sheet.content(list)
        if (tipText != null) sheet.content(Ui.tip(a, tipText).apply { setPadding(0, dp(10), 0, 0) })
        sheet.action(if (mode == Mode.PDF) "完成" else "取消")
        primary?.let { (label, act) ->
            sheet.action(label, primary = true, dismiss = false) { d -> act(cur, d) }
        }
        val d = sheet.show()
        dlg = d
        d.setOnDismissListener {
            token = null
            onDismiss?.invoke()
        }
        render()
        return d
    }

    /** 重新列一遍当前这一层（[Mode.PDF] 加完一本后刷新尾标用） */
    fun refresh() = render()

    private fun alive(t: Any?): Boolean = token === t && dlg?.isShowing == true

    private fun render() {
        list.removeAllViews()
        val dir = cur
        val t = Any()
        token = t
        if (dir == null) {
            renderVolumes(t)
            return
        }
        pathText.text = dir.absolutePath
        val atRoot = vols.any { it.dir.absolutePath == dir.absolutePath }
        list.addView(
            PadPanels.iconRow(
                a,
                R.drawable.ic_chevron_up,
                if (atRoot) "存储卷列表" else "上一级",
                Ui.onVariant(a),
            ) {
                cur = if (atRoot) null else dir.parentFile
                render()
            },
        )
        val waiting = hint("正在读取目录…")
        list.addView(waiting)
        a.runInBackground(
            what = "列目录 ${dir.name}",
            // 过滤与排序也在后台：`isDirectory` 是每个条目一次 stat，才是慢的那部分
            work = { listing(dir) },
            ok = { l -> if (alive(t)) fill(waiting, l) },
            fail = {
                if (alive(t)) {
                    list.removeView(waiting)
                    list.addView(hint("读取目录失败：${it.message}"))
                }
            },
        )
    }

    private fun renderVolumes(t: Any) {
        pathText.text = "选择一块存储"
        val waiting = hint("正在查找存储卷…")
        list.addView(waiting)
        a.runInBackground(
            what = "枚举存储卷",
            work = { StorageScan.volumes(a) },
            ok = { vs ->
                if (!alive(t)) return@runInBackground
                vols = vs
                list.removeView(waiting)
                if (vs.isEmpty()) {
                    list.addView(hint("没有找到可读的存储卷——确认已授权，U 盘是否已挂载"))
                    return@runInBackground
                }
                for (v in vs) {
                    list.addView(
                        PadPanels.twoLineRow(
                            a, R.drawable.ic_folder, Ui.onVariant(a), v.label, v.dir.absolutePath,
                        ) {
                            cur = v.dir
                            render()
                        },
                    )
                }
            },
            fail = {
                if (alive(t)) {
                    list.removeView(waiting)
                    list.addView(hint("枚举存储卷失败：${it.message}"))
                }
            },
        )
    }

    /**
     * @param stray 这个目录本身就有 `UniReader/library.sqlite`，但名字不是 `.unrd`——
     *   用户十有八九是在这儿找他的库，得当场说清为什么点不了。
     */
    private class Listing(val dirs: List<File>?, val files: List<File>, val stray: Boolean)

    private fun listing(dir: File): Listing {
        val all = dir.listFiles()
        return Listing(
            dirs = all?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() },
            files = if (mode != Mode.PDF) {
                emptyList()
            } else {
                all?.filter { PdfImport.looksLikePdf(it) }?.sortedBy { it.name.lowercase() }.orEmpty()
            },
            stray = mode == Mode.WORKSPACE &&
                !Workspace.looksLikeWorkspace(dir) && File(dir, Workspace.DB_REL).isFile,
        )
    }

    private fun fill(waiting: View, l: Listing) {
        list.removeView(waiting)
        if (l.stray) {
            list.addView(hint("这个文件夹里有库文件，但名字不是 .unrd——改名成「xxx.unrd」再来打开"))
        }
        if (l.dirs == null) {
            list.addView(hint("无法读取此目录（没有权限，或不是真实目录）"))
            return
        }
        if (l.dirs.isEmpty() && l.files.isEmpty()) {
            list.addView(hint(if (mode == Mode.PDF) "（这里没有子文件夹，也没有 PDF）" else "（没有子文件夹）"))
        }
        for (d in l.dirs) list.addView(dirRow(d))
        for (f in l.files) list.addView(fileRow(f))
    }

    /** `.unrd` 用 accent 色的书本图标挑出来：一屏几十个文件夹，靠名字分辨太费眼 */
    private fun dirRow(d: File): View {
        val isWs = mode == Mode.WORKSPACE && Workspace.looksLikeWorkspace(d)
        return PadPanels.iconRow(
            a,
            if (isWs) R.drawable.ic_doc else R.drawable.ic_folder,
            d.name,
            if (isWs) Ui.accent(a) else Ui.onVariant(a),
            trailing = if (isWs) tailText("打开", Ui.accent(a)) else chevron(),
        ) {
            // .unrd 点进去没意义（里面只有 UniReader/ 和 PDFs/），直接当选中处理
            if (isWs) {
                dlg?.dismiss()
                onPick(d)
            } else {
                cur = d
                render()
            }
        }
    }

    private fun fileRow(f: File): View {
        val note = fileNote?.invoke(f)
        val row = PadPanels.iconRow(
            a,
            R.drawable.ic_doc,
            f.name,
            if (note == null) Ui.accent(a) else Ui.outline(a),
            trailing = tailText(note ?: sizeText(f.length()), Ui.onVariant(a)),
        ) {
            if (note == null) onPick(f) else Log.i(TAG, "${f.name} 已处理过，忽略这次点击")
        }
        if (note != null) row.isClickable = false
        return row
    }

    private fun sizeText(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "${bytes / (1L shl 20)} MB"
        else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    }

    private fun tailText(s: String, color: Int) = Ui.body(a, s).apply {
        textSize = 12f
        setTextColor(color)
        setPadding(dp(8), 0, dp(4), 0)
    }

    private fun chevron() = ImageView(a).apply {
        setImageResource(R.drawable.ic_chevron_right)
        imageTintList = ColorStateList.valueOf(Ui.outline(a))
        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
    }
}
