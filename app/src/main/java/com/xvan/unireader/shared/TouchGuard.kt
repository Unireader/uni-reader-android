package com.xvan.unireader.shared

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/**
 * 双指一帧拆成「平移」和「缩放」两份（2026-09-27 用户：双指滚动不要跟着缩放，但**不要先等一段再判意图**）。
 *
 * 前两版都撤了（`../TODO.md`）：±5% 死区挡不住（向上划两指间距天然变十几个百分点）；`PinchIntent`
 * 先挪够 12dp 再定「滚动 / 缩放」——起手那一段不跟手。这一版**每帧**都算、没有起手判定、不锁定：
 *
 * - `ed` = 这一帧两指间距变了多少，`em` = 两指中点挪了多少（各做一次两帧平滑，去单帧抖动）。
 * - `r = ed / (ed + em)`：纯捏合中点几乎不动 → r≈1；一指按住一指外拉 → r≈0.67；
 *   双指往上滚时手指自然并拢，间距变化只有中点位移的一两成 → r≈0.1~0.2。
 * - 缩放只取 `(d / dPrev)^w`，`w` 由 r 在 [R0, R1] 之间平滑过渡到 0~1。平移照旧 100% 跟中点。
 *
 * 于是「滚的时候缩放被压住、捏的时候照常缩放」是逐帧连续的，同一次手势里先滚再捏也不用抬手。
 * 每次手势结束打一行 `UniReader/Pinch`（手指间距实际变了多少 / 真正缩放了多少 / 中点走了多远），
 * 门槛要调先看这行数据。
 */
class PinchSplit(private val density: Float) {
    private var ed = 0f
    private var em = 0f
    private var w = 0f
    private var frames = 0
    private var rawRatio = 1f
    private var appliedRatio = 1f
    private var midPath = 0f

    fun reset() {
        ed = 0f; em = 0f; w = 0f
        frames = 0; rawRatio = 1f; appliedRatio = 1f; midPath = 0f
    }

    /**
     * 这一帧该乘到 zoom 上的系数。[dPrev]/[d] 是两指间距（px），[dmx]/[dmy] 是中点这一帧的位移（px）。
     * 间距有下限（同原先 d0 的 40dp 下限），两指贴得很近时不至于一点抖动就大幅缩放。
     */
    fun factor(dPrev: Float, d: Float, dmx: Float, dmy: Float): Float {
        val floor = MIN_SPAN * density
        val a = max(floor, dPrev)
        val b = max(floor, d)
        val dm = hypot(dmx, dmy)
        ed = 0.5f * ed + 0.5f * abs(b - a)
        em = 0.5f * em + 0.5f * dm
        // 两样都几乎没动（手指停着的抖动）：沿用上一帧的权重，不让噪声来回拨
        if (ed + em > NOISE * density) w = smooth((ed / (ed + em) - R0) / (R1 - R0))
        val f = (b / a).pow(w)
        frames++
        rawRatio *= b / a
        appliedRatio *= f
        midPath += dm
        return f
    }

    /** 手势结束时调：有过双指帧才记一行 */
    fun logEnd(where: String) {
        if (frames == 0) return
        Log.i(
            TAG,
            "$where 双指 ${frames}帧 指距×${"%.3f".format(rawRatio)} 实际缩放×${"%.3f".format(appliedRatio)} " +
                "中点走了${"%.0f".format(midPath / density)}dp",
        )
        frames = 0
    }

    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    companion object {
        const val TAG = "UniReader/Pinch"
        const val R0 = 0.25f       // r 低于它：这一帧不缩放
        const val R1 = 0.55f       // r 高于它：这一帧完全按指距缩放
        const val NOISE = 0.5f     // dp：间距变化 + 中点位移合计不到这么多，算手指停着
        const val MIN_SPAN = 40f   // dp：两指间距下限
    }
}

/**
 * 笔靠近屏幕时屏蔽手指（2026-09-27 用户：开着双指滚动，笔还没落下时手侧面仍会误触）。
 *
 * 已有的防线只管**笔落下之后**（`activePen`）和大面积接触（`PadConst.PALM`）。手侧面接触面不大、
 * 常常是两处同时着屏，在双指滚动模式下正好被当成双指滚动/捏合。这里补三道：
 *
 * 1. **笔在悬停范围内**（收到 HOVER_ENTER/MOVE，还没 HOVER_EXIT）或刚离开 [GRACE_MS] 以内：新落下的手指一律不认。
 *    悬停中笔停着不动时不一定还有 HOVER_MOVE，所以悬停状态最长认 [HOVER_STALE_MS]，防止某台设备漏发 EXIT
 *    导致手指永久失灵。
 * 2. **手指手势进行中笔靠近或落下**：这次手指手势当误触处理——立即结束、不甩惯性；如果手势开始不到
 *    [REVERT_MS]，把画面还原到手指落下之前（`onRevert`）。
 * 3. **系统判成手掌**（ACTION_CANCEL，或 API 33 起 POINTER_UP 带 FLAG_CANCELED）：同第 2 条。
 *
 * 手指落下时的接触尺寸一并打点（`UniReader/Palm`），手侧面到底多大、要不要调 `PALM`，看这行数据。
 */
class PenProximity {
    private var hovering = false
    private var lastPenT = 0L

    /** 收到笔的悬停事件（onGenericMotionEvent 里调） */
    fun onHover(e: MotionEvent) {
        if (e.pointerCount == 0 || e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) return
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (!hovering) Log.i(TAG, "笔进入悬停范围")
                hovering = true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (hovering) Log.i(TAG, "笔离开悬停范围")
                hovering = false
            }
        }
        lastPenT = SystemClock.uptimeMillis()
    }

    /** 笔落下 / 抬起 / 移动时调，刷新「刚刚还在」的时间 */
    fun onPen() {
        lastPenT = SystemClock.uptimeMillis()
    }

    fun near(): Boolean {
        val dt = SystemClock.uptimeMillis() - lastPenT
        return if (hovering) dt < HOVER_STALE_MS else dt < GRACE_MS
    }

    companion object {
        const val TAG = "UniReader/Palm"
        const val GRACE_MS = 300L          // 笔离开悬停范围 / 抬起后，手指还要再等这么久才认
        const val HOVER_STALE_MS = 5000L   // 悬停中这么久没收到笔的事件，就当笔已经走了
        const val REVERT_MS = 1000L        // 手指手势开始不到这么久就被判成误触：画面还原

        /** 这个指针是不是被系统判成手掌取消的 */
        fun canceled(e: MotionEvent): Boolean =
            e.actionMasked == MotionEvent.ACTION_CANCEL ||
                (android.os.Build.VERSION.SDK_INT >= 33 && e.flags and MotionEvent.FLAG_CANCELED != 0)

        fun logFinger(where: String, e: MotionEvent, idx: Int, density: Float, rejected: String?) {
            Log.i(
                TAG,
                "$where 手指落下 触点${e.pointerCount} 接触长${"%.0f".format(e.getTouchMajor(idx) / density)}dp " +
                    "短${"%.0f".format(e.getTouchMinor(idx) / density)}dp" + (rejected?.let { " → 忽略（$it）" } ?: ""),
            )
        }
    }
}
