package com.xvan.unireader.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.max

/**
 * 工作区里的图片本体（`../docs/IMAGE-NOTE-PLAN.md §2.1`，Mac `Sources/Store/ImageAssets.swift` 的同款规则）：
 * `<工作区>/Images/<sha256>.<ext>`，**按内容寻址**（同一张图导两次只落一个文件）。
 *
 * - png / jpg / gif / webp 且长边 ≤ 4096 → **原字节原样**存（sha 就是原文件的 sha）；
 * - 其它格式（HEIC / BMP…）或超限 → 按 EXIF 方向转正、缩到长边 4096、转 PNG 再存；
 * - 落盘 `.part` 临时名 + 改名（中途断电不留半个文件），同 sha 已在就不重写。
 *
 * 纯文件层，不碰库；登记 `image` 表行、对账引用由调用方经 `LibraryStore` 做。
 */
object ImageAssets {
    const val TAG = "UniReader/Image"
    const val FOLDER = "Images"
    const val MAX_LONG_EDGE = 4096
    private val PASSTHROUGH = mapOf(
        "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp",
    )
    /** 文件名后缀认得出的图片（目录浏览器挑图用） */
    val PICKABLE = setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp")

    class Prepared(val data: ByteArray, val sha256: String, val ext: String, val width: Int, val height: Int)

    fun folder(ws: File) = File(ws, FOLDER)

    fun file(ws: File, sha256: String, ext: String) = File(folder(ws), "$sha256.$ext")

    fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** EXIF 方向（只有 JPEG / HEIF 带；读不出 = 正常方向） */
    private fun orientation(b: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(b)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** 5~8 = 转了 90°，宽高对调（同 Mac `pixelSize`） */
    private fun swaps(o: Int) = o in 5..8

    /** 把任意能解的图片字节整理成可入库的形态；解不出来（不是图）→ null */
    fun prepare(b: ByteArray): Prepared? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val ori = orientation(b)
        val w = if (swaps(ori)) o.outHeight else o.outWidth
        val h = if (swaps(ori)) o.outWidth else o.outHeight
        val ext = PASSTHROUGH[o.outMimeType]
        if (ext != null && max(w, h) <= MAX_LONG_EDGE) return Prepared(b, sha256(b), ext, w, h)
        val bmp = decode(b, MAX_LONG_EDGE) ?: return null
        val png = encodePng(bmp)
        Log.i(TAG, "转 PNG：${o.outMimeType} ${o.outWidth}×${o.outHeight} → ${bmp.width}×${bmp.height}")
        return Prepared(png, sha256(png), "png", bmp.width, bmp.height)
    }

    /** PDF 节选那条路拿到的是现成的位图，直接编 PNG */
    fun prepare(bmp: Bitmap): Prepared {
        val png = encodePng(bmp)
        return Prepared(png, sha256(png), "png", bmp.width, bmp.height)
    }

    private fun encodePng(bmp: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** 落盘（幂等：同 sha 已在就不重写）。返回最终文件 */
    fun write(p: Prepared, ws: File): File {
        val dst = file(ws, p.sha256, p.ext)
        if (dst.isFile) return dst
        folder(ws).mkdirs()
        val tmp = File(folder(ws), ".${dst.name}.part")
        tmp.delete()
        tmp.writeBytes(p.data)
        dst.delete()
        if (!tmp.renameTo(dst)) {
            tmp.delete()
            throw java.io.IOException("改名失败：${tmp.path} → ${dst.path}")
        }
        return dst
    }

    fun remove(ws: File, sha256: String, ext: String) {
        file(ws, sha256, ext).delete()
    }

    /** 找这张图的文件：先按登记的扩展名，再逐个试常见的（`image` 表缺行时的兜底，同画板读图） */
    fun find(ws: File, sha256: String, ext: String?): File? =
        (listOfNotNull(ext) + listOf("png", "jpg", "gif", "webp", "jpeg")).distinct()
            .map { file(ws, sha256, it) }.firstOrNull { it.isFile }

    /**
     * 解码成位图：长边不超过 [maxSide]（先按 2 的幂降采样、再精确缩），**按 EXIF 方向转正**
     * （手机拍的 JPEG 原字节存进来时方向信息还在 EXIF 里，`BitmapFactory` 不管它，不转就是躺着的）。
     */
    fun decode(b: ByteArray, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val raw = try {
            BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "解图内存不够（${o.outWidth}×${o.outHeight}）", e)
            null
        } ?: return null
        val m = Matrix()
        when (orientation(b)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val long = max(raw.width, raw.height)
        if (long > maxSide) {
            val s = maxSide.toFloat() / long
            m.postScale(s, s)
        }
        if (m.isIdentity) return raw
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return out
    }

    /** 读文件解成位图（后台线程上调） */
    fun load(f: File, maxSide: Int): Bitmap? = runCatching { decode(f.readBytes(), maxSide) }
        .onFailure { Log.w(TAG, "读图失败 ${f.name}", it) }.getOrNull()

    /** 像素尺寸按显示方向（缩略图排版用，不解像素） */
    fun pixelSize(f: File): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val ori = runCatching { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        return if (swaps(ori)) o.outHeight to o.outWidth else o.outWidth to o.outHeight
    }
}
