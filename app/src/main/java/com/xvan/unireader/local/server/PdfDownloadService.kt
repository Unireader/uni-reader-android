package com.xvan.unireader.local.server

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.xvan.unireader.R
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 后台下载 PDF 的前台服务（2026-11-04，手机端要「切走 / 锁屏也继续下」）：书库页、阅读界面的
 * 「从服务器下载」都从这里走。前台服务 + 通知 = 系统不杀；退到后台、熄屏照下，进度在通知里，
 * 通知上有「取消」。支持排队：连着点几本按顺序下，同一篇重复点只排一份。
 *
 * 下载本体在 [ServerSyncIO.downloadPdf]（写 location 行也在里面），这里只管排队、前台通知、
 * 向发起请求的界面回报事件。小 PDF 的自动预取（[ServerSync.prefetch]）是有心「尽力而为」，
 * 不经这里。多本的完成/失败汇总成一条通知；失败的本在汇总里计数，单本失败时发起界面已弹错。
 */
class PdfDownloadService : Service() {

    /** 一本的下载事件（主线程回调） */
    sealed class Event {
        /** [total] <= 0 = 总大小未知（条转圈）；轮到一本开工时也会发一条 (0, -1) */
        data class Progress(val done: Long, val total: Long) : Event()

        /** [error] = 说人话的失败原因（[ServerUi.describe]）；null = 用户在通知上取消了（界面不用弹错） */
        data class Done(val ok: Boolean, val error: String?) : Event()
    }

    companion object {
        const val TAG = "UniReader/Download"

        private const val CHANNEL = "pdf_download"
        private const val NOTI_ID = 4201
        private const val ACTION_DOWNLOAD = "com.xvan.unireader.action.DOWNLOAD_PDF"
        private const val ACTION_CANCEL = "com.xvan.unireader.action.CANCEL_DOWNLOADS"

        /** 进度通知与事件回报至少隔这么久（下载回调每 64KB 一次，太密） */
        private const val PROGRESS_MIN_MS = 300L

        private val lock = Any()
        private val main = Handler(Looper.getMainLooper())
        private val ids = AtomicInteger(0)

        private class Job(
            val ws: File,
            val documentId: String,
            val title: String,
            /** 等这本书下完要通知的请求（同一个下载被点了多次 = 都通知到） */
            val requests: MutableList<Int>,
        )

        private val queue = ArrayDeque<Job>()
        private var running: Job? = null
        private var worker: Thread? = null
        private var cancelCur: ServerClient.Cancel? = null
        private var lastDone = 0L
        private var lastTotal = -1L
        private var lastTitle: String? = null
        private var doneOk = 0
        private var doneBad = 0

        /** requestId → 回调；主线程读写，收到 [Event.Done] 即移除 */
        private val listeners = HashMap<Int, (Event) -> Unit>()

        @Volatile
        private var instance: PdfDownloadService? = null

        /**
         * 排一本 PDF（主线程调）。[listener] 在主线程收事件，收到 [Event.Done] 后不会再回调。
         * 同一工作区同一篇已经在排队 / 在下的，这个请求并到它上面、不重复下。
         */
        fun enqueue(ctx: Context, ws: File, documentId: String, title: String, listener: ((Event) -> Unit)?): Int {
            val app = ctx.applicationContext
            val req = ids.incrementAndGet()
            main.post {
                if (listener != null) listeners[req] = listener
                synchronized(lock) {
                    val dup = (listOfNotNull(running) + queue).firstOrNull { it.ws == ws && it.documentId == documentId }
                    if (dup != null) dup.requests.add(req)
                    else queue.addLast(Job(ws, documentId, title, mutableListOf(req)))
                    app.startForegroundService(Intent(app, PdfDownloadService::class.java).setAction(ACTION_DOWNLOAD))
                    kickLocked(app)
                    updateNoti(app)
                }
            }
            return req
        }

        /** 通知上的「取消」：清掉排队的、断开正在下的（正在那本按「用户取消」回报） */
        private fun cancelAll() {
            val dropped = synchronized(lock) {
                val d = queue.toList()
                queue.clear()
                cancelCur?.cancel()
                d
            }
            for (j in dropped) dispatch(j, Event.Done(false, null), done = true)
        }

        private fun kickLocked(app: Context) {
            if (worker == null) {
                worker = Thread({ loop(app) }, "unireader-pdfdl").also { it.start() }
            }
        }

        private fun loop(app: Context) {
            try {
                while (true) {
                    val next = synchronized(lock) {
                        val j = queue.removeFirstOrNull()
                        if (j == null) {
                            worker = null
                            null
                        } else {
                            val c = ServerClient.Cancel()
                            cancelCur = c
                            running = j
                            lastDone = 0L
                            lastTotal = -1L
                            j to c
                        }
                    } ?: break
                    runOne(app, next.first, next.second)
                }
            } finally {
                finish(app)
            }
        }

        private fun runOne(app: Context, job: Job, cancel: ServerClient.Cancel) {
            Log.i(TAG, "开始下载《${job.title}》（${job.ws.name}）")
            dispatch(job, Event.Progress(0L, -1L), done = false)
            var cancelled = false
            var error: String? = null
            try {
                val ep = ServerSync.endpoint(app, job.ws)
                    ?: throw ServerClient.Failure(401, app.getString(R.string.srv_need_connect))
                ServerSyncIO.downloadPdf(job.ws, job.documentId, ep.account.client(cancel), ep.wid) { done, total ->
                    progress(app, job, done, total)
                }
            } catch (e: ServerClient.Cancelled) {
                cancelled = true
            } catch (e: Throwable) {
                error = ServerUi.describe(app.resources, e)
                Log.w(TAG, "《${job.title}》下载失败", e)
            }
            val dropped = synchronized(lock) {
                running = null
                cancelCur = null
                lastTitle = job.title
                if (cancelled) {
                    // 用户取消 = 全部停：排队的也清掉，各自按取消回报
                    val d = queue.toList()
                    queue.clear()
                    d
                } else {
                    if (error == null) doneOk++ else doneBad++
                    emptyList()
                }
            }
            for (j in dropped) dispatch(j, Event.Done(false, null), done = true)
            dispatch(job, Event.Done(!cancelled && error == null, error), done = true)
        }

        private var lastEventAt = 0L

        private fun progress(app: Context, job: Job, done: Long, total: Long) {
            val now = System.currentTimeMillis()
            synchronized(lock) { lastDone = done; lastTotal = total }
            if (now - lastEventAt < PROGRESS_MIN_MS) return
            lastEventAt = now
            updateNoti(app)
            dispatch(job, Event.Progress(done, total), done = false)
        }

        private fun dispatch(job: Job, ev: Event, done: Boolean) {
            main.post {
                for (req in job.requests) {
                    val l = (if (done) listeners.remove(req) else listeners[req]) ?: continue
                    runCatching { l(ev) }.onFailure { Log.w(TAG, "回报下载事件出错", it) }
                }
            }
        }

        // ---------- 通知 ----------

        private class Snap(val show: Job?, val done: Long, val total: Long, val queued: Int)

        private fun snap(): Snap = synchronized(lock) {
            Snap(running ?: queue.firstOrNull(), lastDone, lastTotal, queue.size)
        }

        private fun buildNotification(app: Context): Notification {
            val s = snap()
            val cancelPi = PendingIntent.getService(
                app, 0, Intent(app, PdfDownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val b = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, app.getString(R.string.act_cancel), cancelPi)
            val j = s.show
            if (j == null) {
                b.setContentTitle(app.getString(R.string.srv_dl_channel))
                b.setProgress(0, 0, true)
            } else {
                b.setContentTitle(app.getString(R.string.srv_pdf_downloading, j.title))
                if (s.queued > 0) b.setContentText(app.getString(R.string.srv_dl_queued, s.queued))
                if (s.total > 0) b.setProgress(100, (s.done * 100 / s.total).toInt(), false)
                else b.setProgress(0, 0, true)
            }
            return b.build()
        }

        /** 33+ 没给通知权限时 FGS 通知只是不在抽屉里显示，下载不受影响 */
        @SuppressLint("MissingPermission", "NotificationPermission")
        private fun updateNoti(app: Context) {
            runCatching { NotificationManagerCompat.from(app).notify(NOTI_ID, buildNotification(app)) }
                .onFailure { Log.w(TAG, "更新下载通知失败", it) }
        }

        /** 队列空了：收掉前台通知，留一条汇总（有几本完成 / 失败），停服务 */
        private fun finish(app: Context) {
            val ok: Int
            val bad: Int
            synchronized(lock) {
                if (running != null || queue.isNotEmpty() || worker != null) return
                ok = doneOk
                bad = doneBad
                doneOk = 0
                doneBad = 0
            }
            if (ok + bad == 0) return
            main.post {
                synchronized(lock) {
                    if (running != null || queue.isNotEmpty() || worker != null) return@post
                }
                val title = when {
                    bad == 0 && ok == 1 -> app.getString(R.string.srv_dl_done, lastTitle ?: "")
                    bad == 0 -> app.getString(R.string.srv_dl_done_n, ok)
                    else -> app.getString(R.string.srv_dl_summary, ok, bad)
                }
                val nm = NotificationManagerCompat.from(app)
                @SuppressLint("MissingPermission", "NotificationPermission")
                fun post() = runCatching {
                    nm.notify(NOTI_ID, NotificationCompat.Builder(app, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setContentTitle(title)
                        .setAutoCancel(true)
                        .build())
                }
                post()
                instance?.let {
                    ServiceCompat.stopForeground(it, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    it.stopSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.srv_dl_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTI_ID, buildNotification(applicationContext),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (intent?.action == ACTION_CANCEL) cancelAll()
        synchronized(lock) { kickLocked(applicationContext) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
