package com.xvan.unireader.local.agent

import android.app.Activity
import android.graphics.Typeface
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.xvan.unireader.R
import com.xvan.unireader.local.store.LibAgentMemory
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.exercise.MathText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 阅读界面右侧的 Agent 面板（`../docs/ANDROID-AGENT-PLAN.md §6`，用户 2026-11-15 定「阅读界面右侧抽屉」）。
 * 对应 Mac Inspector 的「Agent」页，只是平板上没有 Inspector，做成贴右边满高的一栏。
 *
 * **不盖遮罩**：面板外面照常能翻书、写字（边看边聊）；整个覆盖层不吃触摸，只有面板本身吃。
 * 对话不落库（同 Mac D4：本轮不存对话记录）；记忆在每段新对话的第一句话里自动带上（同 Mac §21）。
 */
class AgentPanel(private val a: Activity, private val host: AgentHost) {

    companion object {
        const val TAG = "UniReader/Agent"
        private const val MAX_W = 440
        private const val WIDTH_RATIO = 0.92f
    }

    private val scope = MainScope()
    private var engine: AgentEngine? = null
    /** 引擎是按哪份设置建的（设置改了就重建、对话清空） */
    private var engineKey = ""
    private var job: Job? = null
    /** 这段对话还没发过消息（第一句带上记忆） */
    private var fresh = true

    private val transcript = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    private val scroll = ScrollView(a).apply {
        isFillViewport = true
        addView(transcript, FrameLayout.LayoutParams(-1, -2))
    }
    private val input = EditText(a).apply {
        hint = a.getString(R.string.agent_input_hint)
        textSize = 15f
        setTextColor(Ui.onSurface(a))
        setHintTextColor(Ui.onVariant(a))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 2
        maxLines = 6
        gravity = Gravity.TOP or Gravity.START
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background = Ui.round(android.graphics.Color.TRANSPARENT, Ui.RADIUS, a, Ui.outline(a))
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    private val sendBtn = Ui.button(a, a.getString(R.string.agent_send), filled = true) { onSendOrStop() }

    private val panel = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ui.surface(a))
        isClickable = true   // 吃掉面板上的触摸，别穿到下面的画布
        addView(
            LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(6), dp(6), dp(6))
                addView(Ui.title(a, "Agent", 18f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(Ui.iconButton(a, R.drawable.ic_plus, a.getString(R.string.agent_new_chat), Ui.onSurface(a)) { newChat() })
                addView(Ui.iconButton(a, R.drawable.ic_more, a.getString(R.string.agent_settings), Ui.onSurface(a)) { showSettings() })
                addView(Ui.iconButton(a, R.drawable.ic_close, a.getString(R.string.agent_close), Ui.onSurface(a)) { close() })
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        addView(Ui.divider(a))
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(Ui.divider(a))
        addView(
            LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                setPadding(dp(12), dp(8), dp(12), dp(8))
                addView(input, LinearLayout.LayoutParams(0, -2, 1f))
                addView(sendBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        // 系统栏 + 输入法：edge-to-edge 下窗口不会因为键盘缩小，自己按 ime 的高度垫底边
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, ins ->
            val bars = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = ins.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(0, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            ins
        }
    }

    /** 整个覆盖层（加进 Activity 的 root，满屏但不吃触摸），面板贴右边。 */
    val view: FrameLayout = FrameLayout(a).apply {
        visibility = View.GONE
        val dm = a.resources.displayMetrics
        val w = min((dm.widthPixels * WIDTH_RATIO).toInt(), dp(MAX_W))
        addView(panel, FrameLayout.LayoutParams(w, -1, Gravity.END))
        panel.elevation = Ui.dpf(a, 8f)
    }

    val isOpen: Boolean get() = view.visibility == View.VISIBLE

    fun toggle() = if (isOpen) close() else open()

    fun open() {
        view.visibility = View.VISIBLE
        ViewCompat.requestApplyInsets(panel)
        if (transcript.childCount == 0) showEmptyState()
    }

    /** 关上之后（宿主刷顶栏那颗键的开关态） */
    var onClosed: () -> Unit = {}

    fun close() {
        view.visibility = View.GONE
        hideKeyboard()
        onClosed()
    }

    /** 换工作区：对话是挂在工作区上的（工具读的是它），清空重来。 */
    fun workspaceChanged() {
        job?.cancel()
        engine?.close(); engine = null
        engineKey = ""
        conversationId = newConversationId()
        clearTranscript()
    }

    /** 这段对话的 id（`agent_turn.conversation_id`）：新对话 / 换引擎 / 换工作区换一个。 */
    private var conversationId = newConversationId()

    private fun newConversationId() = java.util.UUID.randomUUID().toString().uppercase()

    /**
     * 一轮正常答完记一行（同 Mac `AgentChat.recordTurn`：停止 / 出错的不记）。只记用户看得见的那句话和回复正文，
     * 上下文块、工具输出、思考过程都不记。两边都空就不记。
     */
    private fun recordTurn(userText: String, reply: String) {
        val u = userText.trim()
        val r = reply.trim()
        if (u.isEmpty() && r.isEmpty()) return
        val q = host.storeQueue() ?: return
        val now = com.xvan.unireader.local.store.nowIso()
        val t = com.xvan.unireader.local.store.LibAgentTurn(
            newConversationId(), conversationId, AgentToolset.AGENT_NAME, "", u, r, now, now,
        )
        q.submit("Agent 记一轮") { s -> s.insertAgentTurn(t) }   // 失败队列自己记日志
    }

    fun destroy() {
        scope.cancel()
        engine?.close(); engine = null
    }

    // ---------------- 发送 ----------------

    private fun onSendOrStop() {
        if (job?.isActive == true) { job?.cancel(); return }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val s = AgentConfig.load(a)
        if (!s.isComplete) { showSettings(); return }
        val key = "${s.baseUrl}|${s.chatPath}|${s.model}|${s.apiKey.hashCode()}"
        if (engine == null || key != engineKey) {
            engine?.close()
            engine = AgentEngine(s, AgentToolset(host).tools, systemPrompt())
            engineKey = key
            fresh = true
            conversationId = newConversationId()
        }
        val eng = engine ?: return
        input.setText("")
        if (transcript.childCount == 1 && transcript.getChildAt(0).tag == "empty") transcript.removeAllViews()
        addUser(text)
        val firstOfChat = fresh
        fresh = false
        job = scope.launch {
            setBusy(true)
            val turn = TurnView()
            try {
                val ctx = contextBlock(firstOfChat)
                eng.send(text, ctx) { ev -> turn.on(ev) }
                recordTurn(text, turn.reply())
            } catch (e: CancellationException) {
                turn.note(a.getString(R.string.agent_stopped))
            } catch (e: Exception) {
                Log.w(TAG, "这一轮失败", e)
                turn.error(e.message ?: e.javaClass.simpleName)
            } finally {
                turn.finish()
                setBusy(false)
            }
        }
    }

    private fun setBusy(b: Boolean) {
        sendBtn.text = a.getString(if (b) R.string.agent_stop else R.string.agent_send)
    }

    private fun newChat() {
        job?.cancel()
        engine?.reset(systemPrompt())
        fresh = true
        conversationId = newConversationId()
        clearTranscript()
    }

    private fun systemPrompt(): String = """
        You are the assistant inside UniReader, a PDF and Markdown note reader, running on the user's Android tablet.
        One workspace is one subject: its PDFs, the user's annotations (text notes, highlights, bookmarks), Markdown notes and memories.
        Use the tools to look things up instead of guessing; pages are 1-based. Call get_state first when you need to know what the user is looking at.
        When you learn something worth keeping for later conversations in this workspace (the user's goals, preferences, what they study and how far they got), save it with save_memory: one short fact per memory, in the user's language.
        Earlier conversations in this workspace are saved too: to recall what was discussed, use list_conversations and read_conversation.
        Reply in the user's language. Keep answers concise; use Markdown, and $…$ for formulas.
    """.trimIndent()

    /** 每条消息前附带的上下文块（用户看不到）：当前在看什么；新对话第一句再带上记忆。 */
    private suspend fun contextBlock(withMemories: Boolean): String {
        val v = host.currentView()
        val sb = StringBuilder("[UniReader context]\n")
        sb.append("Workspace: ").append(host.workspaceName.ifEmpty { "(none)" }).append('\n')
        if (v != null) {
            sb.append("Current tab: ${v.kind} \"${v.title}\" (id ${v.documentId})")
            if (v.kind == "pdf") sb.append(", page ${v.page + 1} of ${v.pageCount}")
            sb.append('\n')
        }
        if (withMemories) {
            val mems: List<LibAgentMemory> = runCatching {
                host.storeQueue()?.read("Agent 读记忆") { it.agentMemories() }
            }.getOrNull().orEmpty()
            if (mems.isNotEmpty()) {
                sb.append("Workspace memories (saved earlier, id prefix in brackets):\n")
                for (m in mems.take(50)) sb.append("- [").append(m.id.take(8)).append("] ").append(m.content).append('\n')
            }
        }
        sb.append("[/UniReader context]")
        return sb.toString()
    }

    // ---------------- 对话记录 ----------------

    private fun clearTranscript() {
        transcript.removeAllViews()
        if (isOpen) showEmptyState()
    }

    private fun showEmptyState() {
        val s = AgentConfig.load(a)
        val msg = if (s.isComplete) a.getString(R.string.agent_empty, s.model) else a.getString(R.string.agent_need_settings)
        transcript.addView(Ui.body(a, msg).apply {
            tag = "empty"
            setPadding(dp(4), dp(16), dp(4), dp(16))
        })
    }

    private fun addUser(text: String) {
        transcript.addView(
            TextView(a).apply {
                this.text = text
                textSize = 15f
                setTextColor(Ui.onSurface(a))
                background = Ui.round(Ui.container(a), Ui.RADIUS, a)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setTextIsSelectable(true)
            },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = dp(12); marginStart = dp(40) },
        )
        scrollToEnd()
    }

    private fun scrollToEnd() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    /**
     * 一轮的显示：正文段 / 思考 / 工具调用按到达顺序排下去。流式文字只往当前那段 TextView 里追加
     * （不重建视图）；一段说完（接着调工具，或整轮结束）才按 Markdown 重画一遍。
     */
    private inner class TurnView {
        private var textView: TextView? = null
        private val textBuf = StringBuilder()
        private var thinkView: TextView? = null
        private val thinkBuf = StringBuilder()
        private val toolRows = HashMap<String, TextView>()
        /** 这一轮的回复正文（几段之间空一行），记对话用 */
        private val replyAll = StringBuilder()

        fun reply(): String = replyAll.toString()

        fun on(ev: AgentEvent) {
            when (ev) {
                is AgentEvent.TextDelta -> {
                    endThinking()
                    val tv = textView ?: newText().also { textView = it }
                    textBuf.append(ev.text)
                    tv.text = textBuf
                    replyAll.append(ev.text)
                }
                is AgentEvent.ReasoningDelta -> {
                    val tv = thinkView ?: newThinking().also { thinkView = it }
                    thinkBuf.append(ev.text)
                    tv.text = a.getString(R.string.agent_thinking) + "  " + thinkBuf.takeLast(120).toString().replace('\n', ' ')
                }
                is AgentEvent.ToolStart -> {
                    endThinking(); endText()
                    if (replyAll.isNotEmpty() && !replyAll.endsWith("\n\n")) replyAll.append("\n\n")
                    val row = newToolRow(ev.name, ev.args)
                    toolRows[ev.callId] = row
                }
                is AgentEvent.ToolDone -> {
                    val row = toolRows[ev.callId] ?: return
                    val head = "${if (ev.isError) "✗" else "✓"} ${ev.name}"
                    row.tag = Pair(head, ev.output)
                    row.text = head
                    if (ev.isError) row.setTextColor(Ui.col(a, R.color.danger))
                }
                is AgentEvent.Usage -> {}
            }
            scrollToEnd()
        }

        fun note(s: String) {
            endThinking(); endText()
            transcript.addView(Ui.body(a, s).apply { setPadding(0, dp(6), 0, 0) })
        }

        fun error(s: String) {
            endThinking(); endText()
            transcript.addView(
                TextView(a).apply {
                    text = a.getString(R.string.agent_error, s)
                    textSize = 14f
                    setTextColor(Ui.col(a, R.color.danger))
                    setPadding(0, dp(6), 0, 0)
                    setTextIsSelectable(true)
                },
            )
            scrollToEnd()
        }

        fun finish() { endThinking(); endText() }

        private fun endText() {
            val tv = textView ?: return
            if (textBuf.isNotBlank()) MathText.bind(tv, textBuf.toString())
            tv.setTextIsSelectable(true)
            textView = null
            textBuf.setLength(0)
        }

        private fun endThinking() {
            val tv = thinkView ?: return
            val full = thinkBuf.toString().trim()
            tv.text = a.getString(R.string.agent_thought)
            tv.setOnClickListener {
                // 点一下展开 / 收起整段思考
                tv.text = if (tv.text == a.getString(R.string.agent_thought)) full else a.getString(R.string.agent_thought)
            }
            thinkView = null
            thinkBuf.setLength(0)
        }

        private fun newText(): TextView = TextView(a).apply {
            textSize = 15f
            setTextColor(Ui.onSurface(a))
            setLineSpacing(0f, 1.15f)
        }.also { transcript.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }) }

        private fun newThinking(): TextView = TextView(a).apply {
            textSize = 13f
            setTextColor(Ui.onVariant(a))
            maxLines = 30
        }.also { transcript.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }

        private fun newToolRow(name: String, args: String): TextView = TextView(a).apply {
            val head = "… $name"
            text = head
            tag = Pair(head, "")
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Ui.onVariant(a))
            background = Ui.rippleOver(a, Ui.round(Ui.container(a), Ui.RADIUS, a), Ui.RADIUS, Ui.onSurface(a))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            // 点一下展开参数与结果，再点收起
            var open = false
            setOnClickListener {
                val pair = tag as Pair<*, *>
                val h = pair.first as String
                val out = pair.second as String
                open = !open
                text = if (!open) h else buildString {
                    append(h).append("\n").append(args.take(1000))
                    if (out.isNotEmpty()) append("\n\n").append(out.take(4000))
                }
            }
        }.also { transcript.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }) }
    }

    // ---------------- 设置 ----------------

    fun showSettings() {
        val s = AgentConfig.load(a)
        fun field(hintId: Int, value: String, password: Boolean = false) = EditText(a).apply {
            hint = a.getString(hintId)
            setText(value)
            textSize = 15f
            setSingleLine()
            setTextColor(Ui.onSurface(a))
            setHintTextColor(Ui.onVariant(a))
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = Ui.round(android.graphics.Color.TRANSPARENT, Ui.RADIUS, a, Ui.outline(a))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val base = field(R.string.agent_base_url_hint, s.baseUrl)
        val key = field(R.string.agent_api_key_hint, s.apiKey, password = true)
        val model = field(R.string.agent_model_hint, s.model)
        val path = field(R.string.agent_chat_path_hint, if (s.chatPath == AgentSettings.DEFAULT_CHAT_PATH) "" else s.chatPath)
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            fun add(labelId: Int, v: View) {
                addView(Ui.groupTitle(a, a.getString(labelId), top = if (childCount == 0) 0 else 12))
                addView(v, LinearLayout.LayoutParams(-1, -2))
            }
            add(R.string.agent_base_url, base)
            add(R.string.agent_api_key, key)
            add(R.string.agent_model, model)
            add(R.string.agent_chat_path, path)
            addView(Ui.tip(a, a.getString(R.string.agent_settings_tip)))
        }
        Sheet(a).title(a.getString(R.string.agent_settings))
            .size(520, 0.85f)
            .content(body)
            .action(a.getString(R.string.act_cancel))
            .action(a.getString(R.string.act_save), primary = true) {
                AgentConfig.save(
                    a,
                    AgentSettings(
                        baseUrl = base.text.toString().trim(),
                        apiKey = key.text.toString().trim(),
                        model = model.text.toString().trim(),
                        chatPath = path.text.toString().trim().ifEmpty { AgentSettings.DEFAULT_CHAT_PATH },
                    ),
                )
                if (transcript.childCount == 1 && transcript.getChildAt(0).tag == "empty") {
                    transcript.removeAllViews(); showEmptyState()
                }
            }
            .show()
    }

    private fun hideKeyboard() {
        val imm = a.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun dp(v: Int) = Ui.dp(a, v)
}
