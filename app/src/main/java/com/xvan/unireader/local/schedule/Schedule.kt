package com.xvan.unireader.local.schedule

import com.xvan.unireader.local.store.LibScheduleItem
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * 日程（`../docs/SCHEDULE-PLAN.md`、`../docs/ANDROID-AGENT-PLAN.md §5.9`）的几样纯规则，逐条照 Mac `Sources/App/ScheduleText.swift`
 * （JVM 单测 `ScheduleTest`）。本端没有日程界面，只给 Agent 工具用；表 `schedule_plan` / `schedule_item`（v26）早已建、随同步。
 */
object ScheduleDay {
    /** `YYYY-MM-DD`（本地日历日）。 */
    fun string(d: LocalDate): String = "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)

    fun today(): String = string(LocalDate.now())

    /** 解析严格的 `YYYY-MM-DD`；不是真实日子（2 月 30 日）= null。 */
    fun date(s: String): LocalDate? {
        val p = s.split('-')
        if (p.size != 3 || p[0].length != 4 || p[1].length != 2 || p[2].length != 2) return null
        if (p.any { x -> x.any { !it.isDigit() } }) return null
        return try { LocalDate.parse(s).takeIf { string(it) == s } } catch (e: DateTimeParseException) { null }
    }

    fun isValid(s: String): Boolean = date(s) != null

    fun adding(days: Int, to: String): String? = date(to)?.plusDays(days.toLong())?.let(::string)

    fun days(from: String, to: String): Int? {
        val a = date(from) ?: return null
        val b = date(to) ?: return null
        return ChronoUnit.DAYS.between(a, b).toInt()
    }
}

object ScheduleTime {
    /** 当天第几分钟 → `HH:mm`；-1 = 不定时 → null。 */
    fun string(minute: Int): String? {
        if (minute < 0) return null
        val m = minOf(minute, 24 * 60 - 1)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** `HH:mm`（也认 `H:mm`、`HHmm`）→ 当天第几分钟；格式不对 = null。 */
    fun minutes(s: String): Int? {
        val t = s.trim()
        val parts = t.split(':')
        var h: Int? = null
        var m: Int? = null
        if (parts.size == 2) {
            if (parts[1].length != 2) return null
            h = parts[0].toIntOrNull(); m = parts[1].toIntOrNull()
        } else if (parts.size == 1 && t.length == 4) {
            t.toIntOrNull()?.let { v -> h = v / 100; m = v % 100 }
        }
        val hh = h ?: return null
        val mm = m ?: return null
        if (hh !in 0 until 24 || mm !in 0 until 60) return null
        return hh * 60 + mm
    }

    /** 给 Agent 看的时长简写（同 Mac `durationText`）。 */
    fun durationText(minutes: Int): String {
        if (minutes <= 0) return ""
        val h = minutes / 60
        val m = minutes % 60
        if (h == 0) return "$m min"
        return if (m == 0) "$h h" else "$h h $m min"
    }
}

object ScheduleText {
    const val TITLE_MAX = 200
    const val DETAIL_MAX = 4000

    fun clean(s: String, max: Int): String = s.trim().let { if (it.length > max) it.take(max) else it }

    /** 随新对话第一句话带上的那一行（同 Mac `ScheduleText.agentLine`）；没有日程 = null。 */
    fun agentLine(today: String, todayItems: List<LibScheduleItem>, overdue: Int, plans: Int): String? {
        if (todayItems.isEmpty() && overdue == 0 && plans == 0) return null
        val done = todayItems.count { it.isDone }
        val sb = StringBuilder("Workspace schedule (日程) — today is $today: ${todayItems.size} item(s), $done done")
        if (overdue > 0) sb.append("; $overdue overdue (earlier days, not done)")
        if (plans > 0) sb.append("; $plans plan(s)")
        sb.append(". ")
        val shown = todayItems.take(8).map { m -> "${if (m.isDone) "[x]" else "[ ]"} ${ScheduleTime.string(m.startMin)?.let { "$it " }.orEmpty()}${m.title}" }
        if (shown.isNotEmpty()) sb.append("Today: ").append(shown.joinToString("; ")).append(". ")
        sb.append("Use list_schedule to see it, add_schedule_items to plan, update_schedule_items to reschedule or mark done.")
        return sb.toString()
    }
}
