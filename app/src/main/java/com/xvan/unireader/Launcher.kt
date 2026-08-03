package com.xvan.unireader

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xvan.unireader.local.LibraryActivity
import com.xvan.unireader.local.StorageScan
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.pad.PadActivity
import com.xvan.unireader.shared.Bg
import com.xvan.unireader.shared.PadPanels
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.onSystemBarInsets
import com.xvan.unireader.shared.showAlert
import com.xvan.unireader.shared.runInBackground
import java.io.File

/**
 * 启动页：同一个 App 的两种模式二选一（ANDROID-STANDALONE-PLAN §3）。
 * - **打开工作区**（模式1 独立版）：本机直接读 `.unrd/UniReader/library.sqlite` + 本地渲染 PDF。
 *   需要「所有文件访问权限」，这里负责引导；文档列表/阅读/手写是 M1 起的事。
 * - **连 Mac 当输入板**（模式2）：进 [PadActivity]，即已验收的 WS+UDP 链路。
 *
 * 找工作区有三条路（2026-08-03 用户定的口径，见 §9.8）：
 * 1. **最近打开**——开过一次就在这儿；
 * 2. **逐级浏览**——**先列存储卷**（内部存储 / SD 卡 / U 盘），再点进去，
 *    **只有 `.unrd` 结尾的文件夹能选中**，普通文件夹只能点进去继续找；
 * 3. **扫描**——拿到权限后自动扫一遍全部存储卷，之后可手动重扫；也可以在浏览到的某个目录下只扫那一支。
 *
 * 授权状态、选中路径、校验结果**一律打日志**（tag `UniReader/Launcher`、`UniReader/WS`、
 * `UniReader/Scan`）：「授权了但还是打不开」「插了 U 盘却看不见」这类问题不打点就只能靠猜。
 */
class Launcher : Activity() {

    companion object {
        const val TAG = "UniReader/Launcher"
        const val REQ_STORAGE = 71

        /** 扫描进度回主线程的最小间隔：一秒几百个目录，每个都 post 会把 looper 塞满 */
        const val TICK_MS = 120L
    }

    private lateinit var permText: TextView
    private lateinit var permBtn: TextView
    private lateinit var permCard: LinearLayout
    private lateinit var recentBox: LinearLayout
    private lateinit var scanBox: LinearLayout
    private lateinit var scanBtn: TextView

    /** 「正在打开…」。持有它是为了在 [onDestroy] 里收掉，否则校验没回来就退出会 leak window */
    private var busyDlg: AlertDialog? = null

    /** 目录浏览器的当前请求令牌：用户点得比慢卷读得快，回来的旧结果要丢掉 */
    private var browseToken: Any? = null

    /** 扫描是全 App 唯一一份（同时开两个只会互相抢慢卷的 I/O） */
    private var scanning = false
    private var scanCancel: StorageScan.Cancel? = null

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
        scanBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scanBtn = Ui.button(this, "扫描存储查找 .unrd") { startScan(auto = false) }
        col.addView(Ui.spacer(this, 16))
        col.addView(
            modeCard(
                R.drawable.ic_folder,
                "打开工作区",
                "本机独立：直接读 .unrd 里的 library.sqlite 与 PDFs/，不需要开着 Mac。" +
                    "内部存储、SD 卡、OTG U 盘上的工作区都能开。",
                "选择 .unrd 文件夹…",
            ) { onPickWorkspace() }.apply {
                addView(Ui.spacer(this@Launcher, 8))
                addView(scanBtn)
                addView(scanBox)
                addView(recentBox)
            },
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

    /**
     * 一张模式卡：顶上一行「图标 + 标题」，一句解释，一个主操作按钮。
     *
     * 按钮一律走 [Ui.button]：Material 主题默认 `textAllCaps=true`，会把「选择 .unrd 文件夹…」
     * 显示成「选择 .UNRD 文件夹…」——而 `.unrd` 是要用户在文件夹名里认的后缀，大写就对不上了。
     * （自绘的 TextView 按钮不受那条影响，这里留个说明免得有人改回 `Button`。）
     */
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
                    ImageView(this@Launcher).apply {
                        setImageResource(icon)
                        imageTintList = ColorStateList.valueOf(Ui.accent(this@Launcher))
                    },
                    LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) },
                )
                addView(Ui.title(this@Launcher, heading, 18f))
            },
        )
        addView(Ui.body(this@Launcher, desc).apply { setPadding(0, dp(8), 0, dp(14)) })
        addView(Ui.button(this@Launcher, action, filled = true, onClick = onAction))
    }

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
        scanBtn.visibility = if (ok) View.VISIBLE else View.GONE
        scanBtn.text = if (Workspace.scanDone(this)) "重新扫描存储" else "扫描存储查找 .unrd"
        refreshRecents()
        refreshScan()
        maybeAutoScan(ok)
    }

    /** 最近打开：整行可点进书库，右侧一个删除图标（原先是个跟主操作一样重的「移除」按钮） */
    private fun refreshRecents() {
        recentBox.removeAllViews()
        val list = Workspace.recents(this)
        if (list.isEmpty()) return
        recentBox.addView(Ui.sectionTitle(this, "最近打开"))
        for (path in list) {
            val dir = File(path)
            recentBox.addView(
                twoLineRow(
                    R.drawable.ic_doc,
                    Ui.accent(this),
                    dir.name.ifEmpty { path },
                    dir.parent ?: path,
                    trailing = Ui.iconButton(
                        this,
                        R.drawable.ic_delete,
                        "从最近打开中移除",
                        Ui.onVariant(this),
                    ) {
                        Workspace.forget(this, path)
                        refreshRecents()
                        refreshScan()   // 从最近里移掉之后，扫描结果那栏该把它显示回来
                    },
                ) { openWorkspace(dir) },
            )
        }
    }

    /**
     * 扫描到的工作区。已经在「最近打开」里的不重复列一行——同一个库出现两遍只会让人以为有两个库。
     * 「没找到」也要说出来：静默空白会被当成"扫描坏了"。
     */
    private fun refreshScan() {
        scanBox.removeAllViews()
        if (!hasAllFiles()) return
        if (scanning) {
            scanBox.addView(Ui.sectionTitle(this, "正在扫描存储，查找 .unrd 工作区…"))
            return
        }
        val all = Workspace.scanned(this)
        val recents = Workspace.recents(this).toSet()
        val hits = all.filter { it !in recents }
        if (hits.isEmpty()) {
            if (Workspace.scanDone(this) && all.isEmpty()) {
                scanBox.addView(Ui.sectionTitle(this, "上次扫描没有找到 .unrd 工作区"))
            }
            return
        }
        scanBox.addView(Ui.sectionTitle(this, "扫描到的工作区"))
        for (path in hits) {
            val dir = File(path)
            scanBox.addView(
                twoLineRow(R.drawable.ic_doc, Ui.accent(this), dir.name.ifEmpty { path }, dir.parent ?: path) {
                    openWorkspace(dir)
                },
            )
        }
    }

    /** 图标 + 主行 + 灰色次行的一行（最近打开 / 扫描结果 / 存储卷共用） */
    private fun twoLineRow(
        icon: Int,
        tint: Int,
        line1: String,
        line2: String,
        trailing: View? = null,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        background = Ui.rippleOver(this@Launcher, null, Ui.RADIUS, Ui.onSurface(this@Launcher))
        setPadding(dp(8), dp(10), dp(8), dp(10))
        setOnClickListener { onClick() }
        addView(
            ImageView(this@Launcher).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(tint)
            },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) },
        )
        addView(
            LinearLayout(this@Launcher).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.title(this@Launcher, line1, 15f))
                addView(
                    Ui.body(this@Launcher, line2).apply {
                        textSize = 12f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    },
                )
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        if (trailing != null) addView(trailing)
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

    private fun needPermission(): Boolean {
        if (hasAllFiles()) return false
        alert(
            "需要所有文件访问权限",
            "打开工作区要直接读写 .unrd 文件夹里的 SQLite 库，系统的文件选择器给不了真实路径，" +
                "所以必须先授予「所有文件访问权限」。SD 卡 / U 盘上的工作区同样靠它。",
        )
        return true
    }

    // ---------- 选工作区 ----------

    private fun onPickWorkspace() {
        if (needPermission()) return
        browse()
    }

    /**
     * 极简目录浏览器。为什么不用系统的 `ACTION_OPEN_DOCUMENT_TREE`：它返回 SAF 的 tree Uri，
     * 而裸 `SQLiteDatabase` 要真实文件路径（`content://` 打不开），把 Uri 反推成路径又是各家 ROM
     * 各不相同的猜谜游戏。既然已经要了全盘权限，直接用 `File` 列目录最可预期。
     *
     * 两条口径（2026-08-03 用户定）：
     * - **第一层是存储卷**（[StorageScan.volumes]），不再直接落在内置共享存储里——外部存储上的
     *   工作区才是常态，靠"一级级往上点"去够 `/storage/XXXX-XXXX` 既不直观、部分 ROM 还够不到；
     * - **只有 `.unrd` 能选中**：普通目录点进去继续找，原先那颗"选当前目录"按钮已去掉——
     *   它让人以为随便哪个文件夹都能当工作区，选错了只会换来一句"这里没有 library.sqlite"。
     *
     * 列目录走后台（§9.5）：`listFiles` 加上逐个 `isDirectory` 的 stat，在 U 盘/同步盘上是秒级，
     * 而用户是一级一级点进去的——每一级都卡一下，整个选目录过程就在反复触发 ANR 观察窗。
     */
    private fun browse() {
        var cur: File? = null                                   // null = 停在存储卷这一层
        var vols: List<StorageScan.Volume> = emptyList()
        val pathText = Ui.body(this, "").apply { textSize = 12f }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sheet = Sheet(this).title("选择工作区（.unrd）")
        sheet.content(pathText)
        sheet.content(list)
        sheet.content(
            Ui.body(this, "只有以 .unrd 结尾的文件夹才是工作区，点它就是打开；普通文件夹点进去继续找。")
                .apply {
                    textSize = 12f
                    setPadding(0, dp(12), 0, 0)
                },
        )
        sheet.action("取消")
        // 在当前位置直接开扫，省得一级级翻。停在卷列表时＝扫全部卷
        sheet.action("在这里扫描", primary = true) {
            val roots = cur?.let { listOf(it) } ?: vols.map { it.dir }
            startScan(auto = false, roots = roots.ifEmpty { null })
        }
        val dlg = sheet.show()
        dlg.setOnDismissListener { browseToken = null }

        fun render() {
            list.removeAllViews()
            val dir = cur
            val token = Any()
            browseToken = token
            if (dir == null) {
                pathText.text = "选择一块存储"
                val waiting = hint("正在查找存储卷…")
                list.addView(waiting)
                runInBackground(
                    what = "枚举存储卷",
                    work = { StorageScan.volumes(this) },
                    ok = { vs ->
                        if (browseToken !== token || !dlg.isShowing) return@runInBackground
                        vols = vs
                        list.removeView(waiting)
                        if (vs.isEmpty()) {
                            list.addView(hint("没有找到可读的存储卷——确认已授予「所有文件访问权限」，U 盘是否已挂载"))
                            return@runInBackground
                        }
                        for (v in vs) {
                            list.addView(
                                twoLineRow(R.drawable.ic_folder, Ui.onVariant(this), v.label, v.dir.absolutePath) {
                                    cur = v.dir
                                    render()
                                },
                            )
                        }
                    },
                    fail = {
                        if (browseToken === token && dlg.isShowing) {
                            list.removeView(waiting)
                            list.addView(hint("枚举存储卷失败：${it.message}"))
                        }
                    },
                )
                return
            }

            pathText.text = dir.absolutePath
            val atRoot = vols.any { it.dir.absolutePath == dir.absolutePath }
            list.addView(
                PadPanels.iconRow(
                    this,
                    R.drawable.ic_chevron_up,
                    if (atRoot) "存储卷列表" else "上一级",
                    Ui.onVariant(this),
                ) {
                    cur = if (atRoot) null else dir.parentFile
                    render()
                },
            )
            val waiting = hint("正在读取目录…")
            list.addView(waiting)
            runInBackground(
                what = "列目录 ${dir.name}",
                // 过滤与排序也在后台：`isDirectory` 是每个条目一次 stat，才是慢的那部分
                work = {
                    Listing(
                        dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() },
                        stray = !Workspace.looksLikeWorkspace(dir) && File(dir, Workspace.DB_REL).isFile,
                    )
                },
                ok = { l ->
                    // 用户可能已经点进别的目录、或把窗关了——旧结果直接丢
                    if (browseToken === token && dlg.isShowing) fill(list, waiting, l, dlg) { d ->
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

        render()
    }

    /**
     * @param stray 这个目录本身就有 `UniReader/library.sqlite`，但名字不是 `.unrd`——
     *   用户十有八九是在这儿找他的库，得当场说清为什么点不了。
     */
    private class Listing(val dirs: List<File>?, val stray: Boolean)

    /** 把后台列出的子目录填进浏览器（`.unrd` 用 accent 书本图标 + 一个「打开」，点它等于选中） */
    private fun fill(
        list: LinearLayout,
        waiting: View,
        l: Listing,
        dlg: AlertDialog,
        onEnter: (File) -> Unit,
    ) {
        list.removeView(waiting)
        if (l.stray) {
            list.addView(
                hint("这个文件夹里有 ${Workspace.DB_REL}，但名字不是 .unrd 结尾——先把它改名成「xxx.unrd」再来打开。"),
            )
        }
        if (l.dirs == null) {
            list.addView(hint("无法读取此目录（没有权限，或不是真实目录）"))
            return
        }
        if (l.dirs.isEmpty()) list.addView(hint("（没有子文件夹）"))
        for (d in l.dirs) {
            val isWs = Workspace.looksLikeWorkspace(d)
            // 工作区用 accent 色的书本图标挑出来：一屏几十个文件夹，靠 emoji 分辨太费眼
            list.addView(
                PadPanels.iconRow(
                    this,
                    if (isWs) R.drawable.ic_doc else R.drawable.ic_folder,
                    d.name,
                    if (isWs) Ui.accent(this) else Ui.onVariant(this),
                    trailing = if (isWs) {
                        Ui.body(this, "打开").apply {
                            setTextColor(Ui.accent(this@Launcher))
                            setPadding(dp(8), 0, dp(4), 0)
                        }
                    } else {
                        ImageView(this).apply {
                            setImageResource(R.drawable.ic_chevron_right)
                            imageTintList = ColorStateList.valueOf(Ui.outline(this@Launcher))
                            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                        }
                    },
                ) {
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

    // ---------- 扫描 ----------

    /**
     * 刚拿到权限、且从没扫过 → 自动扫一遍（用户 2026-08-03：「获取权限后自动扫描」）。
     *
     * 自动那次**不弹窗**：用户没点任何东西，凭空盖一个模态上来是打扰；结果直接落到卡片里的
     * 「扫描到的工作区」。之后不再自动重扫（慢卷上一次几十秒），要刷新按「重新扫描存储」。
     */
    private fun maybeAutoScan(permOk: Boolean) {
        if (!permOk || scanning || Workspace.scanDone(this)) return
        Log.i(TAG, "已授权且从未扫描过 → 自动扫一遍全部存储卷")
        startScan(auto = true)
    }

    /**
     * @param auto true = 后台静默扫（结果进卡片）；false = 用户点的，开一个实时弹层边扫边列结果。
     * @param roots null = 全部存储卷；非 null = 只扫这几支（浏览器里「在这里扫描」）。
     */
    private fun startScan(auto: Boolean, roots: List<File>? = null) {
        if (scanning) {
            Log.i(TAG, "已有扫描在跑，忽略这次")
            return
        }
        if (!auto && needPermission()) return
        val cancel = StorageScan.Cancel()
        scanCancel = cancel
        scanning = true
        val ui = if (auto) null else ScanSheet(cancel)
        val hits = ArrayList<String>()          // 只在主线程上动
        val full = roots == null
        refreshScan()
        var tick = 0L
        runInBackground(
            what = "扫描 .unrd",
            work = {
                val rs = roots ?: StorageScan.volumes(this).map { it.dir }
                StorageScan.scan(
                    rs,
                    cancel,
                    onFound = { f ->
                        Bg.main.post {
                            hits.add(f.absolutePath)
                            if (!isFinishing) ui?.onFound(f)
                        }
                    },
                    onDir = { d ->
                        // 节流：一秒几百个目录，每个都 post 会把主线程 looper 塞满
                        val now = SystemClock.uptimeMillis()
                        if (ui != null && now - tick > TICK_MS) {
                            tick = now
                            Bg.main.post { if (!isFinishing) ui.onDir(d) }
                        }
                    },
                )
            },
            ok = { r ->
                scanning = false
                scanCancel = null
                // 全盘扫＝这就是全部结果；只扫一支＝并进已有的（别把别处扫到的抹掉）
                val saved = if (full) hits else (Workspace.scanned(this) + hits).distinct()
                Workspace.saveScanned(this, saved, markDone = full)
                ui?.onDone(r)
                refreshScan()
                scanBtn.text = "重新扫描存储"
            },
            fail = { e ->
                scanning = false
                scanCancel = null
                refreshScan()
                ui?.onFailed(e)
                if (auto) Log.w(TAG, "自动扫描失败（不打扰用户，卡片保持原样）", e)
            },
        )
    }

    /**
     * 手动扫描时的实时弹层：一行状态 + 边扫边冒出来的结果，找到就能点开，不必等扫完。
     * **关掉窗口＝停止扫描**（已找到的照样留着）——扫描在慢卷上要几十秒，不给停就只能干等。
     */
    private inner class ScanSheet(cancel: StorageScan.Cancel) {

        private val status = Ui.body(this@Launcher, "正在查找存储卷…").apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        private val results = LinearLayout(this@Launcher).apply {
            orientation = LinearLayout.VERTICAL
        }
        private var count = 0
        private val dialog: AlertDialog

        init {
            val sheet = Sheet(this@Launcher).title("扫描 .unrd 工作区")
            sheet.content(status)
            sheet.content(results)
            sheet.action("关闭", primary = true)
            dialog = sheet.show()
            dialog.setOnDismissListener {
                if (scanning) Log.i(TAG, "用户关掉扫描窗 → 停止扫描")
                cancel.stop()
            }
        }

        fun onDir(dir: File) {
            status.text = (if (count > 0) "已找到 $count 个 · " else "") + "正在看：${dir.absolutePath}"
        }

        fun onFound(dir: File) {
            count++
            results.addView(
                twoLineRow(R.drawable.ic_doc, Ui.accent(this@Launcher), dir.name, dir.parent ?: "") {
                    dialog.dismiss()
                    openWorkspace(dir)
                },
            )
        }

        fun onDone(r: StorageScan.Result) {
            // 没走完就一定要说出来：「找到 2 个」和「扫到 2 个就没时间了」是两回事
            val tail = r.truncated?.let { "（$it，没扫完；可以进到具体文件夹里再扫）" } ?: ""
            status.text = "扫描完成：找到 ${r.found.size} 个，走过 ${r.dirs} 个文件夹，用时 ${r.ms / 1000}s$tail"
            if (r.found.isEmpty()) {
                results.addView(hint("没有找到 .unrd 文件夹。工作区可能在没扫到的更深处，或者不在这块存储上。"))
            }
        }

        fun onFailed(e: Throwable) {
            status.text = "扫描出错：${e.message}"
        }
    }

    // ---------- 打开 ----------

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
                        refreshScan()
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

    private fun busy(msg: String): AlertDialog = Sheet(this).busy(msg)

    private fun dismissBusy() {
        busyDlg?.dismiss()
        busyDlg = null
    }

    override fun onDestroy() {
        // 校验还没回来就退出：窗口先收掉，否则 WindowLeaked
        busyDlg?.dismiss()
        busyDlg = null
        // 界面都没了还在遍历 U 盘毫无意义，且会一直占着 I/O 线程
        scanCancel?.stop()
        super.onDestroy()
    }

    private fun alert(title: String, msg: String) = showAlert(title, msg)
}
