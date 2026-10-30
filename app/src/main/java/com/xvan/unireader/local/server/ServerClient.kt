package com.xvan.unireader.local.server

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 连 UniReader 服务器的 HTTP 客户端（`../docs/SERVER-MODE-PLAN.md §3.3`；接口全表在
 * `../server/Sources/UniServerHTTP/ServerApp.swift` 文件头）。只管收发，同步算法在 [SyncClient]。
 * 照 Mac `../Sources/ServerSync/ServerClient.swift` 写，接口一一对应。
 *
 * **全部是阻塞调用**，只在后台线程用。[cancel] 给了的话，每个请求都登记在它上面：取消时正在飞的请求立刻断开，
 * 之后的请求一律抛 [Cancelled]（打开工作区前要先停掉还在跑的那一轮，见 [ServerSync.prepareToOpen]）。
 */
class ServerClient(val baseUrl: String, val token: String?, private val cancel: Cancel? = null) {

    /**
     * 服务器回了错误状态：[status] = HTTP 状态码（[BAD_RESPONSE] / [BAD_ADDRESS] 是本端判的），
     * message = 服务器给的说明（可能为空，界面层再按状态码说人话，见 `ServerUi.describe`）。
     */
    class Failure(val status: Int, message: String) : IOException(message)

    class Cancelled : IOException("cancelled")

    /** 一轮同步的取消开关：取消时断开正在飞的请求 */
    class Cancel {
        @Volatile var cancelled = false
            private set
        private var current: Call? = null

        @Synchronized fun track(c: Call?) {
            current = c
            if (cancelled) c?.cancel()
        }

        @Synchronized fun cancel() {
            cancelled = true
            current?.cancel()
        }

        fun check() { if (cancelled) throw Cancelled() }
    }

    data class Paired(
        val deviceId: String, val token: String, val schemaVersion: Int,
        val serverId: String?, val serverName: String?,
    )

    data class Info(
        val serverVersion: String, val schemaVersion: Int, val deviceId: String, val deviceName: String,
        val serverId: String?, val serverName: String?,
    )

    data class WorkspaceInfo(val id: String, val name: String, val head: Long)

    fun with(cancel: Cancel?) = ServerClient(baseUrl, token, cancel)

    /** 这一轮被取消了就抛 [Cancelled]（两次网络之间、动手写库之前问一声） */
    fun checkCancelled() = cancel?.check()

    companion object {
        private val JSON = "application/json".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private const val CHUNK = 8 * 1024 * 1024

        /** [Failure.status] 的两个本端值：回应不合约定 / 地址拼不出来 */
        const val BAD_RESPONSE = -1
        const val BAD_ADDRESS = -2

        /**
         * 连服务器**一律不走代理**（2026-10-29 用户定，同 Mac）：服务器是自己搭的，用不着代理；
         * 经代理反而连不上（局域网地址、只有 IPv6 的自家域名）。
         */
        val http: OkHttpClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build()

        /** 用户填的地址 → 基础地址：没写协议补 `http://`，去掉末尾的 `/`。不像地址 = null */
        fun normalize(raw: String): String? {
            var s = raw.trim()
            if (s.isEmpty()) return null
            if (!s.contains("://")) s = "http://$s"
            while (s.endsWith("/")) s = s.dropLast(1)
            val u = s.toHttpUrlOrNull() ?: return null
            if (u.host.isEmpty()) return null
            return s
        }

        fun pair(baseUrl: String, code: String, deviceName: String): Paired {
            val c = ServerClient(baseUrl, null)
            val body = JSONObject().put("code", code).put("deviceName", deviceName).toString().toRequestBody(JSON)
            val o = JSONObject(c.send(c.request("v1/pair").post(body)).decodeToString())
            return Paired(
                o.getString("deviceId"), o.getString("token"), o.optInt("schemaVersion"),
                o.optString("serverId").ifEmpty { null }, o.optString("serverName").ifEmpty { null },
            )
        }
    }

    // ---------- 接口 ----------

    fun info(): Info {
        val o = JSONObject(send(request("v1/info")).decodeToString())
        return Info(
            o.optString("serverVersion"), o.optInt("schemaVersion"), o.optString("deviceId"), o.optString("deviceName"),
            o.optString("serverId").ifEmpty { null }, o.optString("serverName").ifEmpty { null },
        )
    }

    fun workspaces(): List<WorkspaceInfo> {
        val arr = JSONObject(send(request("v1/workspaces")).decodeToString()).getJSONArray("workspaces")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            WorkspaceInfo(o.getString("id"), o.optString("name"), o.optLong("head"))
        }
    }

    fun push(ws: String, body: ByteArray): ByteArray =
        send(request("v1/workspaces/$ws/push").post(body.toRequestBody(OCTET)))

    fun pull(ws: String, since: Long, limit: Int = 5000): ByteArray =
        send(request("v1/workspaces/$ws/pull", "since" to since.toString(), "limit" to limit.toString()))

    /** 下载快照到 [dst]，返回快照对应的修订号 */
    fun downloadSnapshot(ws: String, dst: File): Long {
        val rev = download(request("v1/workspaces/$ws/snapshot"), dst)?.header("X-UniReader-Rev")?.toLongOrNull()
        return rev ?: throw Failure(BAD_RESPONSE, "")
    }

    /** 服务器上有没有这份 PDF：有 = 字节数，没有 = null */
    fun pdfSize(ws: String, hash: String): Long? = headSize(request("v1/workspaces/$ws/pdf/$hash"))

    fun downloadPdf(ws: String, hash: String, dst: File, progress: ((Long, Long) -> Unit)? = null) {
        download(request("v1/workspaces/$ws/pdf/$hash"), dst, progress)
    }

    fun uploadPdf(ws: String, hash: String, file: File, progress: ((Long, Long) -> Unit)? = null) =
        resumableUpload("v1/workspaces/$ws/pdf/$hash", emptyList(), file, progress)

    fun downloadImage(ws: String, sha: String, ext: String, dst: File) {
        download(request("v1/workspaces/$ws/image/$sha.$ext"), dst)
    }

    fun uploadImage(ws: String, sha: String, ext: String, file: File) =
        resumableUpload("v1/workspaces/$ws/image/$sha.$ext", emptyList(), file, null)

    // ---------- 上传本地工作区 ----------

    fun newUpload(): String =
        JSONObject(send(request("v1/uploads").post(ByteArray(0).toRequestBody(null))).decodeToString()).getString("upload")

    fun uploadFile(upload: String, path: String, file: File, progress: ((Long, Long) -> Unit)? = null) =
        resumableUpload("v1/uploads/$upload/file", listOf("path" to path), file, progress)

    /** 登记：服务器要逐个校验 PDF 的哈希，等得久一点 */
    fun finishUpload(upload: String): WorkspaceInfo {
        val req = request("v1/uploads/$upload/finish").post(ByteArray(0).toRequestBody(null))
        val o = JSONObject(send(req, readTimeoutSec = 600).decodeToString())
        return WorkspaceInfo(o.getString("id"), o.optString("name"), o.optLong("head"))
    }

    fun cancelUpload(upload: String) {
        runCatching { send(request("v1/uploads/$upload").delete()) }
    }

    // ---------- 收发 ----------

    private fun url(path: String, query: List<Pair<String, String>>): HttpUrl {
        val base = "$baseUrl/".toHttpUrlOrNull() ?: throw Failure(BAD_ADDRESS, "")
        val b = base.newBuilder()
        for (seg in path.split("/")) b.addPathSegment(seg)
        for ((k, v) in query) b.addQueryParameter(k, v)
        return b.build()
    }

    private fun request(path: String, vararg query: Pair<String, String>): Request.Builder = request(path, query.toList())

    private fun request(path: String, query: List<Pair<String, String>>): Request.Builder {
        val b = Request.Builder().url(url(path, query))
        if (token != null) b.header("Authorization", "Bearer $token")
        return b
    }

    private fun call(req: Request, readTimeoutSec: Long? = null): Response {
        cancel?.check()
        val client = if (readTimeoutSec == null) http else http.newBuilder().readTimeout(readTimeoutSec, TimeUnit.SECONDS).build()
        val c = client.newCall(req)
        cancel?.track(c)   // 留到下一个请求才换掉：下载的响应体在 execute 之后才读，读的时候也要能断开
        try {
            return c.execute()
        } catch (e: IOException) {
            if (cancel?.cancelled == true) throw Cancelled()
            throw e
        }
    }

    private fun send(b: Request.Builder, readTimeoutSec: Long? = null): ByteArray =
        call(b.build(), readTimeoutSec).use { r ->
            val body = r.body.bytes()
            if (!r.isSuccessful) throw failure(r.code, body)
            body
        }

    /** 流式下载到 [dst]：先写旁边的 `.part`，下完再改名（半截文件不会冒充完整的） */
    private fun download(b: Request.Builder, dst: File, progress: ((Long, Long) -> Unit)? = null): Response? {
        val r = call(b.build(), readTimeoutSec = 300)
        r.use {
            if (!r.isSuccessful) throw failure(r.code, r.body.bytes())
            dst.parentFile?.mkdirs()
            val part = File(dst.parentFile, ".${dst.name}.part")
            val total = r.body.contentLength()
            try {
                r.body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            progress?.invoke(done, total)
                        }
                    }
                }
            } catch (e: IOException) {
                part.delete()
                if (cancel?.cancelled == true) throw Cancelled()
                throw e
            }
            dst.delete()
            if (!part.renameTo(dst)) {
                part.delete()
                throw IOException("rename failed: ${dst.absolutePath}")
            }
            return r
        }
    }

    /** HEAD：200 → 字节数；404 → null（其余状态照常抛错） */
    private fun headSize(b: Request.Builder): Long? = call(b.head().build()).use { r ->
        if (r.code == 404) return null
        if (!r.isSuccessful) throw Failure(r.code, "")
        r.header("Content-Length")?.toLongOrNull() ?: 0L
    }

    /**
     * 续传上传（服务器接口约定见 `ServerApp.swift` 文件头）：先 `HEAD` 问已经收到多少，再按 8MB 一块 `PUT`，
     * 每块带 `Upload-Offset` / `Upload-Length`。网络断了重问一次偏移接着传（最多重试 3 次）。
     */
    private fun resumableUpload(path: String, query: List<Pair<String, String>>, file: File, progress: ((Long, Long) -> Unit)?) {
        val total = file.length()
        var retries = 0
        var offset = uploadOffset(path, query) ?: run { progress?.invoke(total, total); return }   // 服务器已经有了
        RandomAccessFile(file, "r").use { raf ->
            while (true) {
                cancel?.check()
                val at = offset
                progress?.invoke(at, total)
                val n = minOf(CHUNK.toLong(), total - at).toInt().coerceAtLeast(0)
                val buf = ByteArray(n)
                raf.seek(at)
                raf.readFully(buf)
                val req = request(path, query)
                    .header("Upload-Offset", at.toString())
                    .header("Upload-Length", total.toString())
                    .put(buf.toRequestBody(OCTET))
                    .build()
                val resp = try {
                    call(req, readTimeoutSec = 300)
                } catch (e: IOException) {
                    if (e is Cancelled || retries >= 3) throw e
                    retries++
                    offset = uploadOffset(path, query) ?: run { progress?.invoke(total, total); return }
                    continue
                }
                resp.use { r ->
                    when (r.code) {
                        200, 201 -> {
                            progress?.invoke(total, total)
                            return
                        }
                        202, 409 -> {
                            val o = r.header("Upload-Offset")?.toLongOrNull()
                                ?: throw Failure(BAD_RESPONSE, "")
                            if (r.code == 409 && ++retries > 3) throw failure(409, r.body.bytes())
                            offset = o
                        }
                        else -> throw failure(r.code, r.body.bytes())
                    }
                }
            }
        }
    }

    /** 已经传了多少：null = 服务器已经有完整的了 */
    private fun uploadOffset(path: String, query: List<Pair<String, String>>): Long? =
        call(request(path, query).head().build()).use { r ->
            if (r.isSuccessful) return null
            if (r.code != 404) throw Failure(r.code, "")
            r.header("Upload-Offset")?.toLongOrNull() ?: 0L
        }

    /** 服务器的错误体是 `{"error":{"message":"…"}}`（Hummingbird 的 `HTTPError`） */
    private fun failure(status: Int, body: ByteArray): Failure {
        val msg = runCatching { JSONObject(body.decodeToString()).getJSONObject("error").getString("message") }
            .getOrNull().orEmpty()
        return Failure(status, msg)
    }
}
