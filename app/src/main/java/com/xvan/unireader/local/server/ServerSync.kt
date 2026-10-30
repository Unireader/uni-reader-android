package com.xvan.unireader.local.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.xvan.unireader.local.mirror.MirrorStore
import com.xvan.unireader.local.store.StoreQueue
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 服务器工作区的同步调度（`../SERVER-MODE-PLAN.md §4.3` 的 S2 档，照 Mac `ServerSyncCoordinator`）。
 * IO 在 [ServerSyncIO]，这里管时机与状态。
 *
 * | 时机 | 做什么 | 用哪条连接 |
 * |---|---|---|
 * | 打开前（[prepareToOpen]：启动页 / 阅读界面开库前） | 完整一轮：推 + 拉 + 补图片，限时，超时就取消、照常开本机缓存 | 临时连接 |
 * | 开着时（每 60 秒 / 退到后台，[sessionStart]） | **只推不拉**：不往开着的文档里写（那是 S4 的事） | 阅读界面那条 [StoreQueue]，基线不开事务 |
 * | 关掉后（[workspaceClosed]：阅读界面关库之后） | 完整一轮 | 临时连接 |
 *
 * 🔴 同一工作区一次只跑一轮（[busy]）；**阅读界面开着它的时候不跑完整一轮**（完整一轮会用另一条连接写用户数据，
 * 阅读界面内存里的笔迹快照就对不上了——对账时会把它写回去或删掉）。打开前先取消还在跑的那一轮：取消是安全的，
 * 推上去而基线没记的下一轮再推（服务器答「一致」），拉下来还没应用的修订号没记、下一轮重拉。
 * 小 PDF 在每次完整一轮之后后台下载（[prefetch]，只写 location，与开着的阅读界面不冲突）。
 *
 * 线程：[prepareToOpen] 只在后台线程调（会等）；session 那几个只在主线程调。状态变化在主线程通知 [addListener] 的人。
 */
object ServerSync {

    const val TAG = "UniReader/Server"

    sealed class Status {
        data class Synced(val at: Long) : Status()
        object Syncing : Status()
        /** 连不上服务器（或等太久）：照常用本机缓存 */
        data class Offline(val error: Throwable) : Status()
        data class Failed(val error: Throwable) : Status()
        /** 本机没有这台服务器的令牌（没连过、或断开了） */
        object NoAccount : Status()
    }

    private val main = Handler(Looper.getMainLooper())
    private val statuses = ConcurrentHashMap<String, Status>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun key(dir: File): String = dir.absoluteFile.normalize().path

    fun status(dir: File): Status? = statuses[key(dir)]

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun setStatus(k: String, s: Status?) {
        if (s == null) statuses.remove(k) else statuses[k] = s
        main.post { for (l in listeners) l() }
    }

    /** 「大 PDF」的界限：小于它的完整同步之后在后台下载，大的等点开再下（方案 §4.1，默认 50MB） */
    fun largePdfBytes(ctx: Context): Long =
        ctx.getSharedPreferences("servers", Context.MODE_PRIVATE).getInt("largePdfMB", 50).coerceAtLeast(1) * 1_048_576L

    /** 连这个工作区要用的账号 + 工作区 id。不是服务器工作区 / 没有令牌 = null。读一眼库，后台线程调 */
    data class Endpoint(val account: ServerAccount, val wid: String)

    fun endpoint(ctx: Context, dir: File): Endpoint? {
        val link = ServerSyncIO.serverLink(dir) ?: return null
        val a = ServerAccounts.find(ctx, link.serverId, link.url) ?: return null
        val wid = link.workspaceId ?: return null
        return Endpoint(a, wid)
    }

    // ---------- 完整一轮（没有活连接的时候） ----------

    /** 一轮。[prev] = 它排在后面等着的上一轮（等完就清掉）：取消时连同在等的那几轮一起取消 */
    private class Round(val cancel: ServerClient.Cancel, val done: CountDownLatch, @Volatile var prev: Round?)

    private val busy = HashMap<String, Round>()
    private val lastOk = ConcurrentHashMap<String, Long>()

    /** 取消还在跑的那一轮并等它停下（后台线程调） */
    private fun stopRound(k: String) {
        val r = synchronized(busy) { busy[k] } ?: return
        cancelChain(r)
        r.done.await()
    }

    private fun cancelChain(r: Round) {
        var x: Round? = r
        while (x != null) {
            x.cancel.cancel()
            x = x.prev
        }
    }

    /**
     * 打开服务器工作区之前（**后台线程**）：取消还在跑的上一轮，再同步一轮，最多等 [timeoutMs]。
     * [skipIfRecentMs] > 0 时，这么久之内刚同步过就不再同步（启动页开过、紧接着进阅读界面，别连着两轮）。
     * 阅读界面正开着它：不跑完整一轮，只叫那边推一次。不是服务器工作区 = 什么都不做。
     */
    fun prepareToOpen(ctx: Context, dir: File, timeoutMs: Long, skipIfRecentMs: Long = 0) {
        val k = key(dir)
        if (sessions.containsKey(k)) {
            main.post { pushNow(dir) }
            return
        }
        stopRound(k)
        val link = ServerSyncIO.serverLink(dir) ?: return
        val ep = endpoint(ctx, dir)
        if (ep == null) {
            Log.i(TAG, "${dir.name}：本机没有 ${link.url} 的令牌，直接开本机缓存")
            setStatus(k, Status.NoAccount)
            return
        }
        if (skipIfRecentMs > 0 && System.currentTimeMillis() - (lastOk[k] ?: 0L) < skipIfRecentMs) return
        val r = startRound(ctx.applicationContext, dir, ep)
        if (!r.done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            Log.i(TAG, "${dir.name}：打开前同步超过 ${timeoutMs}ms，取消、先用本机缓存")
            cancelChain(r)
            r.done.await()
        }
    }

    /** 手动「立即同步」：开着就只推，没开着就完整一轮（不等） */
    fun syncNow(ctx: Context, dir: File) {
        val app = ctx.applicationContext
        if (sessions.containsKey(key(dir))) {
            pushNow(dir)
            return
        }
        Thread({
            val ep = endpoint(app, dir)
            if (ep == null) setStatus(key(dir), Status.NoAccount) else startRound(app, dir, ep)
        }, "unireader-sync").start()
    }

    /** 阅读界面关掉这个工作区（连接已关）之后：完整同步一轮（不等它）。主线程调 */
    fun workspaceClosed(ctx: Context, dir: File) {
        sessionStop(dir)
        val app = ctx.applicationContext
        Thread({ endpoint(app, dir)?.let { startRound(app, dir, it) } }, "unireader-sync").start()
    }

    /** 起一轮（前一轮还在跑就先等它完）。返回这一轮的句柄 */
    private fun startRound(ctx: Context, dir: File, ep: Endpoint): Round {
        val k = key(dir)
        val r = synchronized(busy) { Round(ServerClient.Cancel(), CountDownLatch(1), busy[k]).also { busy[k] = it } }
        setStatus(k, Status.Syncing)
        Thread({
            var ok = false
            try {
                r.prev?.done?.await()
                r.prev = null
                r.cancel.check()
                val rep = ServerSyncIO.closedRound(dir, ep.account.client(r.cancel), ep.wid)
                Log.i(TAG, "${dir.name}：同步完成 推 ${rep.pushed} 拉 ${rep.pulled} 冲突 ${rep.pull.conflicts} 跳过 ${rep.pull.skipped}")
                lastOk[k] = System.currentTimeMillis()
                setStatus(k, Status.Synced(System.currentTimeMillis()))
                ok = true
            } catch (e: Throwable) {
                Log.w(TAG, "${dir.name}：同步没完成", e)
                setStatus(k, classify(e))
            } finally {
                synchronized(busy) { if (busy[k] === r) busy.remove(k) }
                r.done.countDown()
            }
            if (ok) prefetch(ctx, dir, ep)
        }, "unireader-sync").start()
        return r
    }

    fun classify(e: Throwable): Status = when (e) {
        is ServerClient.Cancelled -> Status.Offline(e)
        is ServerClient.Failure -> Status.Failed(e)
        is java.io.IOException -> Status.Offline(e)
        else -> Status.Failed(e)
    }

    // ---------- 小 PDF ----------

    private val prefetching = ConcurrentHashMap<String, Boolean>()

    /** 后台下载本机没有的小 PDF（同一工作区同一时间一份） */
    private fun prefetch(ctx: Context, dir: File, ep: Endpoint) {
        val k = key(dir)
        if (prefetching.putIfAbsent(k, true) != null) return
        try {
            val n = ServerSyncIO.prefetchSmallPdfs(dir, ep.account.client(), ep.wid, largePdfBytes(ctx))
            if (n > 0) {
                Log.i(TAG, "${dir.name}：后台下载了 $n 本 PDF")
                main.post { for (l in listeners) l() }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "${dir.name}：后台下载 PDF 中断", e)
        } finally {
            prefetching.remove(k)
        }
    }

    // ---------- 开着时（只推不拉） ----------

    private class Session(val ctx: Context, val dir: File, val queue: StoreQueue) {
        var pushing = false
        /** 上次确认「没有要推的」时这条连接的 `total_changes()`；没变就不必再全量找改动。-1 = 要重找 */
        var cleanAt = -1L
        lateinit var tick: Runnable
    }

    private class Prep(val tc: Long, val changes: List<SyncWire.RowChange>, val files: List<ServerSyncIO.FileUp>)

    private val sessions = ConcurrentHashMap<String, Session>()

    /** 阅读界面开好了一个服务器工作区（主线程）：每 60 秒推一次 */
    fun sessionStart(ctx: Context, dir: File, queue: StoreQueue) {
        val k = key(dir)
        sessions.remove(k)?.let { main.removeCallbacks(it.tick) }
        val s = Session(ctx.applicationContext, dir, queue)
        s.tick = Runnable {
            push(s)
            main.postDelayed(s.tick, 60_000L)
        }
        sessions[k] = s
        main.postDelayed(s.tick, 60_000L)
    }

    /** 阅读界面要关这个工作区了（主线程，在关库之前） */
    fun sessionStop(dir: File) {
        sessions.remove(key(dir))?.let { main.removeCallbacks(it.tick) }
    }

    /** 马上推一次（退到后台时）。没开着 = 什么都不做 */
    fun pushNow(dir: File) {
        sessions[key(dir)]?.let { push(it) }
    }

    /**
     * 只推不拉：在库队列上找本地改动（库没被写过就跳过）→ 网络线程传文件、推送 → 回库队列逐行记基线（不开事务，
     * 见 [SyncClient.recordPushed]）。推的时候阅读界面关了（关掉后那一轮会再推一次）、或变回了本地工作区：不记。
     */
    private fun push(s: Session) {
        val k = key(s.dir)
        if (s.pushing || sessions[k] !== s) return
        s.pushing = true
        s.queue.submit("同步：找本地改动", { store ->
            store.withMirrorDb { db ->
                val tc = totalChanges(db)
                if (tc == s.cleanAt || !MirrorStore.hasSyncBase(db)) {
                    null
                } else {
                    val ch = SyncClient.localChanges(db, s.dir)
                    Prep(tc, ch, ServerSyncIO.filesToUpload(ch, db, s.dir))
                }
            }
        }) { prep ->
            if (prep == null || sessions[k] !== s) {
                s.pushing = false
                return@submit
            }
            if (prep.changes.isEmpty()) {
                s.cleanAt = prep.tc
                s.pushing = false
                if (statuses[k] !is Status.Synced) setStatus(k, Status.Synced(System.currentTimeMillis()))
                return@submit
            }
            setStatus(k, Status.Syncing)
            Thread({
                try {
                    val ep = endpoint(s.ctx, s.dir)
                    if (ep == null) {
                        setStatus(k, Status.NoAccount)
                        main.post { s.pushing = false }
                    } else {
                        val client = ep.account.client()
                        ServerSyncIO.uploadAll(prep.files, client, ep.wid)
                        val req = SyncWire.PushRequest(com.xvan.unireader.local.store.Schema.VERSION, prep.changes)
                        val resp = SyncWire.decodePushResponse(client.push(ep.wid, SyncWire.encode(req)))
                        main.post { record(s, prep, resp) }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "${s.dir.name}：推送没完成", e)
                    if (sessions[k] === s) setStatus(k, classify(e))
                    main.post { s.pushing = false }
                }
            }, "unireader-push").start()
        }
    }

    private fun record(s: Session, prep: Prep, resp: SyncWire.PushResponse) {
        val k = key(s.dir)
        if (sessions[k] !== s) {
            s.pushing = false
            return
        }
        s.queue.submit("同步：记基线", { store ->
            store.withMirrorDb { db ->
                val before = totalChanges(db)
                SyncClient.recordPushed(db, prep.changes, resp)
                // 找改动之后、记基线之前用户又写过 → 下次照常全量找；没写过 → 记下此刻，下次没变就跳过
                if (before == prep.tc) totalChanges(db) else -1L
            }
        }) { tc ->
            s.cleanAt = tc
            s.pushing = false
            if (sessions[k] === s) setStatus(k, Status.Synced(System.currentTimeMillis()))
        }
    }

    private fun totalChanges(db: com.xvan.unireader.local.store.Db): Long =
        db.query("SELECT total_changes()") { it.getLong(0) }.first()

    // ---------- 变回本地工作区 ----------

    /** 停掉同步（后台线程调）：取消还在跑的那一轮、停开着时的推送。之后调用方写库去掉服务器记录 */
    fun stopAll(dir: File) {
        val k = key(dir)
        main.post { sessionStop(dir) }
        stopRound(k)
        setStatus(k, null)
    }
}
