package com.xvan.unireader.local.mirror

import android.app.Activity
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import com.xvan.unireader.local.FileBrowser
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.local.store.Db
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.runInBackground
import com.xvan.unireader.shared.showAlert
import java.io.File

/**
 * 离线镜像的两条界面流程：**做成离线副本** 与 **同步预览**（方案 `../OFFLINE-MIRROR-PLAN.md` §8）。
 *
 * 放一处而不是散进 `LibraryActivity`：两条流程都要「开连接 → 后台跑 → 关连接 → 刷界面」这一套，
 * 抄两遍必然有一遍忘了关连接（那会让 U 盘拔不掉、WAL 一直挂着）。
 *
 * 🔴 全部 I/O 走 [runInBackground]：拷 PDF 是分钟级，主线程做必 ANR（§9.5 的既有教训）。
 * 界面上一件事一句话说完，长背景走 [Ui.tip]（「界面上不许出现大段技术说明」那条红线）。
 */
object MirrorUi {

    const val TAG = "UniReader/MirrorUi"

    /** 镜像的默认存放位置，与 [MirrorBuilder.defaultParent] 同一处定义 */
    fun defaultParent(): File = MirrorBuilder.defaultParent()

    /** 这个工作区是不是一份离线副本（列表页据此换文案与角标） */
    fun isMirror(store: LibraryStore): Boolean = store.meta(MirrorStore.META_MIRROR_OF) != null

    // ---------- 做成离线副本 ----------

    /**
     * 「做成离线副本」：选存放位置 → 起名 → 后台建 → 报结果。
     *
     * [ws] 是**源工作区**；本流程**另开一条可写连接**（要写 `workspace_id` 与借出记录），
     * 用完即关——不复用调用方那条只读连接。
     */
    fun makeMirror(a: Activity, ws: File, onDone: () -> Unit) {
        FileBrowser(
            a = a,
            mode = FileBrowser.Mode.FOLDER,
            title = "离线副本放在哪",
            tipText = "放内部存储，拔了 U 盘也能看和写。建议 ${defaultParent().absolutePath}",
            primary = "就放这里" to { dir: File?, dlg ->
                if (dir == null) {
                    a.showAlert("还没选到位置", "点进一个文件夹再按")
                } else {
                    dlg.dismiss()
                    askNameThenBuild(a, ws, dir, onDone)
                }
            },
            onPick = { },
        ).show()
    }

    private fun askNameThenBuild(a: Activity, ws: File, parent: File, onDone: () -> Unit) {
        val field = EditText(a).apply {
            setText(ws.name.removeSuffix(".unrd"))
            setTextColor(Ui.onSurface(a))
            setSelectAllOnFocus(true)
        }
        Sheet(a)
            .title("离线副本的名字")
            .content(field)
            .action("取消")
            .action("建立", primary = true) {
                val name = Workspace.sanitizeName(field.text.toString())
                if (name.isEmpty()) a.showAlert("名字不能为空", "换一个再试")
                else build(a, ws, File(parent, "$name.unrd"), onDone)
            }
            .show()
    }

    private fun build(a: Activity, ws: File, dst: File, onDone: () -> Unit) {
        val busy = Sheet(a).busy("正在建立离线副本…")
        a.runInBackground(
            what = "建镜像 ${dst.name}",
            work = {
                // 源库要**可写**：建镜像会补 workspace_id、记一笔借出
                LibraryStore.open(ws, readOnly = false).use { store ->
                    // 首版全带 PDF：安卓端还没有「按书勾选」的界面，而"少带了哪本"这件事用户
                    // 在插不上硬盘的时候才会发现——宁可多占空间。空间不够时 create 会明说。
                    val plan = MirrorBuilder.Plan(
                        documentsWithPDF = store.allDocuments().map { it.id }.toSet(),
                        sourceHint = ws.absolutePath,
                    )
                    MirrorBuilder.create(
                        source = ws, store = store, destination = dst, plan = plan,
                        deviceId = MirrorStore.deviceId(a),
                        resolve = { loc -> Workspace.resolvePdf(ws, loc) },
                        progress = { s, _ -> Log.i(TAG, s) },
                    )
                }
            },
            ok = { r ->
                busy.dismiss()
                a.showAlert("离线副本已建好", "${r.copiedFiles} 本书的 PDF 已复制\n${dst.absolutePath}") { onDone() }
            },
            fail = { e ->
                busy.dismiss()
                a.showAlert("建不了离线副本", e.message ?: e.toString())
            },
        )
    }

    // ---------- 同步预览 ----------

    /**
     * 干跑的结果。**用正经类型而不是一坨 Pair/Triple**：这段要在后台线程算好、跨线程递到主线程，
     * 一旦退化成 `Pair<Any?, String>` 就得靠强转，那种代码改一次错一次。
     */
    sealed class Preview {
        /** 找不到源工作区。[hint] = 「上次见到它在哪」（只是提示，判据始终是 workspace_id） */
        data class SourceMissing(val hint: String) : Preview()
        data class Ready(
            val source: File,
            val plan: MirrorDiff.Plan,
            val titles: Map<String, String>,
            /** `content_hash → 书名`，OCR 与扫描页对齐两条明细用得上（见 `MirrorStore.ocrTitles`） */
            val hashTitles: Map<String, String> = emptyMap(),
        ) : Preview()
    }

    /**
     * 「同步预览」：找源工作区 → 三方合并干跑 → 显示报告。**一个字都不写**（M5 才应用合并）。
     * [mirror] 必须是一份镜像。
     */
    fun syncPreview(a: Activity, mirror: File) {
        val busy = Sheet(a).busy("正在找源工作区…")
        a.runInBackground(
            what = "干跑 ${mirror.name}",
            work = { dryRun(a, mirror) },
            ok = { p ->
                busy.dismiss()
                when (p) {
                    is Preview.SourceMissing -> showNotConnected(a, p.hint)
                    is Preview.Ready -> showReport(a, mirror, p)
                }
            },
            fail = { e ->
                busy.dismiss()
                Log.e(TAG, "干跑失败", e)
                a.showAlert("算不了同步预览", e.message ?: e.toString())
            },
        )
    }

    /** 干跑本体（后台线程）。只读连接，用完即关。 */
    fun dryRun(a: Activity, mirror: File): Preview =
        LibraryStore.open(mirror, readOnly = true).use { mine ->
            val sourceId = mine.meta(MirrorStore.META_MIRROR_OF) ?: error("这不是一份离线副本")
            val src = findSource(a, sourceId)
                ?: return Preview.SourceMissing(mine.meta(MirrorStore.META_MIRROR_SOURCE_HINT).orEmpty())
            Db.open(File(src, Workspace.DB_REL), readOnly = true).use { srcDb ->
                val mineSnap = mine.mirrorSnapshot()
                val theirs = MirrorStore.snapshot(srcDb)
                // 基线走镜像自己那条连接：sync_base 只在镜像库里
                Preview.Ready(
                    src,
                    MirrorDiff.compute(
                        mine.syncBase(), mineSnap, theirs,
                        mine.mirrorOcrKeys(), MirrorStore.ocrKeys(srcDb),
                        mine.mirrorAlignStamps(), MirrorStore.alignStamps(srcDb),
                    ),
                    MirrorStore.titles(mineSnap, theirs),
                    // OCR 与扫描页对齐两条明细都按内容 hash 报书名（见 `MirrorStore.ocrTitles`）
                    MirrorStore.ocrTitles(mineSnap, theirs),
                )
            }
        }

    private fun showNotConnected(a: Activity, hint: String) {
        Sheet(a)
            .title("源工作区没有连接")
            .subtitle(if (hint.isEmpty()) "插上那块盘再试" else "上次见到它在：$hint")
            .action("知道了", primary = true)
            .show()
    }

    private fun showReport(a: Activity, mirror: File, p: Preview.Ready) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        for (line in MirrorReport.summary(p.plan, p.titles, p.hashTitles)) {
            box.addView(Ui.sectionTitle(a, line.text))
            for (d in line.detail) box.addView(Ui.body(a, "· $d"))
        }
        val sheet = Sheet(a)
            .title("同步预览")
            .subtitle(MirrorReport.headline(p.plan))
            .content(box)
            .content(Ui.tip(a, "以上只是预演，什么都没有写入"))
            .action("知道了")
        // 这一步会大批量改用户数据 —— 预览页不直接执行，再点一次才算数
        if (!p.plan.isEmpty) sheet.action("同步…", primary = true) { confirmApply(a, mirror, p) }
        sheet.show()
    }

    private fun confirmApply(a: Activity, mirror: File, p: Preview.Ready) {
        Sheet(a)
            .title("要应用这次合并吗")
            .subtitle("会先把硬盘上的资料库整份备份出来，保留最近 3 份")
            .action("取消")
            .action("同步", primary = true) { doApply(a, mirror, p) }
            .show()
    }

    /**
     * 用的是**干跑给用户看的那一份 Plan**，不重算 —— 重算就意味着「用户看到的」和「实际做的」
     * 可能不是同一件事，而这一步会大批量改用户数据。
     */
    private fun doApply(a: Activity, mirror: File, p: Preview.Ready) {
        val busy = Sheet(a).busy("正在同步…")
        a.runInBackground(
            what = "应用合并 ${mirror.name}",
            work = {
                LibraryStore.open(mirror).use { mine ->
                    LibraryStore.open(p.source).use { src ->
                        MirrorApply.apply(p.plan, mirror, mine, p.source, src) { s, _ -> Log.i(TAG, s) }
                    }
                }
            },
            ok = { r ->
                busy.dismiss()
                // 静默丢行是绝对不行的：哪怕只有一条，也要让用户知道，还要说清怎么办
                val extra = buildString {
                    if (r.orphansSkipped > 0) append("\n有 ${r.orphansSkipped} 行被跳过：它们所属的文档已经不在了。")
                    if (r.hashClashesSkipped > 0) {
                        append("\n有 ${r.hashClashesSkipped} 个版本被跳过：对面已经有同一份文件了。")
                        append("用「关联为同一文档」把它们合并。")
                    }
                    if (r.pathClashesSkipped > 0) {
                        append("\n有 ${r.pathClashesSkipped} 篇笔记被跳过：对面同一路径上已经有一篇笔记了。")
                    }
                }
                a.showAlert(
                    "同步完成",
                    "硬盘 +${r.sourceUpserts}/-${r.sourceDeletes}，本机 +${r.mirrorUpserts}/-${r.mirrorDeletes}。" +
                        "\n备份：${r.backup?.name ?: "—"}$extra",
                )
            },
            fail = { e ->
                busy.dismiss()
                Log.e(TAG, "应用合并失败", e)
                a.showAlert("同步失败", "${e.message ?: e}\n源库已回滚，硬盘上的备份还在。")
            },
        )
    }

    /**
     * 按 `workspace_id` 找回源工作区。
     *
     * **判据是 id，不是路径也不是名字**：路径换台设备/换挂载点必变，名字会被改，而且盘上可能
     * 同时躺着源和另一份同名镜像。候选 = 最近 + 上次扫描到的那些 `.unrd`（够用；真要深扫交给
     * 启动页的「重新扫描存储」，别在这里替用户遍历整块盘）。
     */
    fun findSource(a: Activity, sourceId: String): File? {
        val seen = LinkedHashSet<String>()
        val candidates = (Workspace.recents(a) + Workspace.scanned(a))
            .filter { seen.add(it) }
            .map(::File)
            .filter { File(it, Workspace.DB_REL).isFile }
        for (dir in candidates) {
            val hit = runCatching {
                Db.open(File(dir, Workspace.DB_REL), readOnly = true).use { d ->
                    val wid = d.query("SELECT value FROM meta WHERE key='workspace_id'") { it.getString(0) }
                        .firstOrNull()
                    // 镜像自己也带 workspace_id，但它同时带 mirror_of —— 源盘不会有这一条
                    val isMirror = d.query(
                        "SELECT value FROM meta WHERE key=?",
                        arrayOf(MirrorStore.META_MIRROR_OF),
                    ) { it.getString(0) }.isNotEmpty()
                    wid == sourceId && !isMirror
                }
            }.getOrElse {
                Log.i(TAG, "跳过打不开的候选：${dir.absolutePath}（${it.message}）")
                false
            }
            if (hit) return dir
        }
        Log.i(TAG, "在 ${candidates.size} 个候选里没找到 workspace_id=$sourceId")
        return null
    }
}
