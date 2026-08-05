package com.xvan.unireader.shared

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `PageCanvasView.setStrokes` 的**回声记账**（模式2 擦除）。
 *
 * 守的是用户报的这个 bug：擦除时「删掉了又出现，过一会才真的被删除」。
 * 根因在 Mac——`AppModel.inkErase` 每收到一批擦除点就 `broadcastStrokes()` 一次，每份都滞后一个 RTT，
 * 只有**最后一批**的那次回声才是擦完的最终状态。光挡「手势进行中」不够：抬笔时在途还有好几份
 * 中途快照，闸门一开它们挨个应用，笔迹就一份份地被恢复出来再擦掉。
 *
 * 记账口径由 Mac 侧两条事实撑着（改 Mac 前先回来看这里）：
 * ① 每个 `erase phase=move` 消息恰好引来一次广播；② `erase phase=end` **不做任何事、不广播**
 * （`AppModel.swift` 的 `case "erase"` 只认 move）。所以「发了 N 批 → 收到第 N 次回声」= 最终状态。
 */
class StrokeEchoTest {

    /** 只为把 protected 的记账口和 strokes 露出来（真子类是 PadView / LocalCanvasView） */
    private class Probe(ctx: Context) : PageCanvasView(ctx) {
        fun echo() = expectStrokesEcho()
        fun count() = strokes.size
    }

    private val pen = Pen(0, 0, 0, 1f, 8f, 0)
    private fun stroke(n: Int) = Stroke(0L, pen, listOf(Pt3(0.1f * n, 0.5f, 0.8f)))

    /** View 的 invalidate 只能在主线程调 */
    private fun ui(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    fun 擦除中途快照全丢弃只认最后一份() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        var v: Probe? = null
        ui { v = Probe(ctx) }
        val p = v!!

        ui { p.setStrokes(emptyList()) }
        assertEquals("基线：没记账时回推该立刻生效", 0, p.count())

        // 一次擦除手势发出去 3 批擦除帧 → Mac 会回 3 次广播，前两次都是擦到一半的中途快照
        ui { p.echo(); p.echo(); p.echo() }

        ui { p.setStrokes(listOf(stroke(1), stroke(2), stroke(3))) }
        assertEquals("中途快照 1 被应用了（笔迹被恢复出来）", 0, p.count())

        ui { p.setStrokes(listOf(stroke(1), stroke(2))) }
        assertEquals("中途快照 2 被应用了", 0, p.count())

        ui { p.setStrokes(listOf(stroke(1))) }
        assertEquals("最后一份（擦完的最终状态）反而没被应用", 1, p.count())
    }

    @Test
    fun 没记账的回推立刻生效() {
        // 模式1 的 onErase 是空实现（真源就在进程内，没有回声这回事），所以一次账都不该记——
        // 记了就永远等不到人来销，之后所有回推都会被当成中途快照丢掉，笔迹从此不更新。
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        var v: Probe? = null
        ui { v = Probe(ctx) }
        val p = v!!

        ui { p.setStrokes(listOf(stroke(1), stroke(2), stroke(3))) }
        assertEquals(3, p.count())
        ui { p.setStrokes(listOf(stroke(1))) }
        assertEquals(1, p.count())
    }

    @Test
    fun 换文档把没销的账一起作废() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        var v: Probe? = null
        ui { v = Probe(ctx) }
        val p = v!!

        ui { p.echo(); p.echo() }                                   // 两笔账悬着
        ui { p.setPages(2, listOf(1f to 1.4f, 1f to 1.4f), true) }   // 换文档
        ui { p.setStrokes(listOf(stroke(1), stroke(2))) }
        assertEquals("换了文档旧账还在挡新文档的回推", 2, p.count())
    }
}
