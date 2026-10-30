package com.xvan.unireader.local.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.xvan.unireader.pad.MacDiscovery
import java.net.Inet4Address

/**
 * 局域网里的 UniReader 服务器（Bonjour `_unireader-srv._tcp`，契约 `../SERVER-MODE-PLAN.md §3.7`）。
 * 只在「连接服务器」面板开着时查找。做法同 `pad/MacDiscovery`（系统 `NsdManager`，逐个解析、老系统串行；只收 IPv4），
 * 公告里**没有配对码**：发现只是帮着填地址，连接照旧要配对码。
 * ⚠️ 组播不跨网段：平板与服务器不在同一网段（经路由 / VPN）时发现不了，手填地址。
 */
class ServerDiscovery(ctx: Context, private val onChange: () -> Unit) {

    companion object {
        const val TAG = "UniReader/Discover"

        /** ↔ 服务器 `Discovery.swift` / Mac `ServerDiscovery.serviceType` */
        const val SERVICE_TYPE = "_unireader-srv._tcp"

        private const val RETRY_MS = 300L
        private const val RETRY_MAX = 5
        private const val ALREADY_ACTIVE = 3
    }

    /** 一台发现到的服务器。[url] = `http://IPv4:端口`；[serverId] 来自 TXT `id`（服务器的稳定身份） */
    data class Found(val serviceName: String, val name: String, val url: String, val serverId: String, val schema: Int?)

    var found: List<Found> = emptyList()
        private set

    private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null
    private var gen = 0

    private val alive = HashSet<String>()
    private val byName = HashMap<String, Found>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        if (listener != null) return
        val g = ++gen
        alive.clear(); byName.clear(); pending.clear()
        resolving = false
        publish()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "查找服务器启动失败 code=$errorCode")
                main.post { if (g == gen) listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    if (g != gen) return@post
                    alive.add(info.serviceName)
                    pending.addLast(info)
                    pump()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (g != gen) return@post
                    alive.remove(info.serviceName)
                    if (byName.remove(info.serviceName) != null) publish()
                }
            }
        }
        listener = l
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "查找服务器启动失败", e)
            listener = null
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        gen++
        runCatching { nsd.stopServiceDiscovery(l) }
    }

    private fun pump(retry: Int = 0) {
        if (resolving) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        val g = gen
        @Suppress("DEPRECATION")   // 同 MacDiscovery：只要一次性的地址 + TXT
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
                    pump()
                }
            }

            override fun onServiceResolved(si: NsdServiceInfo) {
                val f = toFound(si)
                main.post {
                    if (g != gen) return@post
                    resolving = false
                    if (f != null && f.serviceName in alive) {
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
        val host = v4?.hostAddress?.takeIf { it.isNotEmpty() } ?: return null
        fun txt(k: String) = si.attributes[k]?.let { String(it, Charsets.UTF_8) }.orEmpty()
        return Found(
            si.serviceName, MacDiscovery.unescapeName(si.serviceName), "http://$host:${si.port}",
            txt("id"), txt("schema").toIntOrNull(),
        )
    }

    private fun publish() {
        found = byName.values.sortedBy { it.name.lowercase() }
        onChange()
    }
}
