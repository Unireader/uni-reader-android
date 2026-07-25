package com.xvan.unireader.pad

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 页图获取：HTTP GET http://host:8770/page.png?i=N&v=docV → Bitmap（token 不用带，Mac 该路由不校验）。
 * LruCache 按 (i, v) 缓存，换文档（v 变化）时调用方 clear()；支持多页并发预取。
 * cb 在后台线程回调，调用方自行切主线程。
 */
class PageFetcher(private val host: String) {

    private val client = OkHttpClient()
    private val cache = LruCache<String, Bitmap>(16)

    /** 换文档时清缓存（旧 v 的页图全部作废） */
    fun clear() = cache.evictAll()

    fun fetch(index: Int, v: String, cb: (Bitmap?) -> Unit) {
        val key = "$v/$index"
        cache.get(key)?.let { cb(it); return }
        Thread {
            val bmp = try {
                val url = "http://$host:8770/page.png?i=$index&v=${java.net.URLEncoder.encode(v, "UTF-8")}"
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body.byteStream().use { BitmapFactory.decodeStream(it) }
                    } else null
                }
            } catch (_: Exception) {
                null
            }
            if (bmp != null) cache.put(key, bmp)
            cb(bmp)
        }.start()
    }
}
