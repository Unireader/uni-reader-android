package com.xvan.unireader

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xvan.unireader.local.PdfSource
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.NoteKind
import com.xvan.unireader.pad.PadActivity
import com.xvan.unireader.shared.brushName
import java.io.File
import kotlin.math.abs

/**
 * 启动页：同一个 App 的两种模式二选一（ANDROID-STANDALONE-PLAN §3）。
 * - **打开工作区**（模式1 独立版）：本机直接读 `.unrd/UniReader/library.sqlite` + 本地渲染 PDF。
 *   需要「所有文件访问权限」，这里负责引导；文档列表/阅读/手写是 M1 起的事。
 * - **连 Mac 当输入板**（模式2）：进 [PadActivity]，即已验收的 WS+UDP 链路。
 *
 * 授权状态、选中路径、校验结果**一律打日志**（tag `UniReader/Launcher` 与 `UniReader/WS`）：
 * 「授权了但还是打不开」这类问题不打点就只能靠猜。
 */
class Launcher : Activity() {

    companion object {
        const val TAG = "UniReader/Launcher"
        const val REQ_STORAGE = 71
    }

    private lateinit var permText: TextView
    private lateinit var permBtn: Button
    private lateinit var recentBox: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    /** 从系统设置授权页返回时不会有回调，只能在 onResume 重新查一遍 */
    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------- UI ----------

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }

        col.addView(title("UniReader"))
        col.addView(hint("版本 ${versionName()}"))

        // —— 权限 ——
        permText = hint("")
        permBtn = btn("去授权") { requestAllFiles() }
        col.addView(gap(dp(20)))
        col.addView(permText)
        col.addView(permBtn)

        // —— 模式1 ——
        col.addView(gap(dp(24)))
        col.addView(section("打开工作区（本机独立）"))
        col.addView(hint("直接读 .unrd 工作区里的 library.sqlite 与 PDFs/，不需要开着 Mac。"))
        col.addView(btn("选择 .unrd 文件夹…") { onPickWorkspace() })
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(recentBox)

        // —— 模式2 ——
        col.addView(gap(dp(24)))
        col.addView(section("连 Mac 当输入板"))
        col.addView(hint("平板只管采集笔迹，页面与笔迹的真源在 Mac 上。"))
        col.addView(
            btn("进入输入板") {
                Log.i(TAG, "进入模式2 输入板")
                startActivity(Intent(this, PadActivity::class.java))
            },
        )

        // —— 单写者提醒（§9.2：工作区没有任何加锁/同步机制） ——
        col.addView(gap(dp(28)))
        col.addView(
            hint(
                "同一个工作区同一时间只能一端打开：平板在用时别在 Mac 上开同一个工作区，" +
                    "否则两边的写入会互相覆盖。搬运时把整个 .unrd 文件夹一起拷（含 -wal/-shm），" +
                    "放在云盘同步目录上尤其危险。",
            ),
        )

        setContentView(ScrollView(this).apply { addView(col) })
        refresh()
    }

    /**
     * 按钮一律走这里：Material 主题默认 `textAllCaps=true`，会把「选择 .unrd 文件夹…」显示成
     * 「选择 .UNRD 文件夹…」——而 `.unrd` 是要用户在文件夹名里认的后缀，大写就对不上了。
     */
    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun title(s: String) = TextView(this).apply {
        text = s; textSize = 28f; setTextColor(0xFF1A1A1A.toInt())
    }

    private fun section(s: String) = TextView(this).apply {
        text = s; textSize = 17f; setTextColor(0xFF1A1A1A.toInt())
        setPadding(0, 0, 0, dp(4))
    }

    private fun hint(s: String) = TextView(this).apply {
        text = s; textSize = 13f; setTextColor(0xFF6B6B6B.toInt())
        setPadding(0, 0, 0, dp(8))
    }

    private fun gap(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        "?"
    }

    private fun refresh() {
        val ok = hasAllFiles()
        Log.i(TAG, "刷新：全盘文件权限=$ok sdk=${Build.VERSION.SDK_INT}")
        permText.text =
            if (ok) "已获得所有文件访问权限。"
            else "尚未获得「所有文件访问权限」——打开工作区需要它（模式2 输入板不需要）。" +
                "小米/HyperOS 等国产 ROM 需要在系统设置里单独允许。"
        permBtn.visibility = if (ok) View.GONE else View.VISIBLE
        refreshRecents()
    }

    private fun refreshRecents() {
        recentBox.removeAllViews()
        val list = Workspace.recents(this)
        if (list.isEmpty()) return
        recentBox.addView(hint("最近打开："))
        for (path in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = TextView(this).apply {
                text = File(path).name.ifEmpty { path }
                textSize = 15f
                setTextColor(0xFF0A5AC2.toInt())
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { openWorkspace(File(path)) }
            }
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(
                btn("移除") {
                    Workspace.forget(this, path)
                    refreshRecents()
                },
            )
            recentBox.addView(row)
        }
    }

    // ---------- 权限 ----------

    private fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun requestAllFiles() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
                REQ_STORAGE,
            )
            return
        }
        // 先跳本 App 的授权开关；部分 ROM 没有这个 action，回落到全局列表页
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "本 App 授权页缺失，回落到全局列表", e)
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: ActivityNotFoundException) {
                Log.e(TAG, "系统没有全盘文件权限页", e2)
                alert("无法打开授权页", "请手动到 系统设置 → 应用 → UniReader → 权限 里允许「所有文件访问」。")
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            Log.i(TAG, "存储权限结果：${grantResults.joinToString()}")
            refresh()
        }
    }

    // ---------- 选工作区 ----------

    private fun onPickWorkspace() {
        if (!hasAllFiles()) {
            alert(
                "需要所有文件访问权限",
                "打开工作区要直接读写 .unrd 文件夹里的 SQLite 库，系统的文件选择器给不了真实路径，" +
                    "所以必须先授予「所有文件访问权限」。",
            )
            return
        }
        browse(Environment.getExternalStorageDirectory())
    }

    /**
     * 极简目录浏览器。为什么不用系统的 `ACTION_OPEN_DOCUMENT_TREE`：它返回 SAF 的 tree Uri，
     * 而裸 `SQLiteDatabase` 要真实文件路径（`content://` 打不开），把 Uri 反推成路径又是各家 ROM
     * 各不相同的猜谜游戏。既然已经要了全盘权限，直接用 `File` 列目录最可预期。
     */
    private fun browse(start: File) {
        var cur = start
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val dlg = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton("取消", null)
            .setPositiveButton("选当前目录", null)   // 拦截：不关窗，交给 openWorkspace 判
            .create()

        fun render() {
            dlg.setTitle(cur.absolutePath)
            list.removeAllViews()
            cur.parentFile?.let { parent ->
                list.addView(
                    row("⬆  上一级") {
                        cur = parent
                        render()
                    },
                )
            }
            val kids = cur.listFiles()
            if (kids == null) {
                list.addView(hint("无法读取此目录（没有权限，或不是真实目录）"))
                return
            }
            val dirs = kids.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            if (dirs.isEmpty()) list.addView(hint("（没有子文件夹）"))
            for (d in dirs) {
                val isWs = Workspace.looksLikeWorkspace(d)
                list.addView(
                    row(if (isWs) "📘  ${d.name}" else "📁  ${d.name}") {
                        // .unrd 点进去没意义（里面只有 UniReader/ 和 PDFs/），直接当选中处理
                        if (isWs) {
                            dlg.dismiss()
                            openWorkspace(d)
                        } else {
                            cur = d
                            render()
                        }
                    },
                )
            }
        }

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dlg.dismiss()
                openWorkspace(cur)
            }
        }
        render()
        dlg.show()
    }

    private fun row(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(0xFF1A1A1A.toInt())
        setPadding(0, dp(12), 0, dp(12))
        setOnClickListener { onClick() }
    }

    private fun openWorkspace(dir: File) {
        Log.i(TAG, "尝试打开工作区：${dir.absolutePath}")
        when (val c = Workspace.check(dir)) {
            is Workspace.Check.Bad -> {
                Workspace.forget(this, dir.absolutePath)   // 失效的最近项别再留着
                refreshRecents()
                alert("打不开这个工作区", c.reason)
            }
            is Workspace.Check.OK -> {
                Workspace.remember(this, dir.absolutePath)
                refreshRecents()
                showLibrary(c)
            }
        }
    }

    /**
     * 打开库把内容读出来。M1 只把读到的东西摆明（M2 起换成真正的书库列表 + 阅读界面）——
     * 但这一步是真读：`LibraryStore` 走的就是后续所有功能要用的那条路，读不出来当场就暴露。
     */
    private fun showLibrary(c: Workspace.Check.OK) {
        val report = try {
            LibraryStore.open(c.dir, readOnly = c.readOnly).use { store ->
                val docs = store.allDocuments()
                buildString {
                    append("工作区：${store.workspaceName().ifEmpty { c.dir.name }}")
                    if (c.readOnly) append("（只读）")
                    append("\nschema v${store.meta("schema_version")}")
                    if (c.walBytes > 0) append("　已合并 ${c.walBytes / 1024}KB WAL")
                    append("\n\n${docs.size} 个文档：\n")
                    for (d in docs) {
                        val pdf = Workspace.firstOpenablePdf(c.dir, store, d.id)
                        append("· ${d.title}\n")
                        append("  ${d.pageCount} 页")
                        append("　进度 第 ${d.readPage + 1} 页 ${(d.readFrac * 100).toInt()}%")
                        append("　缩放 ${d.readZoom}\n")
                        append("  笔迹 ${store.noteCount(d.id, NoteKind.INK)} 条")
                        append("　注解 ${store.noteCount(d.id, NoteKind.TEXT)} 条")
                        append("　高亮 ${store.noteCount(d.id, NoteKind.HIGHLIGHT)} 条\n")
                        // 解码一遍：条数对不上就是 payload 有坏的，比 M2 画不出来时再查便宜
                        val strokes = store.strokes(d.id)
                        val brushes = strokes.groupingBy { brushName(it.pen.brush) }.eachCount()
                        append("  解码 ${strokes.size} 条")
                        if (strokes.isNotEmpty()) {
                            append("（${brushes.entries.joinToString("，") { "${it.key}×${it.value}" }}）")
                            append("　共 ${strokes.sumOf { it.pts.size }} 点")
                        }
                        append("\n")
                        val layers = store.inkLayers(d.id)
                        append("  图层 ${layers.size} 个：")
                        append(layers.joinToString("，") { "${it.name}(${it.colorKey}${if (it.visible) "" else "·隐藏"})" })
                        append("\n")
                        append("  PDF：${pdf?.name ?: "路径失效（需在 Mac 上把文件拷进工作区）"}\n")
                    }
                    append("\n阅读界面与手写是 M2/M3 的内容，本版（M1 数据层）读到这一步。")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开库失败", e)
            "打开 ${Workspace.DB_REL} 失败：\n${e.message}\n\n" +
                "若工作区在 FAT32/exFAT 的 U 盘上，WAL 可能建不起来——先拷到内部存储再试。"
        }
        AlertDialog.Builder(this)
            .setTitle("工作区已打开")
            .setMessage(report)
            .setPositiveButton("好", null)
            .setNeutralButton("打开第一个文档") { _, _ ->
                // M2 中间态：先证明 Pdfium 这条路通（尺寸口径 + 出图），阅读界面随后
                val pdf = LibraryStore.open(c.dir, readOnly = true).use { s ->
                    s.allDocuments().firstOrNull()?.let { Workspace.firstOpenablePdf(c.dir, s, it.id) }
                }
                if (pdf == null) alert("没有可打开的 PDF", "文档的 location 都解析不到本机文件。")
                else previewPdf(c.dir, pdf)
            }
            .show()
    }

    /**
     * M2 中间验证：真开一次 PDF——取全页尺寸表（按 Mac 的 effectiveBox 口径）、打进 logcat 供
     * §9.1 逐页比对，再渲一页出来看。M2 收尾时这段会被真正的阅读界面取代。
     */
    private fun previewPdf(workspaceDir: File, pdf: File) {
        Log.i(TAG, "预览 PDF：${pdf.name}")
        val src = try {
            PdfSource(this, pdf)
        } catch (e: Exception) {
            Log.e(TAG, "Pdfium 打开失败", e)
            alert("打不开 PDF", "${pdf.name}\n${e.message}")
            return
        }
        src.logPageSizes()
        val ratios = src.pageSizes.map { if (it[0] > 0f) it[1] / it[0] else 0f }
        val head = buildString {
            append("${pdf.name}\n${src.pageCount} 页　缓存上限 ${PdfSource.defaultCacheBytes() / 1024 / 1024}MB\n")
            append("第 1 页 ${"%.2f".format(src.pageSizes[0][0])}×${"%.2f".format(src.pageSizes[0][1])} pt")
            append("　宽高比 ${"%.6f".format(ratios[0])}\n")
            val uniform = ratios.all { abs(it - ratios[0]) < 1e-6f }
            append(if (uniform) "全部 ${src.pageCount} 页宽高比一致\n" else "各页宽高比不一（拼页/混排文档）\n")
            append("\n尺寸表已打进 logcat（PAGESIZE 行），与 tools/dump-page-sizes.swift 的输出逐行 diff 即 §9.1 验收。")
        }

        // 渲第一页（在后台线程，回调切回主线程）
        val img = ImageView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(hint(head))
            addView(img, LinearLayout.LayoutParams(-1, dp(420)))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("PDF 已打开")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("好", null)
            .create()
        dlg.setOnDismissListener { src.close() }
        dlg.show()
        src.request(0, dp(360)) { bmp ->
            runOnUiThread {
                if (bmp == null) {
                    Log.e(TAG, "第 1 页渲染返回 null")
                    img.visibility = View.GONE
                } else {
                    Log.i(TAG, "第 1 页位图 ${bmp.width}×${bmp.height}（${bmp.byteCount / 1024}KB）")
                    img.setImageBitmap(bmp)
                    img.adjustViewBounds = true
                }
            }
        }
    }

    private fun alert(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("好", null)
            .show()
    }
}
