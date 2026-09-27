package com.xvan.unireader.shared

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import android.util.Log
import android.view.MotionEvent
import com.xvan.unireader.R
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
    private var penX = 0f          // 最近一次笔的位置（视图坐标 px，落笔 / 移动 / 悬停都算）
    private var penY = 0f
    private var hasPenPos = false

    /** 收到笔的悬停事件（onGenericMotionEvent 里调） */
    fun onHover(e: MotionEvent) {
        if (e.pointerCount == 0 || e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) return
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (!hovering) Log.i(TAG, "笔进入悬停范围")
                hovering = true
                penX = e.getX(0); penY = e.getY(0); hasPenPos = true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (hovering) Log.i(TAG, "笔离开悬停范围")
                hovering = false
            }
        }
        lastPenT = SystemClock.uptimeMillis()
    }

    /** 笔落下 / 移动时调：刷新「刚刚还在」的时间与笔的位置 */
    fun onPen(x: Float, y: Float) {
        penX = x; penY = y; hasPenPos = true
        lastPenT = SystemClock.uptimeMillis()
    }

    /** 笔抬起时调（抬起那一刻的位置已经由最后一次 move 记下） */
    fun onPen() {
        lastPenT = SystemClock.uptimeMillis()
    }

    fun near(): Boolean {
        val dt = SystemClock.uptimeMillis() - lastPenT
        return if (hovering) dt < HOVER_STALE_MS else dt < GRACE_MS
    }

    private fun penAge(): Long = SystemClock.uptimeMillis() - lastPenT

    /**
     * ② 写字时暂停单指滚动：笔最近 [WRITE_PAUSE_MS] 内还在（落笔 / 悬停），这次单指手势不滚动（双指照常）。
     * 写字时笔常常抬出悬停范围，字与字之间的空档比 [GRACE_MS] 长，手背一蹭就被当成单指滚动。
     */
    fun writePaused(): Boolean = PalmSettings.writePause && lastPenT > 0 && penAge() < WRITE_PAUSE_MS

    /**
     * ③ 按手的位置屏蔽：笔最近 [ZONE_MS] 内用过，落在笔尖「书写手那一侧的下方」的手指不认。
     * 右手：笔尖右下方（往左留 [ZONE_BACK] dp、往上留 [ZONE_UP] dp 的余量）；左手镜像。
     * 只挡**单根**手指：两根手指落下间隔不到 [PAIR_MS] 当成主动双指操作放行（由画布判，见各自的 fingerAllowed）——
     * 第一版两根都挡，用户写完字马上双指滚动滚不动。
     */
    fun inPalmZone(x: Float, y: Float, density: Float): Boolean {
        if (!PalmSettings.palmZone || !hasPenPos || penAge() >= ZONE_MS) return false
        if (y < penY - ZONE_UP * density) return false
        return if (PalmSettings.leftHanded) x <= penX + ZONE_BACK * density
               else x >= penX - ZONE_BACK * density
    }

    /**
     * ① 误触不甩惯性：很短促（不到 [SUSPECT_MS]）又划不到 [SUSPECT_TRAVEL] 的单指划动，松手不甩惯性——
     * 手背擦过去那一下速度很高，甩出去就「跑好远」。
     * 🔴 **不看笔最近用没用过**：第一版加了「笔 5s 内用过也算」，写完字正常快速一划被挡住，用户报「不能拖动但图标是亮的」，
     * 09-27 用户定去掉；刚写完字的防护交给 ②③（它们挡的时候小手会变灰）。
     */
    fun flingSuspect(durationMs: Long, travelPx: Float, density: Float): Boolean =
        PalmSettings.flingGuard && durationMs < SUSPECT_MS && travelPx < SUSPECT_TRAVEL * density

    companion object {
        const val TAG = "UniReader/Palm"
        const val GRACE_MS = 300L          // 笔离开悬停范围 / 抬起后，手指还要再等这么久才认
        const val HOVER_STALE_MS = 5000L   // 悬停中这么久没收到笔的事件，就当笔已经走了
        const val REVERT_MS = 1000L        // 手指手势开始不到这么久就被判成误触：画面还原
        // ②③ 与小手恢复的时长 09-27 用户嫌「拿开笔到手指能用有点久」，从 1.5s / 3s / 1.5s 收到下面三个数
        const val WRITE_PAUSE_MS = 600L    // ②：笔最近这么久内用过，单指不滚动
        const val ZONE_MS = 1200L          // ③：笔最近这么久内用过，书写手那一侧的单根手指不认
        const val PAIR_MS = 150L           // ③：两根手指落下间隔不到这么久 = 主动双指操作，屏蔽区放行
        const val ZONE_BACK = 24f          // ③：dp，区域往笔尖另一侧多让出的余量
        const val ZONE_UP = 48f            // ③：dp，区域往笔尖上方多让出的余量
        const val SUSPECT_MS = 200L        // ①：短于这么久的单指划动算「短促」
        const val SUSPECT_TRAVEL = 24f     // ①：dp，短促又划不到这么远 → 不甩惯性

        /**
         * 这个指针是不是被系统判成手掌取消的：只认 `FLAG_CANCELED`（API 33 起，ACTION_CANCEL / POINTER_UP 都可能带）。
         * 🔴 **光是 ACTION_CANCEL 不算**——上层视图抢走手势、系统手势也发 ACTION_CANCEL；第一版把它也当手掌，
         * 画板上双指刚按下 60~100ms 就被取消 + 还原，用户报「不能拖动」（2026-09-27 日志）。
         * 每次取消都打一行动作与 flags，来源不明时看它。
         */
        fun canceled(e: MotionEvent): Boolean {
            val palm = android.os.Build.VERSION.SDK_INT >= 33 && e.flags and MotionEvent.FLAG_CANCELED != 0
            if (palm || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                Log.i(
                    TAG,
                    "收到取消 action=${e.actionMasked}(3=CANCEL 6=POINTER_UP) flags=0x${Integer.toHexString(e.flags)} " +
                        "触点${e.pointerCount} → " + if (palm) "系统判为手掌" else "不是手掌（上层抢走 / 系统手势）",
                )
            }
            return palm
        }

        fun logFinger(where: String, e: MotionEvent, idx: Int, density: Float, rejected: String?) {
            Log.i(
                TAG,
                "$where 手指落下 触点${e.pointerCount} 接触长${"%.0f".format(e.getTouchMajor(idx) / density)}dp " +
                    "短${"%.0f".format(e.getTouchMinor(idx) / density)}dp" + (rejected?.let { " → 忽略（$it）" } ?: ""),
            )
        }
    }
}

/** 手指被挡下的原因：日志用中文短语，界面提示用字符串资源（见 [GuardBadge]） */
enum class Guard(val log: String, val msg: Int) {
    PEN_WRITING("笔在写", R.string.guard_pen_writing),
    PEN_NEAR("笔在附近", R.string.guard_pen_near),
    PALM_ZONE("在书写手的位置", R.string.guard_palm_zone),
    BIG_CONTACT("接触面过大", R.string.guard_big_contact),
    WRITE_PAUSE("刚写过字，单指不滚动", R.string.guard_write_pause),
    TWO_FINGER("双指滚动模式，单指不滚动", R.string.guard_two_finger),
    REJECTED("判为误触", R.string.guard_rejected),
}

/**
 * 一块画布上「此刻手指能不能用」（2026-09-27 用户：手指划了页面不动，要让人知道为什么；
 * 第一版在画布左下角画一行字，被笔编辑器挡住，改成顶栏一只小手，能用正常、不能用变灰，见 [FingerState]）。
 *
 * 不能用 = 刚有手指被挡下 / 判为误触（手指按着时一直算，全部抬起后再算 [LINGER_MS]）、笔在附近、刚写过字。
 * 后两样是随时间自己结束的，所以不能用期间每 [TICK_MS] 自查一次，变回能用就停。
 * 宿主在触摸 / 悬停事件处理完后调 [update]，挡下手指时调 [show]，手全部离屏时调 [release]。
 */
class FingerGate(private val v: View, private val near: PenProximity) {
    private var blocked: Guard? = null
    private var held = false
    private var until = 0L
    private var reported: Guard? = null
    private val tick = Runnable { update() }

    fun show(g: Guard) {
        blocked = g; held = true
        update()
    }

    /** 先前挡下的手指又被放行了（③ 的双指放行）：立即撤掉「不能用」 */
    fun clear() {
        blocked = null; held = false; until = 0L
        update()
    }

    /** 手指全部抬起（或手势作废）时调：再算一会儿不能用就恢复 */
    fun release() {
        if (!held) return
        held = false
        until = SystemClock.uptimeMillis() + LINGER_MS
        update()
    }

    fun update() {
        val b = blocked
        val cur = when {
            b != null && (held || SystemClock.uptimeMillis() < until) -> b
            near.near() -> Guard.PEN_NEAR
            near.writePaused() -> Guard.WRITE_PAUSE
            else -> null
        }
        if (b != null && cur != b) blocked = null
        if (cur != reported) {
            reported = cur
            FingerState.report(this, cur)
        }
        v.removeCallbacks(tick)
        if (cur != null) v.postDelayed(tick, TICK_MS)
    }

    /** 画布离开窗口：别再占着「不能用」 */
    fun detach() {
        v.removeCallbacks(tick)
        reported = null
        FingerState.report(this, null)
    }

    companion object {
        const val LINGER_MS = 500L
        const val TICK_MS = 100L
    }
}

/**
 * 顶栏「小手」键的数据源：各块画布（页内 / 草稿纸 / 画板，模式1 还有多个标签页）各报各的，
 * 任何一块说不能用就算不能用。宿主（当前那个 Activity）设 [listener] 去改键的颜色。
 */
object FingerState {
    private val src = LinkedHashMap<Any, Guard>()
    var listener: ((Guard?) -> Unit)? = null
    var current: Guard? = null
        private set

    fun report(who: Any, g: Guard?) {
        if (g == null) src.remove(who) else src[who] = g
        val c = src.values.lastOrNull()
        if (c == current) return
        current = c
        Log.i(PenProximity.TAG, "手指状态：" + (c?.log ?: "可用"))
        listener?.invoke(c)
    }
}

/**
 * 防误触的几个开关（2026-09-27 用户要求每条都能单独关）：本机偏好，**两模式共用一份**，存 SharedPreferences `palm`。
 * 页内与草稿纸 / 画板的 [PenProximity] 都直接读这里，宿主不用逐个画布抄一遍。
 */
object PalmSettings {
    private const val PREFS = "palm"

    var flingGuard = true    // ① 误触不甩惯性
    var writePause = true    // ② 写字时暂停单指滚动
    var palmZone = true      // ③ 按手的位置屏蔽
    var leftHanded = false   // ③ 用：左手书写（区域镜像到笔尖左下方）

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        flingGuard = p.getBoolean("flingGuard", true)
        writePause = p.getBoolean("writePause", true)
        palmZone = p.getBoolean("palmZone", true)
        leftHanded = p.getBoolean("leftHanded", false)
        Log.i(
            PenProximity.TAG,
            "防误触设置：不甩惯性=$flingGuard 写字暂停单指=$writePause 手的位置屏蔽=$palmZone 左手=$leftHanded",
        )
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("flingGuard", flingGuard)
            .putBoolean("writePause", writePause)
            .putBoolean("palmZone", palmZone)
            .putBoolean("leftHanded", leftHanded)
            .apply()
    }

    /** 顶栏登记四颗开关键（两模式同一份；默认收在 ⋯ 里，见 ToolLayout.MENU_KEYS）。改完即存，[onChange] 让宿主刷 HUD */
    fun icons(bar: TopBar, ctx: Context, onChange: () -> Unit) {
        fun flip(set: () -> Unit) { set(); save(ctx); onChange() }
        bar.icon("flingGuard", R.drawable.ic_fling_guard, ctx.getString(R.string.tools_fling_guard), toggle = true) {
            flip { flingGuard = !flingGuard }
        }
        bar.icon("writePause", R.drawable.ic_write_pause, ctx.getString(R.string.tools_write_pause), toggle = true) {
            flip { writePause = !writePause }
        }
        bar.icon("palmZone", R.drawable.ic_palm_zone, ctx.getString(R.string.tools_palm_zone), toggle = true) {
            flip { palmZone = !palmZone }
        }
        // 左手只对「按手的位置屏蔽」有意义：那条关着时拿走（或按「显示暂时不能用的按钮」灰着）
        bar.icon(
            "leftHand", R.drawable.ic_left_hand, ctx.getString(R.string.tools_left_hand),
            toggle = true, available = { palmZone },
        ) {
            flip { leftHanded = !leftHanded }
        }
    }

    /**
     * 顶栏「小手」键（默认在右侧固定的「状态」组）：手指能用时正常，不能用时变灰（[FingerState]）。
     * 点一下说一句当前是什么状态、为什么。宿主在 buildUi 里调一次，同时把 [FingerState.listener] 接到这块顶栏上。
     */
    fun fingerIcon(bar: TopBar, ctx: Context) {
        bar.icon("fingerState", R.drawable.ic_hand, ctx.getString(R.string.tools_finger_state)) {
            val g = FingerState.current
            Toast.makeText(ctx, ctx.getString(g?.msg ?: R.string.guard_ok), Toast.LENGTH_SHORT).show()
        }
        val tint = { g: Guard? ->
            // 变灰 = 顶栏前景色压到 35% 透明（同 TopBar.setEnabled 的淡法），但键照样能点（点了说原因）
            bar.setTint("fingerState", g?.let { ColorUtils.setAlphaComponent(Ui.barOn(ctx), 90) })
        }
        FingerState.listener = tint
        tint(FingerState.current)
    }

    /** 刷 HUD 时调：四颗键的开关底色 */
    fun setActive(bar: TopBar) {
        bar.setActive("flingGuard", flingGuard)
        bar.setActive("writePause", writePause)
        bar.setActive("palmZone", palmZone)
        bar.setActive("leftHand", leftHanded)
    }
}
