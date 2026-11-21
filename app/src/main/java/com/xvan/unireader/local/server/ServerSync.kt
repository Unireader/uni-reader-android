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
    private class Round(val cancel: ServerClient.Cancel, val done: CountDownLatch, @Volatile var prev: Round?) {
        /** 这一轮之前的状态：打开前等太久、被取消时恢复它，不当成「连不上」（服务器是通的，只是这一轮没跑完）。 */
        @Volatile var before: Status? = null
        /** 打开前等太久、被这边主动取消的（不是网络断了）。 */
        @Volatile var timedOut = false
    }

    private val busy = HashMap<String, Round>()
    private val lastOk = ConcurrentHashMap<String, Long>()

    /** 取消还在跑的那一轮并等它停下（后台线程调） */
    private fun stopRound(k: String) {
        val r = synchronized(busy) { busy[k] } ?: return
        cancelChain(r)
        r.done.await()
    }

    /** 这边主动取消（等太久 / 要开阅读界面了）：被取消的那几轮都不报「连不上」，见 [Round.timedOut]。 */
    private fun cancelChain(r: Round) {
        var x: Round? = r
        while (x != null) {
            x.timedOut = true
            x.cancel.cancel()
            x = x.prev
        }
    }

    /**
     * 打开服务器工作区之前（**后台线程**）：取消还在跑的上一轮，再同步一轮，最多等 [timeoutMs]。
     * [skipIfRecentMs] > 0 时，这么久之内刚同步过就不再同步（启动页开过、紧接着进阅读界面，别连着两轮）。
     * 阅读界面正开着它：不跑完整一轮，只叫那边推一次。不是服务器工作区 = 什么都不做。
     */
    fun prepareToOpen(
        ctx: Context, dir: File, timeoutMs: Long, skipIfRecentMs: Long = 0,
        /**
         * 等满了还没完怎么办：true（阅读界面）= 取消（阅读界面开着时不许另一条连接跑完整一轮，交给它自己的同步接手）；
         * false（启动页进书库页）= 不再等、让这一轮在后台跑完（书库页不和它抢写库），状态随后变成「已同步」。
         */
        cancelOnTimeout: Boolean = true,
    ) {
        val k = key(dir)
        if (sessions.containsKey(k)) {
            main.post { pushNow(dir) }
            return
        }
        // 启动页那一轮等满 3 秒没完、留在后台接着跑的：阅读界面接着等它，不取消重来
        // （原先取消再从头跑一轮，两轮各要 3 秒多，打开要七秒半，2026-11-21）
        val running = synchronized(busy) { busy[k] }
        if (running != null && cancelOnTimeout) {
            if (running.done.await(timeoutMs, TimeUnit.MILLISECONDS)) return
            Log.i(TAG, "${dir.name}：接着等启动页那一轮又过了 ${timeoutMs}ms，取消、先用本机缓存")
            cancelChain(running)
            running.done.await()
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
        // 打开前只拉（2026-11-21 用户定）：本机没推的改动、笔记文件、补拉都在打开后做
        val r = startRound(ctx.applicationContext, dir, ep, pullOnly = true)
        if (!r.done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            if (!cancelOnTimeout) {
                Log.i(TAG, "${dir.name}：打开前同步超过 ${timeoutMs}ms，先打开本机缓存，这一轮在后台接着跑")
                return
            }
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

    /** 起一轮（前一轮还在跑就先等它完）。[pullOnly] = 打开前那一轮，只拉（[ServerSyncIO.openRound]）。返回这一轮的句柄 */
    private fun startRound(ctx: Context, dir: File, ep: Endpoint, pullOnly: Boolean = false): Round {
        val k = key(dir)
        val r = synchronized(busy) { Round(ServerClient.Cancel(), CountDownLatch(1), busy[k]).also { busy[k] = it } }
        r.before = statuses[k]?.takeIf { it !is Status.Syncing }
        setStatus(k, Status.Syncing)
        Thread({
            var ok = false
            try {
                r.prev?.done?.await()
                r.prev = null
                r.cancel.check()
                val rep = if (pullOnly) {
                    ServerSyncIO.openRound(dir, ep.account.client(r.cancel), ep.wid)
                } else {
                    ServerSyncIO.closedRound(dir, ep.account.client(r.cancel), ep.wid, allowedDeletes[k].orEmpty())
                        .also { allowedDeletes.remove(k) }
                }
                Log.i(TAG, "${dir.name}：同步完成 推 ${rep.pushed} 拉 ${rep.pulled} 冲突 ${rep.pull.conflicts} 跳过 ${rep.pull.skipped}")
                lastOk[k] = System.currentTimeMillis()
                setStatus(k, Status.Synced(System.currentTimeMillis()))
                ok = true
            } catch (e: SyncDeleteGuard.Blocked) {
                Log.w(TAG, "${dir.name}：这一轮要删 ${e.excess.total} 行，超过阈值，等用户确认")
                setStatus(k, Status.NeedsConfirm(e.excess))
            } catch (e: Throwable) {
                if (r.timedOut && e is ServerClient.Cancelled) {
                    // 打开前等太久、这边主动取消的：服务器是通的，别报「连不上」；阅读界面的同步会接手
                    Log.i(TAG, "${dir.name}：打开前那一轮没等完，已取消（交给打开后的同步）")
                    setStatus(k, r.before)
                } else {
                    Log.w(TAG, "${dir.name}：同步没完成", e)
                    setStatus(k, classify(e))
                }
            } finally {
                synchronized(busy) { if (busy[k] === r) busy.remove(k) }
                r.done.countDown()
            }
            if (ok) {
                com.xvan.unireader.local.search.SemanticVectors.pullAsync(ctx, ep.wid, ep.account.client())
                prefetch(ctx, dir, ep)
            }
        }, "unireader-sync").start()
        return r
    }

    /**
     * 拉一次块向量（语义搜索，`../docs/SEMANTIC-SYNC-PLAN.md §4.1`），不等。不是服务器工作区 / 没令牌 = 什么都不做。
     * 设置页打开开关、模型刚装好时 [minGapMs] = 0；搜索时顺带调（离上次够久才真拉）。任何线程都行。
     */
    fun pullVectors(ctx: Context, dir: File, minGapMs: Long) {
        val app = ctx.applicationContext
        Thread({
            endpoint(app, dir)?.let { com.xvan.unireader.local.search.SemanticVectors.pullAsync(app, it.wid, it.account.client(), minGapMs) }
        }, "unireader-vectors").start()
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
        /** 上次确认「没有要推的」时这条连接的 `total_changes()`；没变就不必再全量找改动。-1 = 要重找（只用于整库扫描） */
        @Volatile var cleanAt = -1L
        lateinit var tick: Runnable
        /** 停手满 2 秒那一刻再看一次（不等下一秒的整点） */
        val wake = Runnable { push(this@Session) }

        // —— 只查改过的行（[SyncDirty]）——
        /** 触发器挂上了：平时只查改过的行。false = 没挂上，每次整库扫描（原来的做法） */
        @Volatile var tracking = false
        /** 下一次要整库扫描一遍：会话开头、触发器丢了、整库扫描那一批没推成 */
        @Volatile var needFull = true
        /** 找改动时算出的「过多久再看」（毫秒，0 = 不用），回主线程后排上 [wake] */
        @Volatile var wakeIn = 0L

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

    /** 停手多久再推（用户定「改完停 2 秒推送」）、两次推送至少隔多久（只用于整库扫描）、兜底每隔多久推一次 */
    private const val QUIET_MS = 2_000L
    private const val MIN_PUSH_MS = 5_000L
    private const val HEARTBEAT_MS = 60_000L

    private class Prep(
        val tc: Long, val changes: List<SyncWire.RowChange>, val files: List<ServerSyncIO.FileUp>,
        /** 删得太多、还没确认：这一批先不推。 */
        val blocked: SyncDeleteGuard.Excess? = null,
        /** 只查改过的行时从 [SyncDirty] 取走的行；没推成要放回去。null = 这一批是整库扫描出来的 */
        val taken: Map<String, Set<String>>? = null,
    ) {
        // 计时（推完写一行日志）：找改动用了多久、最后一次用户写入的时刻（墙钟毫秒，0 = 不知道）
        var findMs = 0L
        var lastWriteAt = 0L
    }

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
        // 挂上「改过哪些行」的记录（排在第一次找改动之前；第一次照旧整库扫描，之后只查改过的行）
        // （在库队列上就记下结果：下一个作业就是找改动，别等回主线程）
        // 挂好之后：本机还没推的行（打开前只拉，不推）由后台另一条只读连接整库扫描找出来、放进改动记录，
        // 照常由「只查改过的行」推上去——不占库队列，刚打开就写的笔迹不用排在扫描后面（[scanInBackground]）
        queue.submit("同步：挂改动记录", { st ->
            st.withMirrorDb { db ->
                s.tracking = runCatching { SyncDirty.install(db) }
                    .onFailure { Log.w(TAG, "${dir.name}：改动记录没挂上，开着时每次整库扫描", it) }
                    .getOrDefault(false)
                if (s.tracking) s.needFull = false
                s.tracking
            }
        }) { ok -> if (ok) scanInBackground(s) }
        s.tick = Runnable {
            push(s)
            maybePushNotes(s)
            main.postDelayed(s.tick, 1_000L)
        }
        sessions[k] = s
        main.postDelayed(s.tick, 1_000L)
        Thread({ watch(s) }, "unireader-watch").start()
    }

    /**
     * 会话开头在后台找本机还没推的行（另一条只读连接整库扫描，[ServerSyncIO.unpushedRows]），放进改动记录（时刻记 0，不推迟推送），
     * 下一次找改动就推。覆盖得全：扫描开始前改动记录已经挂上，之后的写都记得到；之前的写都已落库、扫描看得见。
     * 扫描出错 → 退回库队列上整库扫描一次（[Session.needFull]）。主线程调。
     */
    private fun scanInBackground(s: Session) {
        Thread({
            val t0 = android.os.SystemClock.uptimeMillis()
            val keys = try {
                ServerSyncIO.unpushedRows(s.dir)
            } catch (e: Throwable) {
                Log.w(TAG, "${s.dir.name}：后台整库扫描出错，改在库队列上扫", e)
                null
            }
            if (s.stopped) return@Thread
            if (keys == null) {
                s.needFull = true
                s.cleanAt = -1L
                return@Thread
            }
            val n = keys.values.sumOf { it.size }
            Log.i(TAG, "${s.dir.name}：后台整库扫描 ${android.os.SystemClock.uptimeMillis() - t0}ms，本机没推的 $n 行")
            if (n == 0) return@Thread
            s.queue.submit("同步：记下没推的行", { st ->
                st.withMirrorDb { db -> runCatching { SyncDirty.putBack(db, keys) }.getOrDefault(false) }
            }) { ok -> if (!ok) { s.needFull = true; s.cleanAt = -1L } }
        }, "unireader-scan").start()
    }

    /** 阅读界面要关这个工作区了（主线程，在关库之前） */
    fun sessionStop(dir: File) {
        sessions.remove(key(dir))?.let { stopSession(it) }
    }

    private fun stopSession(s: Session) {
        s.stopped = true
        s.cancel.cancel()       // 断开挂着的长轮询
        main.removeCallbacks(s.tick)
        main.removeCallbacks(s.wake)
        // 库可能还开着（变回本地工作区）：别让触发器一直记下去。库正要关的话这一步排在关库之前，无害
        s.queue.submit("同步：拆改动记录") { st -> st.withMirrorDb { db -> SyncDirty.uninstall(db) } }
    }

    /** 马上推一次（退到后台时，不等停手）。没开着 = 什么都不做 */
    fun pushNow(dir: File) {
        sessions[key(dir)]?.let { push(it, force = true) }
    }

    /**
     * 只推不拉：在库队列上找本地改动（[findChanges]）→ 网络线程传文件、推送 → 回库队列逐行记基线（不开事务，
     * 见 [SyncClient.recordPushed]）。推的时候阅读界面关了（关掉后那一轮会再推一次）、或变回了本地工作区：不记。
     */
    private fun push(s: Session, force: Boolean = false) {
        val k = key(s.dir)
        if (s.pushing || s.pulling || sessions[k] !== s) return
        s.pushing = true
        s.queue.submit("同步：找本地改动", { store ->
            store.withMirrorDb { db ->
                // 出错也要回到主线程放开 pushing（作业抛出去的话回调不会来，之后就再也不推了）
                try {
                    findChanges(s, db, k, force)
                } catch (e: Throwable) {
                    Log.w(TAG, "${s.dir.name}：找本地改动出错，下次整库扫描", e)
                    s.needFull = true
                    s.cleanAt = -1L
                    null
                }
            }
        }) { prep ->
            if (prep == null || sessions[k] !== s) {
                s.pushing = false
                if (prep == null && s.wakeIn > 0 && !s.stopped) {
                    main.removeCallbacks(s.wake)
                    main.postDelayed(s.wake, s.wakeIn)
                }
                return@submit
            }
            if (prep.blocked != null) {
                if ((statuses[k] as? Status.NeedsConfirm)?.excess != prep.blocked) {
                    Log.w(TAG, "${s.dir.name}：这次推送要删 ${prep.blocked.total} 行，超过阈值，等用户确认")
                }
                setStatus(k, Status.NeedsConfirm(prep.blocked))
                unpushed(s, prep)
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
                        main.post { unpushed(s, prep) }
                    } else {
                        val client = ep.account.client()
                        val t0 = android.os.SystemClock.uptimeMillis()
                        ServerSyncIO.uploadAll(prep.files, client, ep.wid)
                        val req = SyncWire.PushRequest(com.xvan.unireader.local.store.Schema.VERSION, prep.changes)
                        val resp = SyncWire.decodePushResponse(client.push(ep.wid, SyncWire.encode(req)))
                        val since = if (prep.lastWriteAt > 0) "，最后一次写入到推完 ${System.currentTimeMillis() - prep.lastWriteAt}ms" else ""
                        Log.i(
                            TAG,
                            "${s.dir.name}：开着时推了 ${prep.changes.size} 处（${if (prep.taken != null) "只查改过的行" else "整库扫描"}）：" +
                                "找改动 ${prep.findMs}ms · 传文件+推 ${android.os.SystemClock.uptimeMillis() - t0}ms$since",
                        )
                        main.post { record(s, prep, resp) }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "${s.dir.name}：推送没完成", e)
                    if (sessions[k] === s) setStatus(k, classify(e))
                    main.post { unpushed(s, prep) }
                }
            }, "unireader-push").start()
        }
    }

    /**
     * 找本机要推的改动（库队列线程）。null = 这次不推（没改动 / 还没停手 / 不是服务器工作区）。
     *
     * 平时只查 [SyncDirty] 记下的改过的行（几十毫秒）；会话开头、触发器丢了、或上一批整库扫描的没推成时整库扫描一遍
     * （平板上大工作区要两三秒）。停手时间按触发器记下的**写入时刻**算，满 2 秒那一刻就查（[Session.wakeIn]），不等下一秒的整点。
     */
    private fun findChanges(s: Session, db: com.xvan.unireader.local.store.Db, k: String, force: Boolean): Prep? {
        s.wakeIn = 0
        if (!MirrorStore.hasSyncBase(db)) return null
        val now = System.currentTimeMillis()
        if (s.tracking && !s.needFull) {
            val peek = SyncDirty.peek(db)
            if (peek != null) {
                if (peek.count == 0) return null
                // 只查改过的行很便宜：不再要求两次推送隔 5 秒（那是整库扫描时为了少扫加的），停手满 2 秒就推
                val due = peek.lastAt + QUIET_MS
                if (!force && now < due && now - s.lastPush < HEARTBEAT_MS) {
                    s.wakeIn = due - now
                    return null
                }
                val taken = SyncDirty.take(db).orEmpty()
                s.lastPush = now
                val t0 = android.os.SystemClock.uptimeMillis()
                return prepare(db, k, s, -1L, SyncClient.localChanges(db, s.dir, taken), taken).also {
                    it.findMs = android.os.SystemClock.uptimeMillis() - t0
                    it.lastWriteAt = peek.lastAt
                }
            }
            // 触发器没了（库连接换过之类）：重新挂上，这一次整库扫描
            Log.w(TAG, "${s.dir.name}：改动记录丢了，重新挂上、这次整库扫描")
            s.tracking = runCatching { SyncDirty.install(db) }.getOrDefault(false)
            s.needFull = true
            s.cleanAt = -1L
        }
        // 整库扫描（原来的做法）：库被写过、停手满 2 秒（或每分钟兜底）才扫
        val tc = totalChanges(db)
        if (tc != s.seenTc) { s.seenTc = tc; s.dirtyAt = now }      // 用户还在写：重新计停手的时间
        val quiet = now - s.dirtyAt >= QUIET_MS && now - s.lastPush >= MIN_PUSH_MS
        if (tc == s.cleanAt) return null
        if (!force && !quiet && now - s.lastPush < HEARTBEAT_MS) return null        // 还没停手，等下一秒再看
        s.lastPush = now
        if (s.tracking) {
            SyncDirty.clear(db)         // 扫描覆盖到了；从这一刻起再写的照常记下
            s.needFull = false
        }
        val t0 = android.os.SystemClock.uptimeMillis()
        return prepare(db, k, s, totalChanges(db), SyncClient.localChanges(db, s.dir), null).also {
            it.findMs = android.os.SystemClock.uptimeMillis() - t0
        }
    }

    private fun prepare(
        db: com.xvan.unireader.local.store.Db, k: String, s: Session, tc: Long,
        ch: List<SyncWire.RowChange>, taken: Map<String, Set<String>>?,
    ): Prep {
        // 删得太多先问：这一批没确认过就整批先不推（同一次推送是一个事务，不拆开）
        val x = SyncDeleteGuard.excess(ch, ServerSyncIO.baseCounts(db), allowedDeletes[k].orEmpty())
        return if (x != null) Prep(tc, ch, emptyList(), x, taken)
        else Prep(tc, ch, ServerSyncIO.filesToUpload(ch, db, s.dir), taken = taken)
    }

    /**
     * 这一批没推成（主线程）：把这批的行放回改动记录，下次只查它们（加上之后又写的）。
     * 只查改过的行取走的 → 原样放回；挂着触发器时整库扫描出来的 → 放回这批改动的行（扫描时已清空记录，
     * 这批以外的行要么和基线一致、要么之后又写过已经记下了）；没挂触发器 → 下次再整库扫描一遍。
     * 放回去之后才放开 [Session.pushing]，免得下一次找改动插到放回去之前、漏掉这几行。
     */
    private fun unpushed(s: Session, prep: Prep) {
        val taken = prep.taken
            ?: if (s.tracking && !s.needFull) prep.changes.groupBy({ it.table }, { it.rowId }).mapValues { it.value.toSet() } else null
        if (taken == null || taken.isEmpty()) {
            if (taken == null) { s.needFull = true; s.cleanAt = -1L }
            s.pushing = false
            return
        }
        s.queue.submit("同步：放回没推的行", { st ->
            st.withMirrorDb { db -> runCatching { SyncDirty.putBack(db, taken) }.getOrDefault(false) }
        }) { ok ->
            if (!ok) { s.needFull = true; s.cleanAt = -1L }
            s.pushing = false
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
    /**
     * 开着时补拉一次旧版跳过的新表（网页，`SyncClient.catchUp`）：打开前那一轮最多等 3 秒，大工作区的补拉可能每次都超时、永远补不上，
     * 所以开着时在网络线程上再做一次——拉在网络线程、只应用这几张表的行在库队列。失败不要紧，下次打开再来。
     */
    private fun catchUpWhileOpen(s: Session) {
        try {
            val want = onQueue(s, "同步：要补拉的表") { st -> st.withMirrorDb { SyncClient.pendingCatchUp(it) } }?.toSet()
            if (want.isNullOrEmpty()) return
            val ep = endpoint(s.ctx, s.dir) ?: return
            val client = ep.account.client(s.cancel)
            var since = 0L
            val got = ArrayList<SyncWire.PulledRow>()
            while (!s.stopped) {
                val page = SyncWire.decodePullResponse(client.pull(ep.wid, since))
                page.rows.filterTo(got) { it.table in want }
                if (page.more && page.rows.isNotEmpty()) since = page.rows.last().rev else break
            }
            if (s.stopped) return
            onQueue(s, "同步：应用补拉的行") { st ->
                st.withMirrorDb { db ->
                    val t0 = System.currentTimeMillis()
                    val before = totalChanges(db)
                    // 旧服务器忽略过的本机行，接下来的推送里重新推（同 `SyncClient.catchUp`）。
                    // 清基线会经 sync_base 上的触发器把这些行记进改动记录，只查改过的行也推得到
                    SyncClient.clearBase(db, want)
                    if (got.isNotEmpty()) SyncClient.applyPulled(db, s.dir, got)
                    SyncDirty.settle(db, t0)
                    SyncClient.markCaughtUp(db, want)
                    if (s.seenTc == before) s.seenTc = totalChanges(db)   // 同步自己写的，不算「本机改动」
                }
            } ?: return
            if (got.isEmpty()) return
            Log.i(TAG, "${s.dir.name}：补拉了 ${got.size} 行（${want.sorted().joinToString(",")}）")
            if (got.any { it.table == "web_doc" && !it.deleted }) {
                val hashes = onQueue(s, "同步：读网页清单") { st -> st.webFileHashes() }
                if (hashes != null) runCatching { ServerSyncIO.downloadMissingWebFiles(s.dir, hashes, client, ep.wid) }
            }
            main.post { if (sessions[key(s.dir)] === s) s.onRemote(got) }
        } catch (e: Throwable) {
            Log.w(TAG, "${s.dir.name}：补拉新表没做完（下次打开再来）", e)
        }
    }

    private fun watch(s: Session) {
        val k = key(s.dir)
        var failures = 0
        catchUpWhileOpen(s)
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
                        val t0 = System.currentTimeMillis()
                        val before = totalChanges(db)
                        val rep = SyncClient.applyPulled(db, s.dir, rows)
                        SyncDirty.settle(db, t0)      // 拉下来写的行照样复查一遍，但不算「用户在写」、不推迟推送
                        SyncClient.setPulledRev(db, head)
                        val newImages = rows.any { it.table == "image" && !it.deleted }
                        if (newImages) runCatching { ServerSyncIO.downloadMissingImages(db, s.dir, client, ep.wid) }
                        val after = totalChanges(db)
                        if (s.seenTc == before) s.seenTc = after      // 同步自己写的，不算「本机改动」
                        Applied(before, after, rep.conflicts, rep.skipped, newImages, rows.any { it.table == "variant" && !it.deleted })
                    }
                }
                // 网页文件（`../docs/WEB-DOC-PLAN.md §4`）：在网络线程上补，别占着库队列（课件里可能有视频）
                if (a != null && rows.any { it.table == "web_doc" && !it.deleted }) {
                    val hashes = onQueue(s, "同步：读网页清单") { st -> st.webFileHashes() }
                    if (hashes != null) runCatching { ServerSyncIO.downloadMissingWebFiles(s.dir, hashes, client, ep.wid) }
                        .onFailure { Log.w(TAG, "补网页文件没补完（下次同步再补）", it) }
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
            // 块向量（语义搜索，`../docs/SEMANTIC-SYNC-PLAN.md §4.1`）：拉完行顺带看一眼（开关关着 / 模型没就位不拉）
            com.xvan.unireader.local.search.SemanticVectors.pullAsync(s.ctx, ep.wid, ep.account.client())
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

    /** 🔴 必须走 [com.xvan.unireader.local.store.Db.totalChanges]（主连接上读）：直接 SELECT 会落到连接池的只读连接上，永远读到 0 */
    private fun totalChanges(db: com.xvan.unireader.local.store.Db): Long = db.totalChanges()

    // ---------- 变回本地工作区 ----------

    /** 停掉同步（后台线程调）：取消还在跑的那一轮、停开着时的推送。之后调用方写库去掉服务器记录 */
    fun stopAll(dir: File) {
        val k = key(dir)
        main.post { sessionStop(dir) }
        stopRound(k)
        setStatus(k, null)
    }
}
