package com.xvan.unireader.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 圆盘图标观感探针（2026-08-07，排查「工具图标偏小」用）：把完整环形盘（4 笔 + 4 工具扇区）
 * 按设备 density 渲染成 PNG 落到 app 专属目录，adb pull 出来逐像素看。
 * 不是回归测试——看完即弃，别进 CI。
 */
@RunWith(AndroidJUnit4::class)
class RadialIconProbeTest {

    @Test
    fun renderRadial() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val density = ctx.resources.displayMetrics.density
        val ov = PadOverlays(density)
        val pens = listOf(
            Pen(31, 111, 235, 1f, 2f, 0),     // ballpoint 蓝
            Pen(225, 55, 55, 1f, 3f, 1),      // fountain 红
            Pen(245, 200, 60, 0.5f, 9f, 2),   // marker 黄
            Pen(60, 60, 60, 1f, 1.5f, 3),     // pencil 灰
        )
        val tool = Pen(0, 0, 0, 1f, 0f, 0)
        val items = pens.map { RadialItem(RK_PEN, it) } +
            RadialItem(RK_ERASE, tool) + RadialItem(RK_PAGE, tool) +
            RadialItem(RK_SCRATCH, tool) + RadialItem(RK_TEXT, tool)

        val w = (420 * density).toInt()
        val h = (560 * density).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)   // 白纸底，同真实使用场景

        // 上半：1:1 原尺寸（无高亮）；下半：2.2 倍放大看图标细节（高亮 = 新建草稿纸）
        ov.drawRadial(c, w / 2f, 170 * density, -1, items)
        c.save()
        c.scale(2.2f, 2.2f)
        ov.drawRadial(c, w / 2f / 2.2f, 420 * density / 2.2f, 6, items)
        c.restore()

        val f = File(ctx.getExternalFilesDir(null), "radial-probe.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("radial probe → ${f.absolutePath} density=$density ${w}x$h")
    }
}
