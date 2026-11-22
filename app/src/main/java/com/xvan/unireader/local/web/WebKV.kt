package com.xvan.unireader.local.web

import com.xvan.unireader.local.store.LibraryStore
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 网页里 `window.unireader` 的一次读写（`../docs/WEB-DOC-PLAN.md §5`）：逐条照 Mac `WorkspaceManager.webKV`
 * （op 名、上限、返回给页面的 JSON 文本都一样）。在库队列线程上调。出错抛 [Error]（文字给页面看，用英文）。
 */
object WebKV {
    const val MAX_KEYS = 2000
    const val MAX_VALUE_BYTES = 1 shl 20
    const val MAX_TOTAL_BYTES = 8 shl 20
    const val MAX_KEY_BYTES = 512

    class Error(message: String) : Exception(message)

    fun handle(s: LibraryStore, webId: String, op: String, key: String?, value: String?): String {
        if (s.webDoc(webId) == null) throw Error("This web page is not in the workspace.")
        fun needKey(): String {
            val k = key ?: ""
            if (k.isEmpty()) throw Error("A key is required.")
            if (k.toByteArray().size > MAX_KEY_BYTES) throw Error("The key is too long.")
            return k
        }
        return when (op) {
            "get" -> s.webKVValue(webId, needKey()) ?: "null"
            "set" -> {
                val k = needKey()
                val v = value ?: "null"
                val vb = v.toByteArray().size
                if (vb > MAX_VALUE_BYTES) throw Error("The value is larger than 1 MB.")
                if (!isJson(v)) throw Error("The value is not JSON.")
                val (count, bytes) = s.webKVUsage(webId)
                val old = s.webKVValue(webId, k)
                if (old == null && count >= MAX_KEYS) throw Error("Too many keys (at most $MAX_KEYS).")
                val grow = vb + if (old == null) k.toByteArray().size else -old.toByteArray().size
                if (bytes + grow > MAX_TOTAL_BYTES) throw Error("This page has stored more than 8 MB.")
                s.setWebKV(webId, k, v)
                "true"
            }
            "remove" -> { s.removeWebKV(webId, needKey()); "true" }
            "keys" -> JSONArray(s.webKV(webId).map { it.first }).toString()
            "getAll" -> s.webKV(webId).joinToString(",", "{", "}") { (k, v) -> JSONObject.quote(k) + ":" + v }
            "clear" -> { s.clearWebKV(webId); "true" }
            "query" -> {   // 只读查询（习题库 / 做题状态），见 WebQuery.kt（Mac `WorkspaceManager+WebQuery.swift`）
                if (key.isNullOrEmpty()) throw Error("A query name is required.")
                WebQuery.run(s, key, value)
            }
            else -> throw Error("Unknown operation: $op")
        }
    }

    /** 是不是一段完整的 JSON（允许顶层是数字 / 字符串 / true 这类）。 */
    fun isJson(s: String): Boolean = try {
        val t = JSONTokener(s)
        t.nextValue()
        !t.more()
    } catch (_: Exception) {
        false
    }
}
