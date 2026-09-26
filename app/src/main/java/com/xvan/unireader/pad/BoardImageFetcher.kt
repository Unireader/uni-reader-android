package com.xvan.unireader.pad

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import com.xvan.unireader.shared.ScratchCanvas
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 画板笔记上的图（模式2）：`GET http://host:8770/image?h=<sha>` → 解码 → 按 sha 缓存。
 * 与 `/page.png` 同级不带 token（`../PROTOCOL.md §4.8`；Mac 只认当前跟随画板上登记过的 sha）。
 *
 * 缓存按 sha（图片内容的哈希）记，所以换画板再回来、或同一张图在两篇画板里各贴一次都不必重下。
 * 额度按字节，放得下十来张 2048px 的图就够——画板上的图比页图小得多也少得多。
 * 回调在后台线程（[ScratchCanvas] 自己 post 回主线程）。
 */
class BoardImageFetcher(private val host: String) : ScratchCanvas.BoardPicSource {

    private val client = OkHttpClient()

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    override fun load(key: String, cb: (Bitmap?) -> Unit) {
        cache.get(key)?.let { cb(it); return }
        Thread {
            val bmp = try {
                val url = "http://$host:8770/image?h=${java.net.URLEncoder.encode(key, "UTF-8")}"
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) null else ScratchCanvas.decodePic(resp.body.bytes())
                }
            } catch (e: Exception) {
                Log.w(TAG, "取画板图片失败 ${key.take(8)}", e)
                null
            }
            if (bmp != null) cache.put(key, bmp)
            Log.i(TAG, "画板图片 ${key.take(8)} → ${bmp?.let { "${it.width}x${it.height}" } ?: "失败"}")
            cb(bmp)
        }.start()
    }

    fun clear() = cache.evictAll()

    private companion object {
        const val TAG = "UniReader/BoardImg"
        const val CACHE_BYTES = 96 * 1024 * 1024
    }
}
