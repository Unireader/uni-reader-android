package com.xvan.unireader.local.search

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.truncate

/**
 * 语义搜索的纯规则（`../docs/SEMANTIC-SYNC-PLAN.md §4.2`），照 Mac `Sources/Search/SearchIndex.swift` 的 `semantic` / `fuse`
 * 与 `Embedding.swift` 的 `VectorMath.quantize`。不碰安卓 API（JVM 单测 `SemanticTest`）。
 *
 * 安卓不算块向量：块向量是 Mac 算好经服务器同步来的（[SemanticVectors]），按**块正文的 SHA-256** 认（内容寻址），
 * 这里只把查询向量量化成 Int8、与「本次切出的块里查得到向量的那些」逐个点积。
 */
object Semantic {

    /** 模型标识（与 Mac `LocalEmbedding.modelID` 一字不差）。只用这个模型的向量。 */
    const val MODEL_ID = "local|Qwen3-Embedding-0.6B@97b0c614"

    /** Qwen3-Embedding 的查询任务说明（与 Mac `LocalEmbedding.queryPrefix` 一字不差），后面直接接查询文字。正文不带。 */
    const val QUERY_PREFIX = "Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery:"

    /** 语义那一路先取最像的 [K] 块（同 Mac `semantic(k: 300)`）。 */
    const val K = 300

    /** 倒数排名融合的常数（同 Mac `fuse`）。 */
    private const val RRF_K = 60.0

    /** 块正文 → 32 字节 SHA-256（UTF-8），服务器上向量的键。 */
    fun hash(body: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))

    fun hex(b: ByteArray): String {
        val d = "0123456789abcdef"
        val out = CharArray(b.size * 2)
        for (i in b.indices) { val v = b[i].toInt() and 0xff; out[2 * i] = d[v ushr 4]; out[2 * i + 1] = d[v and 15] }
        return String(out)
    }

    /** 单位向量 ×127 四舍五入（远离零，同 Swift `Float.rounded()`；按 Float 算，与 Mac 逐位一致）夹到 ±127 → Int8。 */
    fun quantize(v: FloatArray): ByteArray = ByteArray(v.size) {
        val x = v[it] * 127f
        val t = truncate(x)
        val r = if (abs(x - t) >= 0.5f) t + sign(x) else t
        r.coerceIn(-127f, 127f).toInt().toByte()
    }

    fun dot(a: ByteArray, b: ByteArray): Int {
        var s = 0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /**
     * 语义一路（同 Mac `SearchIndex.semantic`）：查询向量与每块的向量点积（÷127² ≈ 余弦），按分排，取前 `2 × K`
     * （Mac 先取 `k * 2` 再按块去重，单模型时就是前 600），再按页 / 段去重到 [limit]。查不到向量 / 维数不对的块不参与。
     */
    fun rank(
        q: ByteArray,
        chunks: List<WorkspaceSearch.SearchChunk>,
        vectorOf: (WorkspaceSearch.SearchChunk) -> ByteArray?,
        limit: Int,
    ): List<WorkspaceSearch.Hit> {
        val scored = ArrayList<WorkspaceSearch.Hit>()
        for (c in chunks) {
            val v = vectorOf(c) ?: continue
            if (v.size != q.size || v.isEmpty()) continue
            scored.add(WorkspaceSearch.Hit(c, dot(q, v).toDouble() / (127.0 * 127.0)))
        }
        val top = scored.sortedByDescending { it.score }.take(K * 2)
        return WorkspaceSearch.dedupe(top, limit)
    }

    /**
     * 两路合并（倒数排名融合，k = 60，同 Mac `SearchIndex.fuse`）：两边都靠前的最靠前，只在一边出现的也留着。
     * [keywordWeight]：关键词退回「任一词」时传 0.5（理由见 Mac 那份的注释）。分数换成融合分。
     */
    fun fuse(
        keyword: List<WorkspaceSearch.Hit>,
        semantic: List<WorkspaceSearch.Hit>,
        limit: Int,
        keywordWeight: Double = 1.0,
    ): List<WorkspaceSearch.Hit> {
        val score = LinkedHashMap<String, Double>()
        val pick = HashMap<String, WorkspaceSearch.Hit>()
        for ((i, h) in keyword.withIndex()) {
            val k = WorkspaceSearch.dedupeKey(h.chunk)
            score[k] = (score[k] ?: 0.0) + keywordWeight / (RRF_K + (i + 1))
            pick.putIfAbsent(k, h)
        }
        for ((i, h) in semantic.withIndex()) {
            val k = WorkspaceSearch.dedupeKey(h.chunk)
            score[k] = (score[k] ?: 0.0) + 1.0 / (RRF_K + (i + 1))
            pick.putIfAbsent(k, h)
        }
        return score.entries.sortedByDescending { it.value }.take(limit).mapNotNull { e ->
            pick[e.key]?.let { WorkspaceSearch.Hit(it.chunk, e.value) }
        }
    }
}
