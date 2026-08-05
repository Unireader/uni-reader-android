package com.xvan.unireader.shared

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `PageCanvasView.setStrokes` 的 **ackRel 判据**（`PROTOCOL.md §4.2`）。
 *
 * 守的是用户报的这个 bug：擦除时「删掉了又出现，过一会才真的被删除」。
 * `strokes` 是全量镜像，而 Mac 每收到一批擦除点就广播一次（`AppModel.inkErase`），于是擦除途中会
 * 连着回来一串**中途快照**，每份都比本地的乐观状态旧。客户端照单全收，已擦掉的笔迹就会被一份份
 * 恢复出来再擦掉。
 *
 * 判据只有一条：**`ackRel >= 本端已发出的最后一个 seqRel` → 含我全部输入，应用；否则丢弃。**
 * 这条判据由真源侧给出，不是客户端猜的——此前试过「按发出批数记账」那种单边对账（靠猜「一批擦除
 * 恰好回一次广播」的隐含契约、还得配超时兜底），翻车两次，补丁史见 `ANDROID-STANDALONE-PLAN.md §9.9`。
 */
class StrokeEchoTest {

    /** 只为把 protected 的 strokes 露出来（真子类是 PadView / LocalCanvasView） */
    private class Probe(ctx: Context) : PageCanvasView(ctx) {
        fun count() = strokes.size
    }

    private val pen = Pen(0, 0, 0, 1f, 8f, 0)
    private fun stroke(n: Int) = Stroke(0L, pen, listOf(Pt3(0.1f * n, 0.5f, 0.8f)))

    /** View 的 invalidate 只能在主线程调 */
    private fun ui(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun probe(): Probe {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        var v: Probe? = null
        ui { v = Probe(ctx) }
        return v!!
    }

    @Test
    fun 落后于本端输入的快照一律丢弃() {
        val p = probe()
        ui { p.setStrokes(emptyList(), ackRel = 20L, sentRel = 20L) }
        assertEquals("基线：追平了就该应用", 0, p.count())

        // 本端已发到 seq 25（擦除帧一批批出去），Mac 才处理到 22/23/24 —— 三份都是中途快照
        ui { p.setStrokes(listOf(stroke(1), stroke(2), stroke(3)), ackRel = 22L, sentRel = 25L) }
        assertEquals("中途快照被应用了（笔迹被恢复出来）", 0, p.count())
        ui { p.setStrokes(listOf(stroke(1), stroke(2)), ackRel = 23L, sentRel = 25L) }
        assertEquals(0, p.count())
        ui { p.setStrokes(listOf(stroke(1)), ackRel = 24L, sentRel = 25L) }
        assertEquals(0, p.count())

        // 追平：这份含本端全部输入，是擦完的最终状态
        ui { p.setStrokes(listOf(stroke(1)), ackRel = 25L, sentRel = 25L) }
        assertEquals("追平了反而没被应用", 1, p.count())
    }

    @Test
    fun ackRel超过本端输入也照应用() {
        // Mac 处理得比本端发得还多（别的客户端也在写、或本端刚重连）——不该卡住
        val p = probe()
        ui { p.setStrokes(listOf(stroke(1), stroke(2)), ackRel = 99L, sentRel = 3L) }
        assertEquals(2, p.count())
    }

    @Test
    fun 没有ackRel时照单全收() {
        // ackRel=0 = 不适用：模式1（真源就在进程内），或模式2 还没建起 UDP 会话。
        // 这条同时是模式1 的回归——它调的是单参数的 setStrokes，判据必须完全不生效。
        val p = probe()
        ui { p.setStrokes(listOf(stroke(1), stroke(2), stroke(3))) }
        assertEquals(3, p.count())
        ui { p.setStrokes(listOf(stroke(1))) }
        assertEquals(1, p.count())
        // 本端 seq 已经很靠前，但对端没会话（ackRel=0）仍不该丢
        ui { p.setStrokes(listOf(stroke(1), stroke(2)), ackRel = 0L, sentRel = 500L) }
        assertEquals(2, p.count())
    }
}
