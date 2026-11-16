package com.xvan.unireader.local.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OpenAILLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import org.json.JSONObject

/**
 * Agent 能用的一个工具（`../docs/ANDROID-AGENT-PLAN.md §5`）。名字与参数照 Mac 的 MCP 工具（`../Sources/MCP/`），
 * 同一个模型在两端用起来一个样。
 *
 * 参数是模型给的原始 JSON（Koog 那套 `@Serializable` 参数类要 serialization 编译插件，本工程不装）；
 * 返回给模型看的文字。抛异常 = 工具出错，原因原样回给模型（它会自己改参数重试）。
 */
interface AgentTool {
    val descriptor: ToolDescriptor
    suspend fun run(args: JSONObject): String
}

/** 一轮对话里给界面看的事件（全在调用 [AgentEngine.send] 的那个协程里发出）。 */
sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class ReasoningDelta(val text: String) : AgentEvent
    data class ToolStart(val callId: String, val name: String, val args: String) : AgentEvent
    data class ToolDone(val callId: String, val name: String, val output: String, val isError: Boolean) : AgentEvent
    /** 一次请求结束（模型说完一段，可能马上接着调工具再来一段）。 */
    data class Usage(val inputTokens: Int?, val outputTokens: Int?) : AgentEvent
}

/**
 * 一段对话：Koog 的 OpenAI 客户端（Chat Completions 流式、拼接分段的工具调用、`reasoning_content`）
 * + Koog 的 [Prompt] 当对话历史；「调工具 → 结果放回历史 → 再请求」这一层循环在这里，几十行，
 * 为的是界面能逐字显示、能随时停（取消协程即停，历史停在最后一个完整的位置）。
 *
 * 不用 Koog 的 `AIAgent`：它一次 `run` 一个输入就结束，多轮对话要外挂聊天记忆，界面拿流式文字也绕。
 */
class AgentEngine(
    settings: AgentSettings,
    private val tools: List<AgentTool>,
    systemPrompt: String,
) {
    private val client = OpenAILLMClient(
        apiKey = settings.apiKey,
        settings = OpenAIClientSettings(
            baseUrl = settings.baseUrl.trim().trimEnd('/'),
            chatCompletionsPath = settings.chatPath.trim().trimStart('/'),
        ),
        httpClientFactory = OkHttpKoogHttpClient.Factory(),
    )

    /** 第三方模型 Koog 不认识：自己声明一个，只要 Chat Completions + 工具。 */
    private val model = LLModel(
        provider = OpenAILLMProvider,
        id = settings.model.trim(),
        // Completion = 「能对话」（Koog 发请求前必查，漏了直接 IllegalArgumentException，2026-11-15 插桩测试抓到的）；
        // OpenAIEndpoint.Completions = 走 Chat Completions 而不是 Responses API
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.OpenAIEndpoint.Completions,
            LLMCapability.Tools,
            LLMCapability.ToolChoice,
            LLMCapability.Temperature,
        ),
    )

    private val byName = tools.associateBy { it.descriptor.name }

    @Volatile
    var history: Prompt = prompt("unireader-agent") { system(systemPrompt) }
        private set

    /** 一次工具循环最多几轮请求，防止模型反复调工具停不下来。 */
    var maxSteps = 30

    /**
     * 发一条用户消息，跑到模型不再调工具为止。挂起函数；取消调用它的协程 = 停止。
     * [context] 是这一条附带的上下文块（当前在看哪本书第几页），拼在用户原话前面。
     */
    suspend fun send(text: String, context: String?, emit: (AgentEvent) -> Unit) {
        val userText = if (context.isNullOrBlank()) text else "$context\n\n$text"
        history = prompt(history) { user(userText) }
        val descs = tools.map { it.descriptor }
        for (step in 0 until maxSteps) {
            val frames = ArrayList<StreamFrame>()
            client.executeStreaming(history, model, descs).collect { f ->
                frames += f
                when (f) {
                    is StreamFrame.TextDelta -> emit(AgentEvent.TextDelta(f.text))
                    is StreamFrame.ReasoningDelta -> f.text?.let { emit(AgentEvent.ReasoningDelta(it)) }
                    is StreamFrame.End -> emit(AgentEvent.Usage(f.metaInfo.inputTokensCount, f.metaInfo.outputTokensCount))
                    else -> {}
                }
            }
            val reply = toAssistant(frames)
            history = history.withMessages { it + reply }
            val calls = reply.parts.filterIsInstance<MessagePart.Tool.Call>()
            if (calls.isEmpty()) return
            for (call in calls) {
                val callId = call.id ?: "call_${step}_${call.tool}"
                emit(AgentEvent.ToolStart(callId, call.tool, call.args))
                val (out, isError) = runTool(call)
                emit(AgentEvent.ToolDone(callId, call.tool, out, isError))
                history = prompt(history) { toolResult(call.tool, out, call.id, isError) }
            }
        }
        emit(AgentEvent.TextDelta("\n\n（工具调用超过 $maxSteps 轮，已停下）"))
    }

    /**
     * 同 Koog 的 `toMessageResponse`，只差一处：无参数的工具调用有的服务给空串，
     * 那边 `Json.parseToJsonElement("")` 直接抛异常，这里按 `{}` 算。
     */
    private fun toAssistant(frames: List<StreamFrame>): Message.Assistant {
        var end: StreamFrame.End? = null
        val text = StringBuilder()
        val others = frames.mapNotNull { f ->
            when (f) {
                is StreamFrame.ReasoningComplete -> MessagePart.Reasoning(
                    id = f.id, content = f.content, summary = f.summary, encrypted = f.encrypted,
                )
                is StreamFrame.TextComplete -> { text.append(f.text); null }
                is StreamFrame.ToolCallComplete -> MessagePart.Tool.Call(
                    id = f.id, tool = f.name, args = f.content.ifBlank { "{}" },
                )
                is StreamFrame.End -> { end = f; null }
                else -> null
            }
        }
        // 正文并成一段、空的不要：几段文字 Koog 会发成 content 数组，其中有空串时
        // Kimi 回 400「text content is empty」（2026-11-16 真机第二轮对话踩到）。
        val parts = if (text.isBlank()) others else listOf(MessagePart.Text(text.toString())) + others
        return Message.Assistant(
            parts = parts,
            metaInfo = end?.metaInfo ?: ResponseMetaInfo.Empty,
            finishReason = end?.finishReason,
        )
    }

    private suspend fun runTool(call: MessagePart.Tool.Call): Pair<String, Boolean> {
        val tool = byName[call.tool] ?: return "没有这个工具：${call.tool}" to true
        return try {
            val args = if (call.args.isBlank()) JSONObject() else JSONObject(call.args)
            tool.run(args) to false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            (e.message ?: e.javaClass.simpleName) to true
        }
    }

    /** 新对话：只留系统提示。 */
    fun reset(systemPrompt: String) {
        history = prompt("unireader-agent") { system(systemPrompt) }
    }

    fun close() = client.close()
}
