package com.xvan.unireader.local.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * 安卓 Agent 引擎对着一个假的 OpenAI 兼容服务跑（`../docs/ANDROID-AGENT-PLAN.md §8`）：
 * 测试进程里起一个 127.0.0.1 上的 SSE 服务，按请求里有没有工具结果给两种回复——
 * 没有 → 流式下发一次工具调用（参数分三段到）；有 → 流式下发正文。
 * 验的是 Koog 的真网络 / 流式解析 + 本端的工具循环：路径拼接、鉴权头、分段参数拼接、
 * 工具结果带 `tool_call_id` 回传、`reasoning_content`、空参数的工具调用。不需要 API Key，不碰界面。
 */
@RunWith(AndroidJUnit4::class)
class AgentEngineTest {

    private lateinit var server: ServerSocket
    private val requests = Collections.synchronizedList(ArrayList<String>())
    /** 第一轮工具调用的参数分段（空表 = 参数是空串） */
    @Volatile private var argChunks = listOf("{\"x\":", "\"hi", "\"}")

    @Before
    fun up() {
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    @After
    fun down() = server.close()

    private fun serve(s: Socket) = s.use {
        val inp = BufferedInputStream(s.getInputStream())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = inp.read(); if (b < 0) return
            head.append(b.toChar())
        }
        val len = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
        val body = ByteArray(len)
        var off = 0
        while (off < len) { val n = inp.read(body, off, len - off); if (n < 0) break; off += n }
        val bodyStr = String(body, Charsets.UTF_8)
        requests += head.toString() + bodyStr
        val msgs = JSONObject(bodyStr).getJSONArray("messages")
        val hasToolResult = (0 until msgs.length()).any { msgs.getJSONObject(it).optString("role") == "tool" }
        val out = s.getOutputStream()
        if (hasEmptyText(msgs)) {   // 同 Kimi：content 是空串或数组里有空文字块 → 400
            out.write("HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n".toByteArray())
            out.write("{\"error\":{\"message\":\"text content is empty\",\"type\":\"invalid_request_error\"}}".toByteArray())
            out.flush(); return
        }
        val events = if (!hasToolResult) toolCallChunks() else textChunks()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
        for (e in events) { out.write("data: $e\n\n".toByteArray(Charsets.UTF_8)); out.flush() }
        out.write("data: [DONE]\n\n".toByteArray()); out.flush()
    }

    private fun hasEmptyText(msgs: org.json.JSONArray): Boolean = (0 until msgs.length()).any { i ->
        when (val c = msgs.getJSONObject(i).opt("content")) {
            is String -> c.isEmpty()
            is org.json.JSONArray -> (0 until c.length()).any { j ->
                c.getJSONObject(j).let { it.optString("type") == "text" && it.optString("text").isEmpty() }
            }
            else -> false
        }
    }

    private fun chunk(delta: JSONObject, finish: String? = null): String = JSONObject()
        .put("id", "chatcmpl-1").put("object", "chat.completion.chunk").put("created", 1).put("model", "fake")
        .put(
            "choices",
            org.json.JSONArray().put(
                JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL),
            ),
        ).toString()

    private fun toolCallChunks(): List<String> {
        val first = JSONObject().put("role", "assistant").put(
            "tool_calls",
            org.json.JSONArray().put(
                JSONObject().put("index", 0).put("id", "call_1").put("type", "function")
                    .put("function", JSONObject().put("name", "echo").put("arguments", "")),
            ),
        )
        val parts = argChunks.map { a ->
            chunk(
                JSONObject().put(
                    "tool_calls",
                    org.json.JSONArray().put(JSONObject().put("index", 0).put("function", JSONObject().put("arguments", a))),
                ),
            )
        }
        return listOf(
            chunk(JSONObject().put("role", "assistant").put("reasoning_content", "let me ")),
            chunk(JSONObject().put("reasoning_content", "call echo")),
            chunk(first),
        ) + parts + chunk(JSONObject(), "tool_calls")
    }

    private fun textChunks(): List<String> = listOf(
        // 同 Kimi：思考那几块也带着 "content": ""，与正文交替到达（Koog 会切出几段文字，其中有空串）
        chunk(JSONObject().put("role", "assistant").put("content", "").put("reasoning_content", "answer ")),
        chunk(JSONObject().put("content", "do")),
        chunk(JSONObject().put("content", "").put("reasoning_content", "now")),
        chunk(JSONObject().put("content", "ne")),
        chunk(JSONObject(), "stop"),
    )

    /** 假工具：回 `pong:<x>`（没参数回 `pong:`）。 */
    private val echo = object : AgentTool {
        override val descriptor = ToolDescriptor(
            "echo", "echo back",
            optionalParameters = listOf(ToolParameterDescriptor("x", "text", ToolParameterType.String)),
        )
        override suspend fun run(args: JSONObject): String = "pong:" + args.optString("x")
    }

    private fun engine() = AgentEngine(
        AgentSettings(baseUrl = "http://127.0.0.1:${server.localPort}/v1/", apiKey = "sk-test", model = "fake-model"),
        listOf(echo), "system prompt",
    )

    @Test
    fun toolLoopStreamsAndFeedsResultBack() = runBlocking {
        val events = ArrayList<AgentEvent>()
        engine().send("hello", "[ctx]") { events += it }

        assertEquals("两次请求：调工具一次 + 拿结果再答一次", 2, requests.size)
        val first = requests[0]
        assertTrue(first, first.startsWith("POST /v1/chat/completions "))
        assertTrue("带鉴权头", Regex("(?i)authorization: Bearer sk-test").containsMatchIn(first))
        assertTrue("模型名原样发", first.contains("\"fake-model\""))
        assertTrue("工具描述发出去了", first.contains("\"echo\""))
        assertTrue("上下文块拼在用户原话前", first.contains("[ctx]\\n\\nhello"))

        val start = events.filterIsInstance<AgentEvent.ToolStart>().single()
        assertEquals("echo", start.name)
        assertEquals("分三段到的参数拼回完整 JSON", "hi", JSONObject(start.args).getString("x"))
        val done = events.filterIsInstance<AgentEvent.ToolDone>().single()
        assertEquals("pong:hi", done.output)
        assertEquals(false, done.isError)

        val second = requests[1]
        assertTrue("工具结果回传", second.contains("pong:hi"))
        assertTrue("带上 tool_call_id", second.contains("call_1"))

        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("done", text)
        val thinking = events.filterIsInstance<AgentEvent.ReasoningDelta>().joinToString("") { it.text }
        assertEquals("let me call echoanswer now", thinking)
    }

    @Test
    fun emptyToolArgumentsCountAsEmptyObject() = runBlocking {
        argChunks = emptyList()   // 有的服务调无参数工具时 arguments 一直是空串
        val events = ArrayList<AgentEvent>()
        engine().send("hello", null) { events += it }
        val done = events.filterIsInstance<AgentEvent.ToolDone>().single()
        assertEquals("pong:", done.output)
        assertEquals(false, done.isError)
        assertEquals("done", events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text })
    }

    @Test
    fun secondMessageKeepsHistory() = runBlocking {
        val e = engine()
        e.send("first", null) {}
        e.send("second", null) {}
        // 第二句的第一次请求（第 3 次请求）里应当有第一句、工具结果和第一句的回答
        val third = requests[2]
        assertTrue(third.contains("first"))
        assertTrue(third.contains("pong:hi"))
        assertTrue(third.contains("done"))
        assertTrue(third.contains("second"))
        assertEquals("第二句的请求没被假服务以空文字拒掉", 3, requests.size)
    }
}
