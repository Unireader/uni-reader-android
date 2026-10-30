package com.xvan.unireader.local.server

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import com.xvan.unireader.R
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.local.mirror.MirrorStore
import com.xvan.unireader.local.mirror.MirrorUi
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.Schema
import com.xvan.unireader.shared.PadPanels
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.runInBackground
import com.xvan.unireader.shared.showAlert
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import javax.net.ssl.SSLException

/**
 * 连接服务器的几块界面（方案 `../docs/SERVER-MODE-PLAN.md §2「安卓模式1」`）：连接面板、从服务器打开、上传、变回本地、下载 PDF、
 * 状态一行字。启动页 / 书库页 / 阅读界面都从这里调，界面只用 [Sheet] 与 [Ui]（同其它弹层）。
 */
object ServerUi {

    private const val TAG = "UniReader/Server"

    // ---------- 说人话 ----------

    fun describe(a: Activity, e: Throwable): String = when (e) {
        is ServerClient.Cancelled -> a.getString(R.string.srv_err_timeout)
        is ServerClient.Failure -> when {
            !e.message.isNullOrEmpty() -> e.message!!
            e.status == ServerClient.BAD_RESPONSE -> a.getString(R.string.srv_err_bad_response)
            e.status == ServerClient.BAD_ADDRESS -> a.getString(R.string.srv_bad_address)
            else -> a.getString(R.string.srv_err_status, e.status)
        }
        is ServerSyncIO.NotOnServer -> a.getString(R.string.srv_err_not_on_server)
        is UnknownHostException -> a.getString(R.string.srv_err_unknown_host)
        is SSLException -> a.getString(R.string.srv_err_tls)
        is ConnectException, is NoRouteToHostException, is SocketTimeoutException -> a.getString(R.string.srv_err_unreachable)
        is IOException -> e.message ?: a.getString(R.string.srv_err_unreachable)
        else -> e.message ?: e.javaClass.simpleName
    }

    /** 书库页表头的一行：哪台服务器 + 同步状态 */
    fun statusLine(a: Activity, label: String, s: ServerSync.Status?): String = when (s) {
        is ServerSync.Status.Synced -> a.getString(
            R.string.srv_status_synced, label, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.at)),
        )
        is ServerSync.Status.Syncing -> a.getString(R.string.srv_status_syncing, label)
        is ServerSync.Status.Offline -> a.getString(R.string.srv_status_offline, label, describe(a, s.error))
        is ServerSync.Status.Failed -> a.getString(R.string.srv_status_failed, label, describe(a, s.error))
        is ServerSync.Status.NoAccount -> a.getString(R.string.srv_status_noaccount, label)
        null -> a.getString(R.string.srv_status_idle, label)
    }

    /** 不可取消的进度弹层：一行字 + 一条进度（fraction 为 null 时转圈） */
    class Progress(a: Activity, msg: String) {
        private val text = Ui.title(a, msg, 15f)
        private val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 1000
        }
        val dialog: AlertDialog = Sheet(a).cancelable(false).content(
            LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(text)
                addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 12) })
            },
        ).show()

        fun set(msg: String, fraction: Double? = null) {
            text.text = msg
            if (fraction == null) {
                bar.isIndeterminate = true
            } else {
                bar.isIndeterminate = false
                bar.progress = (fraction.coerceIn(0.0, 1.0) * 1000).toInt()
            }
        }

        fun dismiss() = dialog.dismiss()
    }

    // ---------- 连接 ----------

    /**
     * 连接面板：地址 + 配对码，下面一组「局域网里的服务器」（点一下填地址；身份对得上而地址变了的，点一下更新地址）。
     * 配对成功存账号、关面板、回调。失败留在原地改（[Sheet.action] 的 dismiss=false）。
     */
    fun connect(a: Activity, onDone: (ServerAccount) -> Unit) {
        val addr = PadPanels.inputBox(a, a.getString(R.string.srv_address_hint))
        val code = PadPanels.inputBox(a, a.getString(R.string.srv_code_hint)).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val nearbyBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(addr)
            addView(Ui.spacer(a, 8))
            addView(code)
            addView(Ui.spacer(a, 12))
            addView(nearbyBox)
        }
        var discovery: ServerDiscovery? = null
        fun renderNearby() {
            nearbyBox.removeAllViews()
            nearbyBox.addView(Ui.sectionTitle(a, a.getString(R.string.srv_nearby)))
            val list = discovery?.found.orEmpty()
            if (list.isEmpty()) {
                nearbyBox.addView(Ui.body(a, a.getString(R.string.srv_nearby_none)))
                return
            }
            val accounts = ServerAccounts.all(a)
            for (f in list) {
                val acc = accounts.firstOrNull { f.serverId.isNotEmpty() && it.serverId == f.serverId }
                val moved = acc != null && acc.url != f.url
                val line2 = buildString {
                    append(f.url.substringAfter("://"))
                    when {
                        moved -> append("　").append(a.getString(R.string.srv_nearby_moved))
                        acc != null -> append("　").append(a.getString(R.string.srv_nearby_connected))
                    }
                    if (f.schema != null && f.schema != Schema.VERSION) {
                        append("　").append(a.getString(R.string.srv_nearby_schema, f.schema, Schema.VERSION))
                    }
                }
                nearbyBox.addView(
                    PadPanels.twoLineRow(a, R.drawable.ic_doc, Ui.accent(a), f.name, line2) {
                        if (moved && acc != null) {
                            ServerAccounts.updateUrl(a, acc, f.url)
                            Toast.makeText(a, a.getString(R.string.srv_address_updated), Toast.LENGTH_SHORT).show()
                            renderNearby()
                        } else {
                            addr.setText(f.url.substringAfter("://"))
                            code.requestFocus()
                        }
                    },
                )
            }
        }
        discovery = ServerDiscovery(a) { renderNearby() }
        renderNearby()
        discovery.start()

        var busy = false
        val dlg = Sheet(a).title(a.getString(R.string.srv_connect_title))
            .subtitle(a.getString(R.string.srv_connect_sub))
            .content(col)
            .action(a.getString(R.string.act_cancel))
            .action(a.getString(R.string.srv_connect_go), primary = true, dismiss = false) { d ->
                if (busy) return@action
                val url = ServerClient.normalize(addr.text.toString())
                val c = code.text.toString().trim().uppercase()
                if (url == null) { a.showAlert(a.getString(R.string.srv_connect_title), a.getString(R.string.srv_bad_address)); return@action }
                if (c.isEmpty()) { a.showAlert(a.getString(R.string.srv_connect_title), a.getString(R.string.srv_need_code)); return@action }
                busy = true
                val p = Progress(a, a.getString(R.string.srv_connecting))
                a.runInBackground(
                    what = "配对 $url",
                    work = { ServerClient.pair(url, c, MirrorStore.deviceName()) },
                    ok = { r ->
                        p.dismiss()
                        busy = false
                        val acc = ServerAccount(url, r.serverId, r.serverName.orEmpty(), r.deviceId, r.token)
                        ServerAccounts.save(a, acc)
                        Log.i(TAG, "已连接 ${acc.label}（$url）")
                        d.dismiss()
                        Toast.makeText(a, a.getString(R.string.srv_connected, acc.label), Toast.LENGTH_SHORT).show()
                        onDone(acc)
                    },
                    fail = { e ->
                        p.dismiss()
                        busy = false
                        a.showAlert(a.getString(R.string.srv_connect_title), describe(a, e))
                    },
                )
            }
            .show()
        dlg.setOnDismissListener { discovery.stop() }
    }

    /** 一台服务器的菜单（启动页那一段标题右边的 ⋯）：断开 = 只删本机的配对 */
    fun accountMenu(a: Activity, acc: ServerAccount, onChanged: () -> Unit) {
        Sheet(a).title(acc.label)
            .subtitle(acc.url)
            .content(Ui.tip(a, a.getString(R.string.srv_disconnect_msg)))
            .action(a.getString(R.string.act_cancel))
            .action(a.getString(R.string.srv_disconnect), primary = true) {
                ServerAccounts.remove(a, acc)
                onChanged()
            }
            .show()
    }

    // ---------- 从服务器打开 ----------

    /** 本机已有这个服务器工作区的缓存就直接用它；没有就下载快照建一份。成功后回调缓存文件夹（调用方再去打开） */
    fun openFromServer(a: Activity, acc: ServerAccount, ws: ServerClient.WorkspaceInfo, then: (File) -> Unit) {
        val p = Progress(a, a.getString(R.string.srv_step_library))
        val name = ws.name.ifEmpty { "Workspace" }
        a.runInBackground(
            what = "从服务器打开 $name",
            work = {
                ServerSyncIO.findCache(ws.id) ?: ServerSyncIO.createCache(
                    File(ServerSyncIO.cachesRoot, ServerSyncIO.hostFolderName(acc.url)), name, acc.url, acc.serverId,
                    acc.client(), ws.id,
                ) { step ->
                    val text = a.getString(
                        when (step) {
                            ServerSyncIO.Step.LIBRARY -> R.string.srv_step_library
                            ServerSyncIO.Step.PREPARING -> R.string.srv_step_preparing
                            ServerSyncIO.Step.IMAGES -> R.string.srv_step_images
                        },
                    )
                    a.runOnUiThread { p.set(text) }
                }
            },
            ok = { dir ->
                p.dismiss()
                then(dir)
            },
            fail = { e ->
                p.dismiss()
                a.showAlert(a.getString(R.string.srv_open_failed), describe(a, e))
            },
        )
    }

    // ---------- 上传 ----------

    /** 书库页 ⋯「上传到服务器…」：挑服务器 → 确认 → 带进度上传 → 原文件夹就地成为本机缓存（方案 §5 第 4 步） */
    fun upload(a: Activity, ws: File, displayName: String, onDone: () -> Unit) {
        val accounts = ServerAccounts.all(a)
        if (accounts.isEmpty()) {
            a.showAlert(a.getString(R.string.srv_upload), a.getString(R.string.srv_upload_need_server))
            return
        }
        // 先在后台查能不能传（离线副本 / 有副本借出 / 已经在服务器上），再挑服务器
        a.runInBackground(
            what = "上传前检查 ${ws.name}",
            work = {
                LibraryStore.open(ws, readOnly = true).use { s ->
                    when {
                        s.meta(SyncClient.META_SERVER_URL)?.isNotEmpty() == true -> R.string.srv_upload_already
                        MirrorUi.isMirror(s) -> R.string.srv_upload_is_mirror
                        MirrorStore.decodeCheckouts(s.meta(MirrorStore.META_CHECKOUTS)).isNotEmpty() -> R.string.srv_upload_has_copy
                        else -> 0
                    }
                }
            },
            ok = { block ->
                if (block != 0) {
                    a.showAlert(a.getString(R.string.srv_upload), a.getString(block))
                } else if (accounts.size == 1) {
                    confirmUpload(a, ws, displayName, accounts[0], onDone)
                } else {
                    val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                    var dlg: AlertDialog? = null
                    for (acc in accounts) {
                        list.addView(
                            PadPanels.twoLineRow(a, R.drawable.ic_doc, Ui.accent(a), acc.label, acc.url) {
                                dlg?.dismiss()
                                confirmUpload(a, ws, displayName, acc, onDone)
                            },
                        )
                    }
                    dlg = Sheet(a).title(a.getString(R.string.srv_upload_pick)).content(list)
                        .action(a.getString(R.string.act_cancel)).show()
                }
            },
            fail = { e -> a.showAlert(a.getString(R.string.srv_upload_failed), describe(a, e)) },
        )
    }

    private fun confirmUpload(a: Activity, ws: File, displayName: String, acc: ServerAccount, onDone: () -> Unit) {
        Sheet(a).title(a.getString(R.string.srv_upload_title, displayName, acc.label))
            .subtitle(a.getString(R.string.srv_upload_msg))
            .action(a.getString(R.string.act_cancel))
            .action(a.getString(R.string.srv_upload_go), primary = true) { runUpload(a, ws, displayName, acc, onDone) }
            .show()
    }

    private fun runUpload(a: Activity, ws: File, displayName: String, acc: ServerAccount, onDone: () -> Unit) {
        val p = Progress(a, a.getString(R.string.srv_upload_preparing))
        val libraryLabel = a.getString(R.string.srv_upload_library)
        a.runInBackground(
            what = "上传 ${ws.name} → ${acc.url}",
            work = {
                val staging = File(a.cacheDir, "upload-${UUID.randomUUID()}")
                try {
                    LibraryStore.open(ws).use { store ->
                        store.ensureWorkspaceId()
                        val plan = ServerSyncIO.prepareUpload(store, ws, staging)
                        val info = ServerSyncIO.upload(plan, acc.client()) { i, n, f ->
                            val text = if (i == 0) libraryLabel else a.getString(R.string.srv_upload_files, i, n)
                            a.runOnUiThread { p.set(text, f) }
                        }
                        // 记成服务器工作区：基线对着上传出去的那份快照（上传期间本机又改的，下一轮照常推）
                        store.setMeta(SyncClient.META_SERVER_URL, acc.url)
                        acc.serverId?.let { store.setMeta(SyncClient.META_SERVER_ID, it) }
                        store.withMirrorDb { SyncClient.writeBase(it, plan.base, info.head) }
                        plan.missingPdfs
                    }
                } finally {
                    staging.deleteRecursively()
                }
            },
            ok = { missing ->
                p.dismiss()
                val msg = a.getString(R.string.srv_upload_done_msg, displayName, acc.label) +
                    if (missing > 0) "\n\n" + a.getString(R.string.srv_upload_missing, missing) else ""
                a.showAlert(a.getString(R.string.srv_upload_done), msg)
                onDone()
            },
            fail = { e ->
                p.dismiss()
                Log.w(TAG, "上传 ${ws.name} 失败", e)
                a.showAlert(a.getString(R.string.srv_upload_failed), describe(a, e))
            },
        )
    }

    // ---------- 变回本地工作区 ----------

    /**
     * 书库页 ⋯「变回本地工作区…」（只给不在 App 缓存目录里的，同 Mac）：确认框里列出本机没有文件的书（只提示、不拦），
     * 确认后停同步（等还在跑的那一轮）、去掉库里记的服务器与基线表。服务器上那份不动，不连服务器也能做。
     */
    fun confirmDetach(a: Activity, ws: File, displayName: String, onDone: () -> Unit) {
        a.runInBackground(
            what = "数本机没有的 PDF ${ws.name}",
            work = {
                LibraryStore.open(ws, readOnly = true).use { s ->
                    s.allDocuments().filter { Workspace.firstOpenablePdf(ws, s, it.id) == null }.map { it.title }
                }
            },
            ok = { missing ->
                var msg = a.getString(R.string.srv_detach_msg)
                if (missing.isNotEmpty()) {
                    val names = missing.take(8).joinToString("、") + if (missing.size > 8) "…" else ""
                    msg += "\n\n" + a.getString(R.string.srv_detach_missing, missing.size, names)
                }
                Sheet(a).title(a.getString(R.string.srv_detach_title, displayName))
                    .subtitle(msg)
                    .action(a.getString(R.string.act_cancel))
                    .action(a.getString(R.string.srv_detach_go), primary = true) {
                        a.runInBackground(
                            what = "变回本地工作区 ${ws.name}",
                            work = {
                                ServerSync.stopAll(ws)
                                LibraryStore.open(ws).use { s -> s.withMirrorDb { SyncClient.detach(it) } }
                            },
                            ok = { onDone() },
                            fail = { e -> a.showAlert(a.getString(R.string.srv_detach_go), describe(a, e)) },
                        )
                    }
                    .show()
            },
        )
    }

    // ---------- 下载 PDF ----------

    /** 下一本书的 PDF（不管多大），带进度；成功 / 失败都回调（失败已经提示过） */
    fun downloadPdf(a: Activity, ws: File, documentId: String, title: String, onDone: (Boolean) -> Unit) {
        val p = Progress(a, a.getString(R.string.srv_pdf_downloading, title))
        a.runInBackground(
            what = "下载 PDF ${documentId.take(8)}",
            work = {
                val ep = ServerSync.endpoint(a, ws) ?: throw ServerClient.Failure(401, a.getString(R.string.srv_need_connect))
                ServerSyncIO.downloadPdf(ws, documentId, ep.account.client(), ep.wid) { done, total ->
                    if (total > 0) a.runOnUiThread { p.set(a.getString(R.string.srv_pdf_downloading, title), done.toDouble() / total) }
                }
            },
            ok = {
                p.dismiss()
                onDone(true)
            },
            fail = { e ->
                p.dismiss()
                a.showAlert(a.getString(R.string.srv_pdf_failed), describe(a, e))
                onDone(false)
            },
        )
    }

    /** 本机没有这本书的 PDF：问一句「从服务器下载」 */
    fun offerPdfDownload(a: Activity, ws: File, documentId: String, title: String, onDone: (Boolean) -> Unit) {
        Sheet(a).title(a.getString(R.string.srv_pdf_missing_title, title))
            .action(a.getString(R.string.act_cancel)) { onDone(false) }
            .action(a.getString(R.string.srv_pdf_download), primary = true) { downloadPdf(a, ws, documentId, title, onDone) }
            .cancelable(false)
            .show()
    }
}
