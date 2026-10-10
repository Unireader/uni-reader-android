package com.xvan.unireader.pad

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.security.MessageDigest

/**
 * 局域网里开着平板服务的 Mac（Bonjour 发现，契约见 `../PROTOCOL.md §8`）。
 *
 * Mac 的平板服务一开就在 HTTP 端口（8770）上广播 [SERVICE_TYPE]，TXT 里只有配对码的**指纹** `tk`，
 * 没有配对码本身（2026-10-10 用户定）：没配过的平板看得到、连不上，仍要扫码。
 * 平板拿本机存的配对码算指纹去比（[match]），就知道「直接能连 / 码已重置要重扫 / 没配过」。
 *
 * 用系统的 `NsdManager`（走系统的 mDNS 服务，不需要组播锁、不需要额外权限）。
 * 发现到的服务要逐个**解析**才拿得到地址和 TXT；老系统同一时间只许解析一个，这里排成串行队列。
 * 只收 IPv4：输入板上其它地方一律按 `http://<host>:8770` 拼地址，IPv6 要加方括号，没必要为它全改一遍。
 *
 * 线程：`NsdManager` 的回调在它自己的线程上，这里一律 post 回主线程再改状态；[found] / [failed]
 * 只在主线程读。每 [start] 一轮 [gen] 加一，上一轮迟到的回调直接丢掉。打点 logcat `UniReader/Discover`。
 */
class MacDiscovery(ctx: Context, private val onChange: () -> Unit) {

    companion object {
        const val TAG = "UniReader/Discover"

        /** ↔ Mac `LANServer.bonjourType`；Mac 的 Info.plist `NSBonjourServices` 列的也是它 */
        const val SERVICE_TYPE = "_unireader._tcp"

        /** 老系统「已经有一个在解析」时的重试间隔与次数 */
        private const val RETRY_MS = 300L
        private const val RETRY_MAX = 5

        /** NsdManager.FAILURE_ALREADY_ACTIVE */
        private const val ALREADY_ACTIVE = 3

        /**
         * 配对码指纹：SHA-256(token 的 UTF-8) 十六进制前 8 位 ↔ Mac `Pairing.fingerprint`。
         * 跨端向量见 `MacDiscoveryTest.fingerprintVectors`（Mac 侧 `spike/pairing-fp-test.swift`）。
         */
        fun fingerprint(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                .take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /**
         * DNS-SD 服务名的转义还原：有的系统版本交回来的名字里空格是 `\032`、中文是一串 `\231\154\132`
         * （UTF-8 逐字节的十进制），`\.` 这类是反斜杠后面那个字符原样。没有反斜杠就原样返回。
         */
        internal fun unescapeName(raw: String): String {
            if ('\\' !in raw) return raw
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < raw.length) {
                if (raw[i] == '\\' && i + 1 < raw.length) {
                    val d = raw.substring(i + 1, minOf(i + 4, raw.length))
                    if (d.length == 3 && d.all { it in '0'..'9' } && d.toInt() <= 255) {
                        out.write(d.toInt())
                        i += 4
                        continue
                    }
                    i++
                }
                val cp = raw.codePointAt(i)
                val b = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                out.write(b, 0, b.size)
                i += Character.charCount(cp)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        /**
         * 发现到的这台 Mac 和「连过的 Mac」对号：
         * ① 有一条存的配对码指纹与广播的 `tk` 相同 → [State.READY]，用那条的配对码直接连（IP 换过也认得出）；
         * ② 指纹都对不上、但有一条 IP 相同 → [State.STALE]，Mac 上重置过配对码，要重扫；
         * ③ 都没有 → [State.NEW]，没配过。
         * 广播里没有 `tk`（不该发生，留个退路）时 IP 相同就当能连，连不上照旧会 authFail。
         */
        internal fun match(f: Found, known: List<KnownMacs.Entry>): Match {
            if (f.tk.isNotEmpty()) {
                known.firstOrNull { fingerprint(it.token) == f.tk }?.let { return Match(State.READY, it) }
            }
            known.firstOrNull { it.host == f.host }?.let {
                return Match(if (f.tk.isEmpty()) State.READY else State.STALE, it)
            }
            return Match(State.NEW, null)
        }
    }

    /** 一台发现到的 Mac。[serviceName] 是 Bonjour 原名（去重 / 丢失按它），[name] 是还原转义后给人看的 */
    data class Found(val serviceName: String, val name: String, val host: String, val tk: String)

    enum class State { READY, STALE, NEW }

    class Match(val state: State, val entry: KnownMacs.Entry?)

    /** 当前在线的，按名字排好（主线程读） */
    var found: List<Found> = emptyList()
        private set

    /** 这一轮查找没能启动（系统拒绝 / 抛异常）——界面据此不再显示「正在查找」 */
    var failed = false
        private set

    private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())

    private var listener: NsdManager.DiscoveryListener? = null
    private var gen = 0

    /** 以下只在主线程碰 */
    private val alive = HashSet<String>()            // 已发现、还没丢失的服务名
    private val byName = HashMap<String, Found>()    // 已解析好的
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    val running: Boolean get() = listener != null

    fun start() {
        if (listener != null) return
        val g = ++gen
        alive.clear(); byName.clear(); pending.clear()
        resolving = false
        failed = false
        publish()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "开始查找 $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "停止查找 $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "查找启动失败 code=$errorCode")
                main.post {
                    if (g != gen) return@post
                    listener = null
                    failed = true
                    publish()
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "停止查找失败 code=$errorCode")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                Log.i(TAG, "发现 ${info.serviceName}")
                main.post { if (g == gen) enqueue(info) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                Log.i(TAG, "下线 ${info.serviceName}")
                main.post { if (g == gen) lost(info.serviceName) }
            }
        }
        listener = l
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "查找启动异常", e)
            listener = null
            failed = true
            publish()
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        gen++
        try {
            nsd.stopServiceDiscovery(l)
        } catch (e: IllegalArgumentException) {
            // 启动失败的那一轮系统里没登记这个 listener，停它会抛；没在跑就是停了
            Log.i(TAG, "停止查找：listener 未登记（${e.message}）")
        }
    }

    private fun enqueue(info: NsdServiceInfo) {
        alive += info.serviceName
        if (pending.none { it.serviceName == info.serviceName }) pending.addLast(info)
        pump()
    }

    private fun lost(serviceName: String) {
        alive -= serviceName
        pending.removeAll { it.serviceName == serviceName }
        if (byName.remove(serviceName) != null) publish()
    }

    private fun pump(retry: Int = 0) {
        if (resolving) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        val g = gen
        @Suppress("DEPRECATION")   // API 34 起推荐 registerServiceInfoCallback；这里只要一次性的地址 + TXT，resolve 各版本都能用
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                main.post {
                    if (g != gen) return@post
                    resolving = false
                    if (errorCode == ALREADY_ACTIVE && retry < RETRY_MAX) {
                        pending.addFirst(info)
                        main.postDelayed({ if (g == gen) pump(retry + 1) }, RETRY_MS)
                        return@post
                    }
                    Log.w(TAG, "解析失败 ${si.serviceName} code=$errorCode")
                    pump()
                }
            }

            override fun onServiceResolved(si: NsdServiceInfo) {
                val f = toFound(si)
                main.post {
                    if (g != gen) return@post
                    resolving = false
                    // 解析期间已经下线的不收，否则会留一条连不上的
                    if (f != null && f.serviceName in alive) {
                        Log.i(TAG, "解析 ${f.name} → ${f.host} tk=${f.tk}")
                        byName[f.serviceName] = f
                        publish()
                    }
                    pump()
                }
            }
        })
    }

    private fun toFound(si: NsdServiceInfo): Found? {
        val v4 = if (Build.VERSION.SDK_INT >= 34) {
            si.hostAddresses.firstOrNull { it is Inet4Address }
        } else {
            @Suppress("DEPRECATION")
            si.host as? Inet4Address
        }
        val host = v4?.hostAddress
        if (host.isNullOrEmpty()) {
            Log.i(TAG, "${si.serviceName} 没有 IPv4 地址，跳过")
            return null
        }
        val tk = si.attributes["tk"]?.let { String(it, Charsets.UTF_8) }.orEmpty()
        return Found(si.serviceName, unescapeName(si.serviceName), host, tk)
    }

    private fun publish() {
        found = byName.values.sortedBy { it.name.lowercase() }
        onChange()
    }
}
