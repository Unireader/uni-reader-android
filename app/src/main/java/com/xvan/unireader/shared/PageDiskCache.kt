package com.xvan.unireader.shared

import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 页图的**磁盘缓存**：把「已经拿到手的那张页图的压缩字节」原样落盘，跨换文档、跨重启、
 * 跨 Mac 掉线都还在（2026-08-29 用户提：「不管 macOS 还是安卓端都可以利用好磁盘缓存」）。
 *
 * ### 为什么必须有它
 * 内存那一层在算术上就不够用：横屏一张目标档页图解码后 26~47MB，而 `LruCache` 的额度是堆的 1/3
 * ——小米 Pad 6 上堆才 256MB（无 `largeHeap`）→ 85MB，**同时只装得下一两张**。换篇文档翻一页，
 * 上一篇的图必然被挤光。而**压缩字节一张才 200~600KB**：500MB 磁盘能装上千页、好几篇书。
 * 于是「切回刚才那篇」从「等 Mac 渲 + 下载 + 解码」缩成「读盘几毫秒 + 解码」。
 *
 * ### 语义
 * - **只存不解释**：键是调用方给的字符串（模式2 = `contentHash/页号@档位`），内容是字节，
 *   本类不认识页图。所以模式1 将来要给 Pdfium 的渲染结果做同款缓存，直接复用。
 * - **LRU 按 `lastModified`**：读到就 `touch` 一下，超额时从最旧的删起（删到 90% 上限，
 *   免得每写一张都要扫一遍目录）。
 * - **放 `cacheDir`**：系统在存储紧张时可以自行清掉，语义正好（丢了只是慢一次，不丢数据）。
 * - 目录体积在后台线程统计（几百个文件的 `length()` 也要几十毫秒，别堵住首屏取图）。
 *
 * 线程：`get`/`put` 由取图的后台线程调用（见 `pad/PageFetcher.load`），**别在主线程调**。
 */
class PageDiskCache(private val dir: File, private val maxBytes: Long) {

    private val lock = Any()
    private var total = 0L

    init {
        Thread {
            runCatching { dir.mkdirs() }
            var t = 0L
            var n = 0
            dir.listFiles()?.forEach { if (it.isFile) { t += it.length(); n++ } }
            synchronized(lock) { total += t }
            Log.i(TAG, "磁盘缓存 $n 张/${t / M}MB，上限 ${maxBytes / M}MB → ${dir.absolutePath}")
            trim()
        }.start()
    }

    /** 命中返回字节并把它标成「刚用过」；未命中/读坏一律 null（丢了只是慢一次） */
    fun get(key: String): ByteArray? {
        val f = File(dir, name(key))
        return try {
            if (!f.isFile) return null
            val b = f.readBytes()
            f.setLastModified(System.currentTimeMillis())   // LRU 的「刚用过」
            b
        } catch (_: Exception) {
            null
        }
    }

    /** 先写临时文件再改名：中途挂掉不会留下半张图被当成好的读出来 */
    fun put(key: String, bytes: ByteArray) {
        val n = name(key)
        val f = File(dir, n)
        if (f.isFile) return
        try {
            val tmp = File(dir, "$n.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { tmp.delete(); return }
            synchronized(lock) { total += bytes.size }
            trim()
        } catch (e: Exception) {
            Log.w(TAG, "写磁盘缓存失败：$n", e)
        }
    }

    fun clear() {
        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
        synchronized(lock) { total = 0 }
    }

    fun stats(): String = synchronized(lock) { "磁盘 ${total / M}MB/${maxBytes / M}MB" }

    /** 超额就从最旧的删起，删到 90% 上限为止 */
    private fun trim() {
        synchronized(lock) { if (total <= maxBytes) return }
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var cur = synchronized(lock) { total }
        val want = maxBytes * 9 / 10
        var dropped = 0
        for (f in files) {
            if (cur <= want) break
            val len = f.length()
            if (f.delete()) { cur -= len; dropped++ }
        }
        synchronized(lock) { total = cur }
        if (dropped > 0) Log.i(TAG, "磁盘缓存超额，删掉最旧的 $dropped 张 → ${cur / M}MB")
    }

    /** 键可能带任意字符（`contentHash` 是 Mac 给的），一律折成 SHA-1 十六进制当文件名 */
    private fun name(key: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }

    private companion object {
        const val TAG = "UniReader/PageDisk"
        const val M = 1024 * 1024
    }
}
