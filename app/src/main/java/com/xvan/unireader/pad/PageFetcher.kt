package com.xvan.unireader.pad

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 页图获取：HTTP GET http://host:8770/page.png?i=N → Bitmap（token 不用带，Mac 该路由不校验）。
 * 简单 LruCache 缓存最近几页；cb 在后台线程回调，调用方自行切主线程。
 */
class PageFetcher(private val host: String) {

    private val client = OkHttpClient()
    private val cache = LruCache<Int, Bitmap>(8)

    fun fetch(index: Int, cb: (Bitmap?) -> Unit) {
        cache.get(index)?.let { cb(it); return }
        Thread {
            val bmp = try {
                val req = Request.Builder().url("http://$host:8770/page.png?i=$index").build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body.byteStream().use { BitmapFactory.decodeStream(it) }
                    } else null
                }
            } catch (_: Exception) {
                null
            }
            if (bmp != null) cache.put(index, bmp)
            cb(bmp)
        }.start()
    }
}
