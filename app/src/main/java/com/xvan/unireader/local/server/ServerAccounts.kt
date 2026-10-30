package com.xvan.unireader.local.server

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一台连过的服务器：地址 + 稳定身份 + 名字 + 本机在它那儿的设备号与令牌 */
data class ServerAccount(
    val url: String,
    val serverId: String?,
    val name: String,
    val deviceId: String,
    val token: String,
) {
    fun client(cancel: ServerClient.Cancel? = null) = ServerClient(url, token, cancel)

    /** 给人看的名字：服务器没给名字就用地址的主机部分 */
    val label: String get() = name.ifEmpty { url.substringAfter("://").substringBefore('/') }
}

/**
 * 连过的服务器（同 Mac `ServerAccounts`；方案 `../SERVER-MODE-PLAN.md §2`）。存在本机私有的 SharedPreferences
 * （同「连过的 Mac」那份配对码，`pad/KnownMacs`）；断开 = 只删这一条，不删本机缓存。
 *
 * 找账号**先按身份、再按地址**（§3.7）：服务器换了 IP、这里改了地址之后，缓存里记的旧地址照样认得出是哪台。
 * 只在主线程改（读哪个线程都行，SharedPreferences 自己有锁）。
 */
object ServerAccounts {

    private const val PREFS = "servers"
    private const val KEY = "accounts"

    fun all(ctx: Context): List<ServerAccount> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ServerAccount(
                    o.getString("url"), o.optString("serverId").ifEmpty { null }, o.optString("name"),
                    o.optString("deviceId"), o.getString("token"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(ctx: Context, list: List<ServerAccount>) {
        val arr = JSONArray()
        for (a in list) {
            arr.put(
                JSONObject().put("url", a.url).put("serverId", a.serverId ?: "").put("name", a.name)
                    .put("deviceId", a.deviceId).put("token", a.token),
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** 新连上一台：同身份（或没有身份时同地址）的那条换掉 */
    fun save(ctx: Context, a: ServerAccount) {
        write(ctx, all(ctx).filterNot { same(it, a) } + a)
    }

    fun remove(ctx: Context, a: ServerAccount) {
        write(ctx, all(ctx).filterNot { same(it, a) })
    }

    /** 「更新地址」：用户点了才改（自动跟过去 = 谁广播同一个 id 都能把令牌骗走，§3.7） */
    fun updateUrl(ctx: Context, a: ServerAccount, url: String) {
        write(ctx, all(ctx).map { if (same(it, a)) it.copy(url = url) else it })
    }

    fun find(ctx: Context, serverId: String?, url: String): ServerAccount? {
        val list = all(ctx)
        if (!serverId.isNullOrEmpty()) list.firstOrNull { it.serverId == serverId }?.let { return it }
        return list.firstOrNull { it.url == url }
    }

    private fun same(x: ServerAccount, y: ServerAccount): Boolean =
        if (!x.serverId.isNullOrEmpty() && !y.serverId.isNullOrEmpty()) x.serverId == y.serverId else x.url == y.url
}
