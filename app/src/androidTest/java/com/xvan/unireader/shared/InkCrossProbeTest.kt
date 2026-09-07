package com.xvan.unireader.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * 三端笔迹绘制对比工具（`spike/ink-cross/`）的 **安卓出图端**。
 *
 * 读 `androidTest/assets/ink-cross-vectors.json`（Mac 那边由 `run.sh` 拷进来的，与另两端**同一份**
 * 向量），用 [InkRenderer] ——**产品代码本身**——把每条笔画渲成 PNG 落到 app 专属目录，
 * 由 `run.sh` 用 `adb pull` 取回。
 *
 * 🔴 **绝不在这里复刻渲染算法**：复刻一份就等于自己跟自己比，三端分叉照样藏着。
 * 这个测试只做四件事：解析向量、构造 [Stroke]、驱动 [InkRenderer]、落盘。
 *
 * 🔴 **`InkRenderer(2f)` 那个 2 不是随手写的**：安卓的 `pen.w` 是 dp，`buildPage` 的线宽倍率
 * `wScale = density`；而 Mac 端走 `ImageRenderer.scale = 2`、web 端走 `wScale = scale = 2`。
 * 三端都要「笔宽 = w × 2 物理像素」才可比——这里的 density 就是那个 2，**与设备真实 density 无关**
 * （用设备 density 的话，同一份向量在 3x 屏和 2x 屏上出的图粗细不同，比的就成了设备而不是算法）。
 *
 * 它是**探针**不是断言测试：唯一的断言是「图确实画出来了、不是一张白纸」——
 * 差异判读交给 `report.py` 和人眼，测试这一层说不清「像不像」。
 *
 * 跑法（`run.sh` 会自动跑）：
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.xvan.unireader.shared.InkCrossProbeTest
 */
class InkCrossProbeTest {

    /** 页 = 整张位图（viewX/viewY 直接按位图尺寸展开），省掉 PageCanvasView 那一整套几何 */
    private class FullBitmapPage(private val w: Float, private val h: Float) : PageMapper {
        override fun viewX(page: Int, nx: Float) = nx * w
        override fun viewY(page: Int, ny: Float) = ny * h
    }

    @Test
    fun 按共同向量出图() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val json = inst.context.assets.open("ink-cross-vectors.json")
            .bufferedReader().use { it.readText() }
        val doc = JSONObject(json)
        val canvas = doc.getJSONObject("canvas")
        val scale = canvas.getDouble("scale").toFloat()
        val w = (canvas.getDouble("w") * scale).toInt()
        val h = (canvas.getDouble("h") * scale).toInt()

        val outDir = File(inst.targetContext.getExternalFilesDir(null), "ink-cross")
        outDir.deleteRecursively()
        outDir.mkdirs()

        val strokes = doc.getJSONArray("strokes")
        var painted = 0
        for (i in 0 until strokes.length()) {
            val v = strokes.getJSONObject(i)
            val name = v.getString("name")
            val col = v.getJSONObject("color")
            val pen = Pen(
                r = col.getInt("r"), g = col.getInt("g"), b = col.getInt("b"),
                a = col.getDouble("a").toFloat(),
                w = v.getDouble("width").toFloat(),
                brush = brushCode(v.getString("type")),
            )
            val ptsArr = v.getJSONArray("points")
            val pts = (0 until ptsArr.length()).map { k ->
                val p = ptsArr.getJSONArray(k)
                Pt3(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(),
                    if (p.length() > 2) p.getDouble(2).toFloat() else 0.5f)
            }

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            InkRenderer(scale).drawStroke(c, Stroke(0L, pen, pts), FullBitmapPage(w.toFloat(), h.toFloat()))

            FileOutputStream(File(outDir, "$name.png")).use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue("$name：整张图全白，一笔都没画上", hasInk(bmp))
            painted++
        }
        assertTrue("向量表是空的？", painted > 0)
        android.util.Log.i("InkCross", "出图 $painted 张 → ${outDir.absolutePath}")
    }

    /** 抽样扫一遍：只要有一个像素不是纯白就算画上了（抽样步长够小，最细的笔也躲不掉） */
    private fun hasInk(bmp: Bitmap): Boolean {
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                if (bmp.getPixel(x, y) != Color.WHITE) return true
                x += 2
            }
            y += 2
        }
        return false
    }
}
