package com.xvan.unireader.pad

import android.content.Context
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 连过的 Mac（模式2「历史设备」）。
 *
 * **为什么要有**：连接弹窗原先只记住**最后一次**成功的 host/token，家里/办公室两台 Mac 来回换就得
 * 重新扫码或者背 IP。这里把每次 authOK 的连接参数按机器存下来，弹窗里点一下就连。
 *
 * **设备名从哪来**：`GET http://host:8770/info` → `{"name":"xVan 的 MacBook Pro","hostName":"xxx.local"}`
 * （Mac 侧 `LANServer.route`）。走 HTTP 而不是往 authOK 里加字段，是因为线格式动一个字节就要三端
 * 同步 + 重出字节向量（`PROTOCOL.md` 开头的红线），而这只是一句展示用的文本。**探测失败不是错误**：
 * 名字留空，列表退回显示 IP（[Entry.label]），下次连上再补。
 *
 * **token 只在 Mac 上「重置配对码」后失效**：Mac 侧配对码是持久的（`Pairing.persistentToken`，2026-08-12 起）。
 * 重置后这里存的 token 就不认了 → authFail，那时弹窗会带着 host 重新弹出来，扫一下码即可；
 * 历史条目本身不删（IP 通常没变，重扫只是补 token）。Mac 在线时启动页的局域网发现（[MacDiscovery]）
 * 会按配对码指纹提前标出「码已重置」，也会按指纹认出换了 IP 的同一台 Mac。
 */
object KnownMacs {

    const val TAG = "UniReader/Macs"

    /** 与 host/token/showGraph 同一份 prefs：都是「连接设置」这一件事 */
    private const val PREFS = "conn"
    private const val KEY = "devices"

    /** 存太多没意义：能连的 Mac 就那么几台，超了从最旧的开始丢 */
    const val MAX = 8

    /** `/info` 探测超时：局域网内没响应就是没响应，别让请求挂着拖到下一次连接 */
    private const val PROBE_TIMEOUT_S = 3L

    class Entry(val host: String, val token: String, val name: String, val at: Long) {
        /** 列表主行：`/info` 没探到名字时退回 IP，别显示一行空白 */
        val label: String get() = name.ifEmpty { host }
    }

    // ---------- 名单规则（纯函数，不碰 Context / 时钟：`KnownMacsTest` 直接验这一层） ----------

    internal fun parse(raw: String?): List<Entry> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val host = o.optString("host")
                if (host.isEmpty()) null
                else Entry(host, o.optString("token"), o.optString("name"), o.optLong("at"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "历史设备解析失败，按空处理", e)
            emptyList()
        }
    }

    /** 存的时候就截到 [MAX]：读的一侧不必再考虑「其实还有更多」 */
    internal fun serialize(list: List<Entry>): String {
        val arr = JSONArray()
        for (e in list.take(MAX)) {
            arr.put(
                JSONObject()
                    .put("host", e.host)
                    .put("token", e.token)
                    .put("name", e.name)
                    .put("at", e.at),
            )
        }
        return arr.toString()
    }

    /**
     * 记一次成功连接：同一个 host 只留一条、挪到最前，并**保留已探到的名字**——
     * 不然每次重连列表都会先闪回 IP、等 `/info` 回来才变成机器名。
     */
    internal fun remembered(old: List<Entry>, host: String, token: String, now: Long): List<Entry> {
        val name = old.firstOrNull { it.host == host }?.name ?: ""
        return listOf(Entry(host, token, name, now)) + old.filter { it.host != host }
    }

    /**
     * `/info` 探测回来后补名字。
     *
     * 同时**清掉同名但 IP 不同的旧条目**：DHCP 换个地址就多一条僵尸记录（token 还是旧的，点了必失败），
     * 攒几次列表就没法看了。代价是同一局域网里两台**电脑名称完全相同**的 Mac 会互相顶掉——
     * 真撞上了重扫一次码就回来，比一直堆死条目划算。
     *
     * 名字为空（探测失败）或该 host 已不在名单里（用户刚删掉）时原样返回，调用方据此跳过写盘。
     */
    internal fun renamed(old: List<Entry>, host: String, name: String): List<Entry> {
        if (name.isEmpty()) return old
        val cur = old.firstOrNull { it.host == host } ?: return old
        if (cur.name == name && old.none { it.host != host && it.name == name }) return old
        val kept = old.filter { it.host != host && it.name != name }
        for (e in old) {
            if (e.host != host && e.name == name) Log.i(TAG, "同名旧地址下线：${e.host} → $host（$name）")
        }
        return listOf(Entry(host, cur.token, name, cur.at)) + kept
    }

    // ---------- prefs 出入口 ----------

    fun list(ctx: Context): List<Entry> = parse(prefs(ctx).getString(KEY, null))

    /** 在 authOK 里调 */
    fun remember(ctx: Context, host: String, token: String) {
        val next = remembered(list(ctx), host, token, System.currentTimeMillis())
        save(ctx, next)
        val name = next.first().name
        Log.i(TAG, "记入历史设备：$host（名字=${name.ifEmpty { "未知" }}，共 ${minOf(next.size, MAX)} 条）")
    }

    /** `/info` 回来后调；名字没变（或没探到）就不写盘 */
    fun setName(ctx: Context, host: String, name: String) {
        val old = list(ctx)
        val next = renamed(old, host, name)
        if (next === old) return
        save(ctx, next)
        Log.i(TAG, "历史设备改名：$host → $name")
    }

    fun forget(ctx: Context, host: String) {
        val next = list(ctx).filter { it.host != host }
        save(ctx, next)
        Log.i(TAG, "移除历史设备：$host（剩 ${next.size} 条）")
    }

    private fun save(ctx: Context, list: List<Entry>) {
        prefs(ctx).edit().putString(KEY, serialize(list)).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- 机器名探测 ----------

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 问 Mac 要机器名。`cb` 在 **OkHttp 后台线程**回调（同 [PageFetcher] 的约定），
     * 调用方自己切主线程；探不到就回空串，绝不抛。
     */
    fun probeName(host: String, cb: (String) -> Unit) {
        val req = Request.Builder().url("http://$host:8770/info").build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i(TAG, "/info 探测失败（$host），历史列表先显示 IP：${e.message}")
                cb("")
            }

            override fun onResponse(call: Call, response: Response) {
                val name = response.use { r ->
                    if (!r.isSuccessful) return@use ""
                    try {
                        val o = JSONObject(r.body.string())
                        // 「电脑名称」优先（人一眼认得出）；空了退回真主机名，去掉 mDNS 的 .local 尾巴
                        o.optString("name").ifEmpty {
                            o.optString("hostName").removeSuffix(".local")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "/info 返回无法解析（$host）", e)
                        ""
                    }
                }
                cb(name)
            }
        })
    }
}
