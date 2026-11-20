package com.xvan.unireader.local

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import com.xvan.unireader.R
import com.xvan.unireader.local.mirror.MirrorStore
import com.xvan.unireader.local.mirror.MirrorUi
import com.xvan.unireader.local.server.ServerAccounts
import com.xvan.unireader.local.server.ServerSync
import com.xvan.unireader.local.server.ServerSyncIO
import com.xvan.unireader.local.server.ServerUi
import com.xvan.unireader.local.server.SyncClient
import com.xvan.unireader.local.server.SyncDeleteGuard
import com.xvan.unireader.local.store.LibDocument
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.StoreQueue
import com.xvan.unireader.shared.PadPanels
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.showAlert
import java.io.File

/**
 * 工作区一级的操作（2026-11-20 书库页去掉后从 `LibraryActivity` 搬到阅读界面，用户：「首页直接移除吧，打开就是工作区」）：
 * 添加 PDF、书的改名 / 移到分组 / 删除、分组改名 / 解散、工作区「更多」（立即同步 / 变回本地 / 上传到服务器 /
 * 做成离线副本 / 同步预览 / 回收站）、同步状态一行字。入口在抽屉「书库 → 文档」的表头与长按（`ReaderDrawer`）。
 *
 * 写库一律经阅读界面那条 [Host.queue]（同一个工作区只开一条可写连接，内存里的快照才对得上）；
 * 上传 / 变回本地会改「这是不是服务器工作区」，做完由宿主把工作区关了再开（[Host.reopen]），同步会话按新身份重建。
 */
class WorkspaceActions(private val a: Activity, private val host: Host) {

    interface Host {
        val workspace: File?
        val queue: StoreQueue?
        /** 书库列表变了（加书、改名、分组、删除）：重读抽屉 / 标签标题 */
        fun libraryChanged()
        /** 一本书要删了：开着它的标签先关掉（别让画布接着往一本没了的书里写） */
        fun closeTabsOf(documentId: String)
        /** 工作区身份变了（上传 / 变回本地）：关掉再开 */
        fun reopen()
        /** 显示回收站（宿主那份，恢复后会重读开着的标签） */
        fun showTrash()
    }

    /** 表头那一行要用的工作区信息（在库队列上读，[load]） */
    class Info(
        val name: String,
        val serverUrl: String?,
        val serverId: String?,
        val isMirror: Boolean,
        val lent: Int,
        /** 本机没有 PDF 文件的书（服务器工作区里没下载的 / 离线副本没带的） */
        val missing: Set<String>,
    )

    var info: Info? = null
        private set

    /** 库队列线程上读（阅读界面每次刷书库时一起读） */
    fun load(store: LibraryStore, ws: File, docs: List<LibDocument>): Info = Info(
        name = store.workspaceName().ifEmpty { ws.name },
        serverUrl = store.meta(SyncClient.META_SERVER_URL)?.takeIf { it.isNotEmpty() },
        serverId = store.meta(SyncClient.META_SERVER_ID)?.takeIf { it.isNotEmpty() },
        isMirror = MirrorUi.isMirror(store),
        lent = MirrorStore.decodeCheckouts(store.meta(MirrorStore.META_CHECKOUTS)).size,
        missing = docs.filter { Workspace.firstOpenablePdf(ws, store, it.id) == null }.map { it.id }.toSet(),
    )

    fun setInfo(i: Info?) {
        info = i
    }

    /** 抽屉表头下那一行：服务器工作区 = 哪台 + 同步状态；离线副本 / 借出份数；都没有 = null（不显示） */
    fun statusLine(): String? {
        val ws = host.workspace ?: return null
        val i = info ?: return null
        val url = i.serverUrl
        if (url != null) {
            val label = ServerAccounts.find(a, i.serverId, url)?.label ?: url.substringAfter("://")
            return ServerUi.statusLine(a, label, ServerSync.status(ws))
        }
        val parts = listOfNotNull(
            if (i.isMirror) a.getString(R.string.ws_is_mirror) else null,
            if (i.lent > 0) a.getString(R.string.ws_lent, i.lent) else null,
        )
        return parts.joinToString(" · ").ifEmpty { null }
    }

    /** 点状态那一行：服务器工作区 = 立即同步（删得太多先问那种情况 = 弹确认） */
    fun statusClicked() {
        val ws = host.workspace ?: return
        if (info?.serverUrl == null) return
        val st = ServerSync.status(ws)
        if (st is ServerSync.Status.NeedsConfirm) confirmServerDeletes(ws, st.excess) else ServerSync.syncNow(a, ws)
    }

    // ---------- 工作区「更多」 ----------

    /**
     * 镜像与源盘**互斥**地给动作——镜像不能再做镜像，源盘也没有「同步回去」这回事；服务器工作区的本机缓存
     * 本身就是离线副本。只出现该出现的那几条。
     */
    fun showMore() {
        val ws = host.workspace ?: return
        val i = info
        val name = i?.name ?: ws.name
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        var dlg: AlertDialog? = null
        fun item(icon: Int, label: String, detail: String? = null, act: () -> Unit) {
            val onClick = { dlg?.dismiss(); act() }
            list.addView(
                if (detail == null) PadPanels.iconRow(a, icon, label, Ui.accent(a), onClick = onClick)
                else PadPanels.twoLineRow(a, icon, Ui.accent(a), label, detail, onClick = onClick),
            )
        }
        val sheet = Sheet(a)
        when {
            i?.serverUrl != null -> {
                sheet.title(a.getString(R.string.srv_workspace_title))
                statusLine()?.let { sheet.subtitle(it) }
                item(R.drawable.ic_redo, a.getString(R.string.srv_sync_now)) { ServerSync.syncNow(a, ws) }
                if (!ServerSyncIO.isInCachesFolder(ws)) {
                    item(R.drawable.ic_folder, a.getString(R.string.srv_detach)) {
                        ServerUi.confirmDetach(a, ws, name) { host.reopen() }
                    }
                }
            }
            i?.isMirror == true -> {
                sheet.title("离线副本")
                item(R.drawable.ic_eye, "同步预览…", "插上源盘就能预览同步会做什么") { MirrorUi.syncPreview(a, ws) }
            }
            else -> {
                sheet.title(name)
                item(R.drawable.ic_tablet, "做成离线副本…", "复制一份到内部存储，拔了盘也能看和写") {
                    MirrorUi.makeMirror(a, ws) { host.libraryChanged() }
                }
                item(R.drawable.ic_monitor, a.getString(R.string.srv_upload)) { ServerUi.upload(a, ws, name) { host.reopen() } }
            }
        }
        item(R.drawable.ic_delete, a.getString(R.string.trash_open)) { host.showTrash() }
        dlg = sheet.content(list).action(a.getString(R.string.act_cancel)).show()
    }

    // ---------- 添加 PDF ----------

    /** 这一轮添加里每个文件的状态（路径 → 尾标）：加过的行标出来，别让人重复点同一本 */
    private val addNotes = HashMap<String, String>()

    /**
     * 「添加 PDF」：浏览本机文件，点一个加一本（[PdfImport] 拷进 `PDFs/` 并入库），窗不关能接着加；
     * 关窗时刷新书库。作业排在阅读界面那条库队列上，主线程不做 I/O（§9.5）。
     */
    fun addPdf() {
        val ws = host.workspace ?: return
        if (host.queue == null) return
        addNotes.clear()
        var browser: FileBrowser? = null
        browser = FileBrowser(
            a,
            FileBrowser.Mode.PDF,
            title = "添加 PDF",
            tipText = "点一个就加一本，可以接着加下一本；文件会拷进工作区",
            fileNote = { f -> addNotes[f.absolutePath] },
            onDismiss = { host.libraryChanged() },
        ) { f -> ingest(ws, f) { browser?.refresh() } }
        browser.show()
    }

    private fun ingest(ws: File, f: File, onChanged: () -> Unit) {
        val q = host.queue ?: return
        val key = f.absolutePath
        addNotes[key] = "添加中…"
        onChanged()
        q.submit("导入 ${f.name}", { store -> runCatching { PdfImport.ingest(a, ws, store, f) } }) { r ->
            r.fold(
                onSuccess = { res ->
                    addNotes[key] = when (res) {
                        is PdfImport.Result.Added -> "已添加"
                        is PdfImport.Result.Duplicate -> "已在库中"
                    }
                },
                onFailure = { e ->
                    // 失败的行把标记去掉，让用户能原地再点一次（换个文件、或等 U 盘缓过来）
                    addNotes.remove(key)
                    Log.e(TAG, "导入 ${f.name} 失败", e)
                    a.showAlert("加不进来：${f.name}", e.message ?: "未知错误")
                },
            )
            onChanged()
        }
    }

    // ---------- 书：改名 / 移到分组 / 删除 ----------

    /** 长按一本书 */
    fun showDocMenu(d: LibDocument, groups: List<String>) {
        Sheet(a).title(d.title)
            .action(a.getString(R.string.act_cancel))
            .action(a.getString(R.string.doc_rename)) { renameDoc(d) }
            .action(a.getString(R.string.doc_move_group)) { pickGroup(d, groups) }
            .action(a.getString(R.string.doc_delete), primary = true) {
                // 确认框里的数 = 这篇全部笔记行（笔迹 / 文字笔记 / 高亮 / 书签 / 图片…），不只是笔迹
                val q = host.queue ?: return@action
                q.submit("数这篇的笔记", { s -> runCatching { s.notes(d.id).size }.getOrDefault(0) }) { n -> confirmDeleteDoc(d, n) }
            }
            .show()
    }

    /** 写一次库，成了刷新书库；失败弹一句（[failTitle]） */
    private fun write(what: String, failTitle: String, body: (LibraryStore) -> Unit) {
        val q = host.queue ?: return
        q.submit(what, { s -> runCatching { body(s) } }) { r ->
            r.onFailure { e ->
                Log.e(TAG, "$what 失败", e)
                a.showAlert(failTitle, e.message ?: "")
            }
            host.libraryChanged()
        }
    }

    private fun renameDoc(d: LibDocument) {
        val edit = PadPanels.inputBox(a, a.getString(R.string.doc_name_hint)).apply {
            setText(d.title)
            setSelection(text.length)
        }
        Sheet(a).title(a.getString(R.string.doc_rename_title))
            .content(edit)
            .action(a.getString(R.string.act_cancel))
            .action(a.getString(R.string.common_save), primary = true) {
                val t = edit.text.toString().trim()
                if (t.isNotEmpty() && t != d.title) {
                    write("改书名《${d.title}》", a.getString(R.string.doc_rename_title)) { it.renameDocument(d.id, t) }
                }
            }
            .show()
    }

    /** 移到哪个分组：未分组 / 现有分组 / 新建分组…（当前所在的那一项标成强调色） */
    private fun pickGroup(d: LibDocument, groups: List<String>) {
        var dlg: AlertDialog? = null
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val fail = a.getString(R.string.doc_move_group)
        fun item(label: String, current: Boolean, onClick: () -> Unit) {
            box.addView(
                Ui.row(a) { dlg?.dismiss(); onClick() }.apply {
                    addView(Ui.title(a, label, 16f).apply { if (current) setTextColor(Ui.accent(a)) })
                },
            )
        }
        item(a.getString(R.string.doc_ungrouped), d.group.isEmpty()) {
            if (d.group.isNotEmpty()) write("移出分组《${d.title}》", fail) { it.setDocumentGroup(d.id, "") }
        }
        for (g in groups) item(g, d.group == g) {
            if (d.group != g) write("移到分组《${d.title}》→ $g", fail) { it.setDocumentGroup(d.id, g) }
        }
        item(a.getString(R.string.doc_group_new), false) {
            askGroupName(a.getString(R.string.doc_group_new_title), "") { name ->
                write("新建分组 $name", fail) { it.setDocumentGroup(d.id, name) }
            }
        }
        dlg = Sheet(a).title(a.getString(R.string.doc_move_group)).subtitle(d.title)
            .content(ScrollView(a).apply { addView(box) })
            .action(a.getString(R.string.act_cancel))
            .show()
    }

    private fun confirmDeleteDoc(d: LibDocument, total: Int) {
        val ws = host.workspace ?: return
        Sheet(a).title(a.getString(R.string.doc_delete_confirm, d.title))
            .subtitle(a.getString(R.string.doc_delete_msg, total))
            .action(a.getString(R.string.act_cancel))
            .action(a.getString(R.string.act_delete), primary = true) {
                host.closeTabsOf(d.id)
                val q = host.queue ?: return@action
                // 先归档进回收站、成了才删（红线 11），都在 Trash.deleteDocument 里
                q.submit("删除文档《${d.title}》", { s -> runCatching { Trash.deleteDocument(s, ws, d) } }) { r ->
                    r.onFailure { e ->
                        Log.e(TAG, "文档归档失败，没删", e)
                        a.showAlert(a.getString(R.string.trash_title), a.getString(R.string.trash_failed))
                    }
                    host.libraryChanged()
                }
            }
            .show()
    }

    // ---------- 分组：改名 / 解散 ----------

    /** 长按分组标题：改名 / 解散（书回未分组，书本身不动） */
    fun showGroupMenu(group: String) {
        val fail = a.getString(R.string.doc_group_rename_title)
        Sheet(a).title(group)
            .action(a.getString(R.string.act_cancel))
            .action(a.getString(R.string.doc_group_dissolve)) { write("解散分组 $group", fail) { it.renameDocumentGroup(group, "") } }
            .action(a.getString(R.string.doc_group_rename), primary = true) {
                askGroupName(fail, group) { name ->
                    if (name != group) write("分组改名 $group → $name", fail) { it.renameDocumentGroup(group, name) }
                }
            }
            .show()
    }

    private fun askGroupName(title: String, initial: String, then: (String) -> Unit) {
        val edit = PadPanels.inputBox(a, a.getString(R.string.doc_group_name_hint)).apply {
            setText(initial)
            setSelection(text.length)
        }
        Sheet(a).title(title)
            .content(edit)
            .action(a.getString(R.string.act_cancel))
            .action(a.getString(R.string.common_save), primary = true) {
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) then(t)
            }
            .show()
    }

    // ---------- 同步：删得太多先问 ----------

    /** 说清这次要从服务器删多少、各是什么，确认了才同步（`ServerSync.confirmDeletes`） */
    private fun confirmServerDeletes(ws: File, x: SyncDeleteGuard.Excess) {
        fun name(table: String) = a.getString(
            when (table) {
                "document", "variant", "location" -> R.string.srv_del_book
                "note" -> R.string.srv_del_note
                "ink_layer" -> R.string.srv_del_layer
                "scratch_pad" -> R.string.srv_del_scratch
                "board_note", "board_page", "board_item" -> R.string.srv_del_board
                "exercise", "exercise_attempt" -> R.string.srv_del_exercise
                else -> R.string.srv_del_other
            },
        )
        val lines = x.byTable.groupBy({ name(it.first) }, { it.second }).map { (n, c) -> "$n × ${c.sum()}" }
        Sheet(a).title(a.getString(R.string.srv_del_title, x.total))
            .subtitle(a.getString(R.string.srv_del_msg) + "\n\n" + lines.joinToString("\n"))
            .action(a.getString(R.string.srv_del_later))
            .action(a.getString(R.string.srv_del_confirm), primary = true) { ServerSync.confirmDeletes(a, ws) }
            .show()
    }

    companion object {
        const val TAG = "UniReader/Workspace"
    }
}
