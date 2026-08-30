package com.xvan.unireader.local

import android.content.Context
import android.util.Log
import com.xvan.unireader.local.store.LibDocument
import com.xvan.unireader.local.store.LibraryStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 把本机的一个 PDF 加进工作区（模式1 的「添加 PDF」）。
 *
 * 口径与 Mac 端的「导入 + 拷进工作区」一致（`WorkspaceManager.ingest` / `copyToWorkspace`）：
 * **内容 SHA-256 是文档的身份**，同一份内容再导一次不会多出一本书。
 *
 * 与 Mac 的唯一差别是**这里一定拷贝**：安卓上没有"跨设备稳定的绝对路径"这回事
 * （换挂载点、拿到 Mac 上开，绝对路径一律作废），所以文件必须进 `PDFs/`，库里存工作区
 * 相对路径——这样整个 `.unrd` 拷到哪儿、在哪端打开都成立。
 *
 * 全部方法都是**后台线程**调用：SHA-256 要过一遍整个文件，拷贝更是几十上百 MB。
 */
object PdfImport {

    const val TAG = "UniReader/Import"

    /** 读文件的块大小。与 Mac `FileHasher` 一致的 4MB——大文件不要一次性进内存 */
    const val CHUNK = 4 * 1024 * 1024

    sealed class Result {
        /** 新加进来的一本 */
        data class Added(val doc: LibDocument, val bytes: Long) : Result()

        /** 内容相同的文件已经在库里（按 SHA-256 判定），没有重复拷贝 */
        data class Duplicate(val doc: LibDocument) : Result()
    }

    fun looksLikePdf(f: File): Boolean = f.isFile && f.name.endsWith(".pdf", ignoreCase = true)

    /**
     * 文件内容的 SHA-256（小写十六进制），与 Mac `FileHasher.sha256` 逐字节同口径——
     * 两端算出来必须是同一串，否则同一份 PDF 在两端会被当成两本书。
     */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 把 [src] 加进 [workspaceDir] 这个工作区。
     *
     * 顺序是刻意的：**先探页数再算 hash 再拷贝**。探页数顺带回答了「Pdfium 认不认这个文件」，
     * 一个坏文件在这一步就被挡住，不会先拷进 `PDFs/` 再发现打不开、留一份垃圾在工作区里。
     *
     * @param store 必须是**可写**连接。调用方负责它的生命周期（本方法不关它）。
     */
    fun ingest(ctx: Context, workspaceDir: File, store: LibraryStore, src: File): Result {
        require(src.isFile) { "文件不存在：${src.absolutePath}" }
        val pages = PdfSource.probePageCount(ctx, src)
        require(pages > 0) { "这个 PDF 一页都读不出来，可能已损坏" }
        val hash = sha256(src)
        val title = src.name.removeSuffix(".pdf").removeSuffix(".PDF").ifEmpty { src.name }

        // 内容已经在库里：只要还有一条能打开的路径，就不再拷第二份（同 Mac 的 findOrCreate 语义）
        val known = store.variantByHash(hash)
        if (known != null && Workspace.firstOpenablePdf(workspaceDir, store, known.documentId) != null) {
            val doc = store.document(known.documentId)
            if (doc != null) {
                Log.i(TAG, "《${doc.title}》内容已在库里（hash 命中），跳过拷贝")
                return Result.Duplicate(doc)
            }
        }

        val pdfDir = File(workspaceDir, Workspace.PDFS_DIR)
        require(pdfDir.isDirectory || pdfDir.mkdirs()) { "建不了 ${Workspace.PDFS_DIR} 目录" }
        val rel = "${Workspace.PDFS_DIR}/${UUID.randomUUID()}.pdf"
        val dst = File(workspaceDir, rel)
        try {
            src.inputStream().use { ins -> dst.outputStream().use { outs -> ins.copyTo(outs, CHUNK) } }
        } catch (e: Exception) {
            // 拷了一半的文件必须清掉：留着它只会让库里多一条打得开却是残页的路径
            runCatching { dst.delete() }
            Log.e(TAG, "拷贝失败：${src.absolutePath} → $rel", e)
            throw e
        }
        val doc = try {
            store.findOrCreate(hash = hash, title = title, pageCount = pages, path = rel)
        } catch (e: Exception) {
            runCatching { dst.delete() }   // 入库失败 = 这份副本没人认领，别留在工作区里
            throw e
        }
        Log.i(TAG, "已添加《$title》$pages 页 ${src.length()}B → $rel")
        return Result.Added(doc, src.length())
    }
}
