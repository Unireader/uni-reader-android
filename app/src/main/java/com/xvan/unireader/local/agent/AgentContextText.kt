package com.xvan.unireader.local.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Agent 上下文（`../docs/ACP-AGENT-PLAN.md §12`、`../docs/ANDROID-AGENT-PLAN.md §5.5`）里的几段文字，
 * 逐条照抄 Mac `Sources/Agent/AgentContextText.swift`：让 Agent 写交接摘要的说明、从回复里取标题与正文、
 * 带进新对话时给 Agent 看的那一块。纯 Kotlin（JVM 单测 `AgentContextTextTest`）。
 */
object AgentContextText {

    /** 「保存上下文」那一轮发给 Agent 的说明（英文，给模型看；放进隐藏上下文块）。🔴 要求第一行是 `# 标题`：存的时候按它切（[parse]）。 */
    val SAVE_INSTRUCTION = """
        The user wants to save this conversation as a handoff context: a summary that another AI agent — possibly a different product, starting from an empty conversation — will read to continue the same work. Write that summary now.
        - Write it in the user's language, as Markdown.
        - The very first line must be "# " followed by a short title for this work (at most 30 characters).
        - Then cover, briefly but concretely: the goal; what has been done and decided so far; the key facts the next agent needs (document titles with document_id, note_ref, page numbers, exercise ids, file names, numbers, formulas); the current state; open questions and next steps; preferences or instructions the user gave.
        - Write only from this conversation. Do not call tools, and do not change anything.
        - Reply with the summary only — nothing before the title line and nothing after the summary.
    """.trimIndent()

    /** 存下的标题：去掉开头的 `#`、多余空白与换行，去掉整体包着的 `**…**`，最长 60 个字（多了截断加省略号）。 */
    fun cleanTitle(raw: String): String {
        var t = raw.trim()
        while (t.startsWith("#")) t = t.substring(1)
        t = t.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (t.startsWith("**") && t.endsWith("**") && t.length > 4) t = t.substring(2, t.length - 2)
        if (t.length > 60) t = t.take(59) + "…"
        return t
    }

    /**
     * 从 Agent 的回复里取标题与正文：第一个 Markdown 标题行（`# …`）就是标题，它后面的全部是正文（标题行前面的客套话丢掉）；
     * 没有标题行 = 第一行非空文字当标题、整段当正文。
     */
    fun parse(reply: String): Pair<String, String> {
        val lines = reply.split("\n")
        val i = lines.indexOfFirst(::isHeading)
        if (i >= 0) return cleanTitle(lines[i]) to lines.drop(i + 1).joinToString("\n").trim()
        val first = lines.firstOrNull { it.isNotBlank() }.orEmpty()
        return cleanTitle(first) to reply.trim()
    }

    private fun isHeading(line: String): Boolean {
        val t = line.trim()
        val hashes = t.takeWhile { it == '#' }.length
        if (hashes !in 1..6) return false
        val rest = t.substring(hashes)
        return rest.startsWith(" ") && rest.isNotBlank()
    }

    /**
     * 带进新对话时给 Agent 看的那一块（英文说明 + 原样的摘要；外面由调用方包进上下文块）。日期 = 本机时区的 yyyy-MM-dd（同 Mac），
     * 给模型看，不本地化。与 Mac 只差一处：「unireader MCP tools」→「the tools」（本端工具就在对话里，不经 MCP）。
     */
    fun handoff(title: String, summary: String, agent: String, id: String, date: Date): String {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
        var by = if (agent.isEmpty()) "" else " by $agent"
        by += " on ${f.format(date)}"
        return "The user attached a saved context (a handoff summary of an earlier conversation, written$by) so that you can " +
            "continue that work. Treat it as background you already know; you do not need to repeat it back. " +
            "If it mentions documents, notes or exercises, use the tools to look at them when needed.\n" +
            "Saved context \"$title\" (context_id $id):\n" +
            summary
    }
}
