package com.xvan.unireader.local.search

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 算查询向量的模型文件（`../docs/SEMANTIC-SYNC-PLAN.md §4.3`）：官方 `Qwen/Qwen3-Embedding-0.6B-GGUF` 的 Q8_0，钉提交、按 SHA-256 校验。
 * 放 `<外部应用私有目录>/models/`（`/sdcard/Android/data/<包名>/files/models/`：`adb push` 进得去，卸载 App 时一起删）。
 *
 * 下载照 Mac `LocalModelDownloader`：`.part` + HTTP Range 断点续传，下完验大小与 SHA-256 再改名；
 * 也可以自己放文件（`adb push`）后点「检查文件」，大小 + SHA-256 都对才算装好。装没装好平时只看大小（同 Mac `isInstalled`）。
 */
object EmbedModel {
    private const val TAG = "UniReader/EmbedModel"

    const val FILE_NAME = "Qwen3-Embedding-0.6B-Q8_0.gguf"
    const val REPO = "Qwen/Qwen3-Embedding-0.6B-GGUF"
    /** hf-mirror.com API 2026-11-16 查到的 main（与 `build/models/` 里实测那份同一个文件） */
    const val REVISION = "370f27d7550e0def9b39c1f16d3fbaa13aa67728"
    const val SIZE = 639_150_592L
    const val SHA256 = "06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439"

    /** 下载地址：Hugging Face 本站，或国内能连上的镜像（同 Mac `LocalEmbeddingModel.endpoints`） */
    val ENDPOINTS = listOf("https://huggingface.co", "https://hf-mirror.com")
    private const val PREFS = "semantic"
    private const val K_ENDPOINT = "modelEndpoint"

    fun endpoint(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_ENDPOINT, null)?.takeIf { it in ENDPOINTS } ?: ENDPOINTS[0]

    fun setEndpoint(ctx: Context, e: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_ENDPOINT, e).apply()
    }

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models")
    fun file(ctx: Context): File = File(dir(ctx), FILE_NAME)
    private fun part(ctx: Context): File = File(dir(ctx), "$FILE_NAME.part")

    fun isInstalled(ctx: Context): Boolean = file(ctx).length() == SIZE

    // ---------------- 下载 / 检查 ----------------

    sealed class State {
        object Idle : State()
        data class Downloading(val done: Long, val total: Long) : State()
        object Verifying : State()
        data class Failed(val message: String) : State()
    }

    @Volatile var state: State = State.Idle
        private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var worker: Thread? = null
    @Volatile private var cancelled = false

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun set(s: State) {
        state = s
        main.post { for (l in listeners) l() }
    }

    val busy: Boolean get() = worker != null

    /** 走系统代理（与 Mac 的 URLSession 同）：Hugging Face 往往要代理才连得上 */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    fun start(ctx: Context) = run(ctx.applicationContext) { download(it) }

    fun checkFile(ctx: Context) = run(ctx.applicationContext) { verifyExisting(it) }

    fun cancel() {
        cancelled = true
        worker?.interrupt()
    }

    fun delete(ctx: Context) {
        cancel()
        QueryEmbedder.unload()
        file(ctx).delete()
        part(ctx).delete()
        set(State.Idle)
        SemanticVectors.changed()
    }

    private fun run(ctx: Context, work: (Context) -> Unit) {
        synchronized(this) {
            if (worker != null) return
            cancelled = false
            worker = Thread({
                try {
                    work(ctx)
                } finally {
                    synchronized(this) { worker = null }
                    SemanticVectors.changed()
                }
            }, "unireader-model").also { it.start() }
        }
    }

    private fun verifyExisting(ctx: Context) {
        val f = file(ctx)
        if (f.length() != SIZE) {
            set(State.Failed("size")); return
        }
        set(State.Verifying)
        val got = runCatching { sha256(f) }.getOrNull()
        if (got != SHA256) {
            Log.w(TAG, "检查文件：SHA-256 对不上（$got）")
            set(State.Failed("checksum")); return
        }
        set(State.Idle)
    }

    private fun download(ctx: Context) {
        val dest = file(ctx)
        val part = part(ctx)
        try {
            dir(ctx).mkdirs()
            if (dest.length() != SIZE) {
                fetch(ctx, part)
                if (cancelled) { set(State.Idle); return }
                if (part.length() != SIZE) throw IOException("size does not match")
                set(State.Verifying)
                if (sha256(part) != SHA256) {
                    part.delete()
                    throw IOException("checksum does not match, please download again")
                }
                dest.delete()
                if (!part.renameTo(dest)) throw IOException("rename failed")
            }
            set(State.Idle)
            Log.i(TAG, "模型已就位：${dest.path}")
        } catch (e: Throwable) {
            if (cancelled) { set(State.Idle); return }
            Log.w(TAG, "下载模型失败", e)
            set(State.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    /** 一个文件的下载：`.part` 已有多少就从那里接着要（服务器回 206 才接，回 200 就从头写）。 */
    private fun fetch(ctx: Context, part: File) {
        var offset = part.length()
        if (offset >= SIZE) { part.delete(); offset = 0 }
        val url = "${endpoint(ctx)}/$REPO/resolve/$REVISION/$FILE_NAME"
        val b = Request.Builder().url(url)
        if (offset > 0) b.header("Range", "bytes=$offset-")
        set(State.Downloading(offset, SIZE))
        val call = http.newCall(b.build())
        val w = Thread.currentThread()
        // 取消：interrupt 不会打断阻塞的 socket 读，另起一个看门的线程在取消时断开连接
        val watch = Thread({
            try { while (!cancelled) Thread.sleep(200); call.cancel() } catch (_: InterruptedException) { }
        }, "unireader-model-cancel").also { it.isDaemon = true; it.start() }
        try {
            call.execute().use { r ->
                if (r.code != 200 && r.code != 206) throw IOException("download answered HTTP ${r.code}")
                val resume = r.code == 206 && offset > 0
                var got = if (resume) offset else 0L
                FileOutputStream(part, resume).use { out ->
                    r.body.byteStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        var last = 0L
                        while (true) {
                            if (cancelled || w.isInterrupted) return
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            val now = System.currentTimeMillis()
                            // 每 0.3 秒最多报一次，界面别一秒刷几百次
                            if (now - last >= 300) { last = now; set(State.Downloading(got, SIZE)) }
                        }
                    }
                }
            }
        } finally {
            watch.interrupt()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(8 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return Semantic.hex(md.digest())
    }
}
