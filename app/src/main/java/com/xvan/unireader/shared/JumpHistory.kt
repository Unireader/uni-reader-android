package com.xvan.unireader.shared

import kotlin.math.abs

/** 跳转的来源类别——只用来在历史列表里分辨说明，不落库、不上线（同 Mac `JumpKind`）。 */
enum class JumpKind { READING, TOC, SEARCH, LIST }

/**
 * 一条跳转标记 = 一处阅读位置（页 0 起 + 页内比例），口径同阅读进度。[label] 空 = 让界面按页去显示。
 * [id] 只在进程内认行用（[JumpHistory.goTo]）。
 */
data class JumpMark(
    val page: Int,
    val frac: Float,
    val kind: JumpKind,
    val label: String = "",
    val id: Long = nextId(),
) {
    /** 同一处位置：同页且页内比例几乎相同（阈值 1% 页高）。 */
    fun samePlace(o: JumpMark): Boolean = page == o.page && abs(frac - o.frac) < 0.01f

    companion object {
        private var seq = 0L
        @Synchronized private fun nextId(): Long = ++seq
    }
}

/**
 * 一篇文档的跳转历史（浏览器式：一条线性轨迹 + 一个游标），逐条照 Mac `Sources/App/JumpHistory.swift`
 * （本端 `JumpHistoryTest`）。🔴 不落库：它描述的是「这次阅读的来路」，关掉标签就忘。
 */
class JumpHistory {
    var marks: List<JumpMark> = emptyList()
        private set
    /** 当前处在第几条（-1 = 空）。 */
    var cursor = -1
        private set

    val current: JumpMark? get() = marks.getOrNull(cursor)
    val canGoBack: Boolean get() = cursor > 0
    val canGoForward: Boolean get() = cursor >= 0 && cursor + 1 < marks.size

    /**
     * 记一次跳转。[from] = 跳走前停的地方，[to] = 目标。三条规则（同 Mac）：
     * ① 离开点与当前这条不在同一处就先把它垫进去（含历史还是空的）；② 后退到中途再跳，后面的前进分支作废；
     * ③ 落到同一处、或同一个搜索词的连续命中 → 就地更新当前条（保留 id），不新增。
     */
    fun record(from: JumpMark, to: JumpMark) {
        val m = ArrayList(marks)
        if (cursor + 1 < m.size) m.subList(cursor + 1, m.size).clear()        // ②
        if (m.getOrNull(cursor)?.samePlace(from) != true) {                    // ①
            m += from
            cursor = m.size - 1
        }
        val cur = m.getOrNull(cursor)
        if (cur != null && (cur.samePlace(to) || sameSearch(cur, to))) {       // ③
            m[cursor] = to.copy(id = cur.id)
        } else {
            m += to
            cursor = m.size - 1
        }
        val over = m.size - LIMIT
        if (over > 0) {
            m.subList(0, over).clear()
            cursor = maxOf(0, cursor - over)
        }
        marks = m
    }

    fun back(): JumpMark? = if (canGoBack) marks[--cursor] else null

    fun forward(): JumpMark? = if (canGoForward) marks[++cursor] else null

    /** 直接跳到列表里的某一条。 */
    fun goTo(id: Long): JumpMark? {
        val i = marks.indexOfFirst { it.id == id }
        if (i < 0) return null
        cursor = i
        return marks[i]
    }

    companion object {
        /** 最多留多少条（超了从最旧的删）。 */
        const val LIMIT = 60

        /** 同一次搜索的两次命中：词相同，或一方是另一方的前缀（边打字边搜）。 */
        private fun sameSearch(a: JumpMark, b: JumpMark): Boolean {
            if (a.kind != JumpKind.SEARCH || b.kind != JumpKind.SEARCH) return false
            if (a.label == b.label) return true
            if (a.label.isEmpty() || b.label.isEmpty()) return false
            return a.label.startsWith(b.label) || b.label.startsWith(a.label)
        }
    }
}
