package com.xvan.unireader.pad

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import com.xvan.unireader.shared.exercise.ExHost
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 模式2 的习题宿主（`../EXERCISE-PLAN.md §10.4`）：题目与作答都只在 Mac，界面（`shared/exercise/ExerciseScreen`）的
 * 每个操作都编成 `exAct` / `exIndex` 发给 Mac（`../PROTOCOL.md §4.9`），Mac 做完回一份 `exDeck`，界面再画。
 *
 * 只有翻题先在本地挪一下（[onLocalGo]，不等一趟来回），Mac 回的 `exIndex` 是同一个位置，不打架。
 * 原题图走 `GET /exercise.png?id=&w=`（Mac 渲，只认平板开着那组里的题），按「题 id + 宽」缓存。
 */
class RemoteExHost(
    private val send: (ByteArray) -> Unit,
    /** 此刻显示的那一组（范围键）；空 = 不在习题上（操作一律不发） */
    private val scope: () -> String,
    /** 连着的那台 Mac（取原题图用） */
    private val host: () -> String,
) : ExHost {

    /** 本地先翻过去（不等 Mac 回 `exIndex`）。 */
    var onLocalGo: (Int) -> Unit = {}
    /** 「到 PDF 原页」发出去之后：平板离开自己的习题标签、回到跟随 Mac（Mac 会把平板锁到那一篇）。 */
    var onOpenedSource: () -> Unit = {}

    private val client = OkHttpClient()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun act(act: Int, id: String = "", arg: String = "", num: Long = 0, ids: List<String> = emptyList()) {
        val sc = scope()
        if (sc.isEmpty()) return
        send(WireCodec.encodeExAct(sc, act, id, arg, num, ids))
    }

    override fun submit(id: String, keys: String, elapsedMs: Long) =
        act(WireCodec.EX_ACT_SUBMIT, id, keys, elapsedMs.coerceIn(0L, 0xFFFF_FFFFL))

    override fun setStarred(id: String, on: Boolean) = act(WireCodec.EX_ACT_STAR, id, num = if (on) 1 else 0)

    override fun removeFromMistakes(id: String) = act(WireCodec.EX_ACT_REMOVE_WRONG, id)

    override fun redo(ids: List<String>, from: Int) = act(WireCodec.EX_ACT_REDO, num = from.toLong(), ids = ids)

    override fun setFilter(filter: Int) = act(WireCodec.EX_ACT_FILTER, num = filter.toLong())

    override fun go(index: Int) {
        val sc = scope()
        if (sc.isEmpty()) return
        onLocalGo(index)
        send(WireCodec.encodeExIndex(sc, index))
    }

    override fun openSource(id: String) {
        act(WireCodec.EX_ACT_OPEN_SOURCE, id)
        onOpenedSource()
    }

    override fun loadOriginal(id: String, widthPx: Int, done: (Bitmap?) -> Unit) {
        val key = "$id@$widthPx"
        cache.get(key)?.let { done(it); return }
        val h = host()
        if (h.isEmpty()) { done(null); return }
        Thread {
            val bmp = try {
                val url = "http://$h:8770/exercise.png?id=${java.net.URLEncoder.encode(id, "UTF-8")}&w=$widthPx"
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body.bytes().let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "取原题图失败 ${id.take(8)}", e)
                null
            }
            if (bmp != null) cache.put(key, bmp)
            main.post { done(bmp) }
        }.start()
    }

    /** 换了一台 Mac / 断开：原题图缓存作废（同一个题 id 在另一台 Mac 上可能是别的东西）。 */
    fun clear() = cache.evictAll()

    private companion object {
        const val TAG = "UniReader/ExRemote"
    }
}
