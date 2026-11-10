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
 * 服务器工作区的同步调度（`../docs/SERVER-MODE-PLAN.md §4.3` 的 S2 档，照 Mac `ServerSyncCoordinator`）。
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
        /** 这次要从服务器删的行太多，先停下等用户确认（`SyncDeleteGuard`） */
        data class NeedsConfirm(val excess: SyncDeleteGuard.Excess) : Status()
    }

    /** 用户确认过、放行的那批删除（按工作区）。 */
    private val allowedDeletes = ConcurrentHashMap<String, Set<String>>()

    /** 用户在界面上确认了「删得太多」那一批：放行，马上再同步一次。主线程调 */
    fun confirmDeletes(ctx: Context, dir: File) {
        val k = key(dir)
        val x = (statuses[k] as? Status.NeedsConfirm)?.excess ?: return
        allowedDeletes[k] = x.keys
        syncNow(ctx, dir)
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
                val rep = ServerSyncIO.closedRound(dir, ep.account.client(r.cancel), ep.wid, allowedDeletes[k].orEmpty())
                allowedDeletes.remove(k)
                Log.i(TAG, "${dir.name}：同步完成 推 ${rep.pushed} 拉 ${rep.pulled} 冲突 ${rep.pull.conflicts} 跳过 ${rep.pull.skipped}")
                lastOk[k] = System.currentTimeMillis()
                setStatus(k, Status.Synced(System.currentTimeMillis()))
                ok = true
            } catch (e: SyncDeleteGuard.Blocked) {
                Log.w(TAG, "${dir.name}：这一轮要删 ${e.excess.total} 行，超过阈值，等用户确认")
                setStatus(k, Status.NeedsConfirm(e.excess))
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

    private class Session(val ctx: Context, val dir: File, val queue: StoreQueue, val onRemote: (List<SyncWire.PulledRow>) -> Unit) {
        @Volatile var pushing = false
        /** 拉别的设备的改动正在做（和推送互斥：两边同时改基线会互相踩） */
        @Volatile var pulling = false
        /** 上次确认「没有要推的」时这条连接的 `total_changes()`；没变就不必再全量找改动。-1 = 要重找 */
        var cleanAt = -1L
        lateinit var tick: Runnable

        // 以下三个只在库队列线程上读写（`push` 里找改动那一步）
        /** 上次看到的 `total_changes()` 与那一刻：变了说明用户还在写，停手满 [QUIET_MS] 才推 */
        var seenTc = -1L
        var dirtyAt = 0L
        var lastPush = 0L

        @Volatile var stopped = false
        val cancel = ServerClient.Cancel()
        /** 上次处理完时服务器的笔记文件修订号（0 = 还没处理过，开头会完整过一遍 `Notes/`） */
        @Volatile var noteHead = 0L

        // —— 笔记文件（`NoteSync`，`../docs/PROTOCOL.md §9.5`）——
        /** 这一轮不许碰的笔记（编辑器里还有没存的输入的那篇，`Notes/…` 路径）。主线程上问。 */
        var noteSkip: () -> Set<String> = { emptySet() }
        /** 拉下来的笔记文件换掉了本机的（主线程）：界面重扫 `Notes/`、重读开着的那篇。 */
        var onNotes: () -> Unit = {}
        @Volatile var notesBusy = false
        /** 本机存过笔记文件（[noteSaved]）：下一秒推。 */
        @Volatile var notesDirty = false
        var lastNotePush = 0L
    }

    /** 笔记文件存盘 / 新建 / 改名 / 删除之后调（主线程）：开着的服务器工作区马上把 `Notes/` 的改动推上去。 */
    fun noteSaved(dir: File) {
        sessions[key(dir)]?.notesDirty = true
    }

    /** 开着时的笔记文件：只推（不问服务器、不改本机已有文件）。存过盘就推，另外每分钟兜底一次。主线程调，活在网络线程上。 */
    private fun maybePushNotes(s: Session) {
        val now = System.currentTimeMillis()
        if (s.stopped || s.notesBusy || s.pulling) return
        if (!s.notesDirty && now - s.lastNotePush < HEARTBEAT_MS) return
        s.notesBusy = true
        s.notesDirty = false
        s.lastNotePush = now
        Thread({
            try {
                val ep = endpoint(s.ctx, s.dir) ?: return@Thread
                val rep = NoteSync.sync(s.dir, ep.account.client(s.cancel), ep.wid, ServerSyncIO.deviceName(), full = false)
                ServerSyncIO.logNotes(s.dir, rep, "open")
            } catch (e: ServerClient.Cancelled) {
                // 会话停了
            } catch (e: Throwable) {
                Log.w(TAG, "${s.dir.name}：笔记文件没推上去（下一分钟再试）", e)
            } finally {
                s.notesBusy = false
            }
        }, "unireader-notes").start()
    }

    /** 服务器上的笔记文件有动静（或会话刚开）：完整一轮（推 + 拉 + 冲突副本），编辑器里没存的那篇绕开。网络线程调。 */
    private fun pullNotes(s: Session, client: ServerClient, ep: Endpoint) {
        while (s.notesBusy && !s.stopped) Thread.sleep(200)
        if (s.stopped) return
        s.notesBusy = true
        try {
            val latch = CountDownLatch(1)
            var skip: Set<String> = emptySet()
            main.post { skip = runCatching { s.noteSkip() }.getOrDefault(emptySet()); latch.countDown() }
            latch.await(5, TimeUnit.SECONDS)
            val rep = NoteSync.sync(s.dir, client, ep.wid, ServerSyncIO.deviceName(), full = true, skip = skip)
            ServerSyncIO.logNotes(s.dir, rep, "live")
            if (rep.localChanged) main.post { if (sessions[key(s.dir)] === s) s.onNotes() }
        } finally {
            s.notesBusy = false
        }
    }

    /** 停手多久再推（用户定「改完停 2 秒推送」）、两次推送至少隔多久、兜底每隔多久推一次 */
    private const val QUIET_MS = 2_000L
    private const val MIN_PUSH_MS = 5_000L
    private const val HEARTBEAT_MS = 60_000L

    private class Prep(
        val tc: Long, val changes: List<SyncWire.RowChange>, val files: List<ServerSyncIO.FileUp>,
        /** 删得太多、还没确认：这一批先不推。 */
        val blocked: SyncDeleteGuard.Excess? = null,
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    /**
     * 阅读界面开好了一个服务器工作区（主线程）：每秒看一眼本机有没有改动（停手 2 秒后推），挂着等服务器通知（S4，有动静就拉）。
     * [onRemote]（主线程）：拉到的别的设备的改动已经写进库了，让界面跟上（刷新列表、重读开着的文档）。
     */
    fun sessionStart(
        ctx: Context, dir: File, queue: StoreQueue,
        /** 这一刻不许同步去碰的笔记（编辑器里有没存的输入），主线程上问 */
        noteSkip: () -> Set<String> = { emptySet() },
        /** 拉下来的笔记文件换掉了本机的（主线程） */
        onNotes: () -> Unit = {},
        onRemote: (List<SyncWire.PulledRow>) -> Unit = {},
    ) {
        val k = key(dir)
        sessions.remove(k)?.let { stopSession(it) }
        val s = Session(ctx.applicationContext, dir, queue, onRemote)
        s.noteSkip = noteSkip
        s.onNotes = onNotes
        s.lastNotePush = System.currentTimeMillis()   // 开头那一遍由 watch 里的完整一轮做（noteHead = 0）
        s.tick = Runnable {
            push(s)
            maybePushNotes(s)
            main.postDelayed(s.tick, 1_000L)
        }
        sessions[k] = s
        main.postDelayed(s.tick, 1_000L)
        Thread({ watch(s) }, "unireader-watch").start()
    }

    /** 阅读界面要关这个工作区了（主线程，在关库之前） */
    fun sessionStop(dir: File) {
        sessions.remove(key(dir))?.let { stopSession(it) }
    }

    private fun stopSession(s: Session) {
        s.stopped = true
        s.cancel.cancel()       // 断开挂着的长轮询
        main.removeCallbacks(s.tick)
    }

    /** 马上推一次（退到后台时，不等停手）。没开着 = 什么都不做 */
    fun pushNow(dir: File) {
        sessions[key(dir)]?.let { push(it, force = true) }
    }

    /**
     * 只推不拉：在库队列上找本地改动（库没被写过就跳过）→ 网络线程传文件、推送 → 回库队列逐行记基线（不开事务，
     * 见 [SyncClient.recordPushed]）。推的时候阅读界面关了（关掉后那一轮会再推一次）、或变回了本地工作区：不记。
     */
    private fun push(s: Session, force: Boolean = false) {
        val k = key(s.dir)
        if (s.pushing || s.pulling || sessions[k] !== s) return
        s.pushing = true
        s.queue.submit("同步：找本地改动", { store ->
            store.withMirrorDb { db ->
                val now = System.currentTimeMillis()
                val tc = totalChanges(db)
                if (tc != s.seenTc) { s.seenTc = tc; s.dirtyAt = now }      // 用户还在写：重新计停手的时间
                val quiet = now - s.dirtyAt >= QUIET_MS && now - s.lastPush >= MIN_PUSH_MS
                if (tc == s.cleanAt || !MirrorStore.hasSyncBase(db)) {
                    null
                } else if (!force && !quiet && now - s.lastPush < HEARTBEAT_MS) {
                    null        // 还没停手，等下一秒再看
                } else {
                    s.lastPush = now
                    val ch = SyncClient.localChanges(db, s.dir)
                    // 删得太多先问：这一批没确认过就整批先不推（同一次推送是一个事务，不拆开）
                    val x = SyncDeleteGuard.excess(ch, ServerSyncIO.baseCounts(db), allowedDeletes[k].orEmpty())
                    if (x != null) Prep(tc, ch, emptyList(), x) else Prep(tc, ch, ServerSyncIO.filesToUpload(ch, db, s.dir))
                }
            }
        }) { prep ->
            if (prep == null || sessions[k] !== s) {
                s.pushing = false
                return@submit
            }
            if (prep.blocked != null) {
                if ((statuses[k] as? Status.NeedsConfirm)?.excess != prep.blocked) {
                    Log.w(TAG, "${s.dir.name}：这次推送要删 ${prep.blocked.total} 行，超过阈值，等用户确认")
                }
                setStatus(k, Status.NeedsConfirm(prep.blocked))
                s.pushing = false
                return@submit
            }
            allowedDeletes.remove(k)
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
                val after = totalChanges(db)
                if (s.seenTc == before) s.seenTc = after      // 记基线是同步自己写的，不算「本机改动」
                // 找改动之后、记基线之前用户又写过 → 下次照常全量找；没写过 → 记下此刻，下次没变就跳过
                if (before == prep.tc) after else -1L
            }
        }) { tc ->
            s.cleanAt = tc
            s.pushing = false
            if (sessions[k] === s) setStatus(k, Status.Synced(System.currentTimeMillis()))
        }
    }

    // ---------- 服务器通知 → 拉（S4） ----------

    /**
     * 挂着等服务器通知（长轮询，最多 25 秒一轮），有动静就拉。出错退避重试（5 秒起、最多 60 秒）；
     * 服务器程序太旧没有 `wait` 就不挂了（退回原来的只推）。**网络线程**，会话停了就退出。
     */
    private fun watch(s: Session) {
        val k = key(s.dir)
        var failures = 0
        while (!s.stopped) {
            try {
                val ep = endpoint(s.ctx, s.dir)
                if (ep == null) { Thread.sleep(10_000); continue }
                val client = ep.account.client(s.cancel)
                val rows = onQueue(s, "同步：读已拉修订号") { st -> st.withMirrorDb { SyncClient.pulledRev(it) } } ?: continue
                val notes = s.noteHead
                val ch = client.waitForChange(ep.wid, rows, notes)
                failures = 0
                if (!ch.changed || s.stopped) continue
                pullWhileOpen(s, client, ep, ch)
                // 拉完修订号没往前走（服务器数据被还原过之类）：别空转
                val after = onQueue(s, "同步：读已拉修订号") { st -> st.withMirrorDb { SyncClient.pulledRev(it) } }
                if (after == rows && s.noteHead == notes) Thread.sleep(10_000)
            } catch (e: ServerClient.Cancelled) {
                return
            } catch (e: Throwable) {
                if (s.stopped) return
                if (e is ServerClient.Failure && e.isUnsupportedRoute) {
                    Log.w(TAG, "${s.dir.name}：服务器程序太旧，没有 wait 接口——开着时不收通知（升级服务器后重新打开工作区）")
                    return
                }
                failures++
                Log.w(TAG, "${s.dir.name}：等服务器通知出错（第 $failures 次）", e)
                if (sessions[k] === s) setStatus(k, classify(e))
                try { Thread.sleep(minOf(60_000L, 5_000L * failures)) } catch (_: InterruptedException) { return }
            }
        }
    }

    private class Applied(val before: Long, val after: Long, val conflicts: Int, val skipped: Int, val images: Boolean, val books: Boolean)

    /**
     * 拉别的设备的改动：网络线程取行（攒齐所有页）→ **库队列**上应用（和用户自己的写入排在一条队里，内存快照不会对不上）→
     * 主线程让界面跟上（[Session.onRemote]）。与推送互斥。新书的小 PDF 随后后台下。
     */
    private fun pullWhileOpen(s: Session, client: ServerClient, ep: Endpoint, ch: ServerClient.Change) {
        val k = key(s.dir)
        while (s.pushing && !s.stopped) Thread.sleep(300)
        if (s.stopped) return
        s.pulling = true
        try {
            val since = onQueue(s, "同步：读已拉修订号") { st -> st.withMirrorDb { SyncClient.pulledRev(it) } } ?: return
            val (rows, head) = SyncClient.fetchPulled(since) { SyncWire.decodePullResponse(client.pull(ep.wid, it)) }
            if (s.stopped) return
            if (rows.isNotEmpty() || head != since) {
                val a = onQueue(s, "同步：应用拉到的改动") { st ->
                    st.withMirrorDb { db ->
                        val before = totalChanges(db)
                        val rep = SyncClient.applyPulled(db, s.dir, rows)
                        SyncClient.setPulledRev(db, head)
                        val newImages = rows.any { it.table == "image" && !it.deleted }
                        if (newImages) runCatching { ServerSyncIO.downloadMissingImages(db, s.dir, client, ep.wid) }
                        val after = totalChanges(db)
                        if (s.seenTc == before) s.seenTc = after      // 同步自己写的，不算「本机改动」
                        Applied(before, after, rep.conflicts, rep.skipped, newImages, rows.any { it.table == "variant" && !it.deleted })
                    }
                }
                if (a != null) {
                    if (rows.isNotEmpty()) Log.i(TAG, "${s.dir.name}：开着时拉到 ${rows.size} 行（冲突 ${a.conflicts} 跳过 ${a.skipped}）${rows.map { it.table }.toSet().sorted()}")
                    main.post {
                        if (sessions[k] !== s) return@post
                        if (s.cleanAt == a.before) s.cleanAt = a.after
                        if (rows.isNotEmpty()) s.onRemote(rows)
                        setStatus(k, Status.Synced(System.currentTimeMillis()))
                    }
                    if (a.books) Thread({ prefetch(s.ctx, s.dir, ep) }, "unireader-prefetch").start()
                }
            }
            // 笔记文件：服务器那边的修订号变了（或这次会话还没过过一遍）→ 完整一轮
            if (ch.notes != s.noteHead || s.noteHead == 0L) pullNotes(s, client, ep)
            s.noteHead = ch.notes
        } finally {
            s.pulling = false
        }
    }

    /** 在库队列上做一件事并等结果（网络线程调）。队列关了 / 超时 = null */
    private fun <T : Any> onQueue(s: Session, what: String, work: (com.xvan.unireader.local.store.LibraryStore) -> T): T? {
        val latch = CountDownLatch(1)
        var out: T? = null
        var failed: Throwable? = null
        s.queue.submit(what, { st -> try { work(st) } catch (t: Throwable) { failed = t; null } }, { r -> out = r; latch.countDown() })
        if (!latch.await(60, TimeUnit.SECONDS)) return null
        failed?.let { Log.w(TAG, "$what 失败", it) }
        return out
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
