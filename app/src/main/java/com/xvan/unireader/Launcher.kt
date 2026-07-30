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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xvan.unireader.local.LibraryActivity
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.pad.PadActivity
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.onSystemBarInsets
import com.xvan.unireader.shared.runInBackground
import java.io.File

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
    private lateinit var permBtn: TextView
    private lateinit var permCard: LinearLayout
    private lateinit var recentBox: LinearLayout

    /** 「正在打开…」。持有它是为了在 [onDestroy] 里收掉，否则校验没回来就退出会 leak window */
    private var busyDlg: AlertDialog? = null

    /** 目录浏览器的当前请求令牌：用户点得比慢卷读得快，回来的旧结果要丢掉 */
    private var browseToken: Any? = null

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

    /**
     * 两种模式各是一张卡片：图标 + 标题 + 一句解释 + 主操作，而不是一串"小标题 + 灰字 + 灰按钮"。
     * 两张卡等重——它们是并列的两条路，不该有一条看起来像附属功能。
     */
    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }

        col.addView(Ui.title(this, "UniReader", 30f))
        col.addView(Ui.body(this, "版本 ${versionName()}").apply { setPadding(0, dp(4), 0, 0) })

        // —— 权限：没授权时才是一张显眼的卡，授权了就缩成一行小字 ——
        permText = Ui.body(this, "")
        permBtn = Ui.button(this, "去授权", filled = true) { requestAllFiles() }
        permCard = Ui.card(this).apply {
            addView(permText)
            addView(Ui.spacer(this@Launcher, 12))
            addView(permBtn)
        }
        col.addView(Ui.spacer(this, 20))
        col.addView(permCard)

        // —— 模式1 ——
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.spacer(this, 16))
        col.addView(
            modeCard(
                R.drawable.ic_folder,
                "打开工作区",
                "本机独立：直接读 .unrd 里的 library.sqlite 与 PDFs/，不需要开着 Mac。",
                "选择 .unrd 文件夹…",
            ) { onPickWorkspace() }.apply { addView(recentBox) },
        )

        // —— 模式2 ——
        col.addView(Ui.spacer(this, 12))
        col.addView(
            modeCard(
                R.drawable.ic_tablet,
                "连 Mac 当输入板",
                "平板只管采集笔迹，页面与笔迹的真源在 Mac 上。",
                "进入输入板",
            ) {
                Log.i(TAG, "进入模式2 输入板")
                startActivity(Intent(this, PadActivity::class.java))
            },
        )

        // —— 单写者提醒（§9.2：工作区没有任何加锁/同步机制） ——
        col.addView(Ui.spacer(this, 24))
        col.addView(
            Ui.body(
                this,
                "同一个工作区同一时间只能一端打开：平板在用时别在 Mac 上开同一个工作区，" +
                    "否则两边的写入会互相覆盖。搬运时把整个 .unrd 文件夹一起拷（含 -wal/-shm），" +
                    "放在云盘同步目录上尤其危险。",
            ).apply { textSize = 12f },
        )

        val scroll = ScrollView(this).apply {
            addView(col)
            setBackgroundColor(Ui.surface(this@Launcher))
        }
        setContentView(scroll)
        scroll.onSystemBarInsets { top, bottom -> scroll.setPadding(0, top, 0, bottom) }
        refresh()
    }

    /** 一张模式卡：顶上一行「图标 + 标题」，一句解释，一个主操作按钮 */
    private fun modeCard(
        icon: Int,
        heading: String,
        desc: String,
        action: String,
        onAction: () -> Unit,
    ): LinearLayout = Ui.card(this, 18).apply {
        addView(
            LinearLayout(this@Launcher).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    android.widget.ImageView(this@Launcher).apply {
                        setImageResource(icon)
                        imageTintList =
                            android.content.res.ColorStateList.valueOf(Ui.accent(this@Launcher))
                    },
                    LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) },
                )
                addView(Ui.title(this@Launcher, heading, 18f))
            },
        )
        addView(Ui.body(this@Launcher, desc).apply { setPadding(0, dp(8), 0, dp(14)) })
        addView(Ui.button(this@Launcher, action, filled = true, onClick = onAction))
    }

    /**
     * 按钮一律走 [Ui.button]：Material 主题默认 `textAllCaps=true`，会把「选择 .unrd 文件夹…」
     * 显示成「选择 .UNRD 文件夹…」——而 `.unrd` 是要用户在文件夹名里认的后缀，大写就对不上了。
     * （自绘的 TextView 按钮不受那条影响，这里留个说明免得有人改回 `Button`。）
     */
    private fun btn(label: String, onClick: () -> Unit) = Ui.button(this, label, onClick = onClick)

    private fun hint(s: String) = Ui.body(this, s).apply { setPadding(0, 0, 0, dp(8)) }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        "?"
    }

    private fun refresh() {
        val ok = hasAllFiles()
        Log.i(TAG, "刷新：全盘文件权限=$ok sdk=${Build.VERSION.SDK_INT}")
        permText.text =
            if (ok) "已获得所有文件访问权限"
            else "尚未获得「所有文件访问权限」——打开工作区需要它（模式2 输入板不需要）。" +
                "小米/HyperOS 等国产 ROM 需要在系统设置里单独允许。"
        // 授权后这张卡就不该再占一整块：缩成一行小字，别一直提醒一件已经办完的事
        permBtn.visibility = if (ok) View.GONE else View.VISIBLE
        permCard.background = if (ok) null else Ui.round(Ui.container(this), 16, this)
        val p = if (ok) 0 else dp(16)
        permCard.setPadding(p, p, p, p)
        permText.setTextColor(if (ok) Ui.onVariant(this) else Ui.onSurface(this))
        refreshRecents()
    }

    /** 最近打开：整行可点进书库，右侧一个删除图标（原先是个跟主操作一样重的「移除」按钮） */
    private fun refreshRecents() {
        recentBox.removeAllViews()
        val list = Workspace.recents(this)
        if (list.isEmpty()) return
        recentBox.addView(Ui.sectionTitle(this, "最近打开"))
        for (path in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = Ui.row(this) { openWorkspace(File(path)) }.apply {
                addView(Ui.title(this@Launcher, File(path).name.ifEmpty { path }, 15f))
                addView(
                    Ui.body(this@Launcher, File(path).parent ?: path).apply { textSize = 12f },
                )
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(
                Ui.iconButton(this, R.drawable.ic_delete, "从最近打开中移除", Ui.onVariant(this)) {
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
     *
     * 列目录走后台（§9.5）：`listFiles` 加上逐个 `isDirectory` 的 stat，在 U 盘/同步盘上是秒级，
     * 而用户是一级一级点进去的——每一级都卡一下，整个选目录过程就在反复触发 ANR 观察窗。
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
            val waiting = hint("正在读取目录…")
            list.addView(waiting)
            val dir = cur
            val token = Any()
            browseToken = token
            runInBackground(
                what = "列目录 ${dir.name}",
                // 过滤与排序也在后台：`isDirectory` 是每个条目一次 stat，才是慢的那部分
                work = { dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } },
                ok = { dirs ->
                    // 用户可能已经点进别的目录、或把窗关了——旧结果直接丢
                    if (browseToken === token && dlg.isShowing) fill(list, waiting, dirs, dlg) { d ->
                        cur = d
                        render()
                    }
                },
                fail = {
                    if (browseToken === token && dlg.isShowing) {
                        list.removeView(waiting)
                        list.addView(hint("读取目录失败：${it.message}"))
                    }
                },
            )
        }

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dlg.dismiss()
                openWorkspace(cur)
            }
        }
        dlg.setOnDismissListener { browseToken = null }
        render()
        dlg.show()
    }

    /** 把后台列出的子目录填进浏览器（`.unrd` 用书本图标，点它等于选中） */
    private fun fill(
        list: LinearLayout,
        waiting: View,
        dirs: List<File>?,
        dlg: AlertDialog,
        onEnter: (File) -> Unit,
    ) {
        list.removeView(waiting)
        if (dirs == null) {
            list.addView(hint("无法读取此目录（没有权限，或不是真实目录）"))
            return
        }
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
                        onEnter(d)
                    }
                },
            )
        }
    }

    private fun row(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(0xFF1A1A1A.toInt())
        setPadding(0, dp(12), 0, dp(12))
        setOnClickListener { onClick() }
    }

    /**
     * 校验走后台（§9.5）：`Workspace.check` 那几个 `exists/isFile/canRead/length` 在 `/sdcard`
     * （FUSE）冷缓存下实测 2.1 秒，U 盘上更久。期间压一个不可取消的「正在打开…」——
     * 不压的话界面看着没反应，用户会连点，连点就会开出两个 [LibraryActivity]。
     */
    private fun openWorkspace(dir: File) {
        if (busyDlg != null) return
        Log.i(TAG, "尝试打开工作区：${dir.absolutePath}")
        busyDlg = busy("正在打开 ${dir.name}…")
        runInBackground(
            what = "校验工作区 ${dir.name}",
            work = { Workspace.check(dir) },
            ok = { c ->
                dismissBusy()
                when (c) {
                    is Workspace.Check.Bad -> {
                        Workspace.forget(this, dir.absolutePath)   // 失效的最近项别再留着
                        refreshRecents()
                        alert("打不开这个工作区", c.reason)
                    }
                    is Workspace.Check.OK -> {
                        Workspace.remember(this, dir.absolutePath)
                        refreshRecents()
                        if (c.readOnly) {
                            Log.w(TAG, "库文件只读：${c.db.absolutePath}")
                        }
                        LibraryActivity.start(this, c.dir)
                    }
                }
            },
            fail = {
                dismissBusy()
                alert("打不开这个工作区", "校验时出错：${it.message}")
            },
        )
    }

    private fun busy(msg: String): AlertDialog {
        val tv = TextView(this).apply {
            text = msg
            textSize = 15f
            setTextColor(0xFF1A1A1A.toInt())
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        return AlertDialog.Builder(this).setView(tv).setCancelable(false).create()
            .also { it.show() }
    }

    private fun dismissBusy() {
        busyDlg?.dismiss()
        busyDlg = null
    }

    override fun onDestroy() {
        // 校验还没回来就退出：窗口先收掉，否则 WindowLeaked
        busyDlg?.dismiss()
        busyDlg = null
        super.onDestroy()
    }

    private fun alert(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("好", null)
            .show()
    }
}
