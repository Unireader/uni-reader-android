package com.xvan.unireader.local.server

import androidx.test.platform.app.InstrumentationRegistry
import com.xvan.unireader.local.ImageAssets
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.local.mirror.MirrorStore
import com.xvan.unireader.local.store.LibraryStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 安卓客户端对着**真服务器**的端到端（`../docs/SERVER-MODE-PLAN.md` S3；Mac 那边是 `../spike/server-client-test.swift`）。
 * 平板扮演两台设备（A = 上传的原件，B = 从服务器建的缓存），走：配对 → 上传 → 建缓存 → 补图片 / 下 PDF →
 * 两边各改一处后完整同步 → 删除传播 → 开着时只推（记基线）→ 变回本地。
 *
 * 服务器由 `../spike/android-server-e2e.sh` 起（Mac 上的临时服务器 + `adb reverse`），地址与配对码经插桩参数传进来；
 * 没传就跳过（日常 `connectedDebugAndroidTest` 不会因此变红）。全程在 `cacheDir` 里做。
 */
class ServerSyncE2ETest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** 1×1 的 PNG（服务器只校验 SHA-256，不解图；给个真 PNG 免得哪天要解） */
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
        0x89.toByte(), 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
        0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
        0x42, 0x60, 0x82.toByte(),
    )

    private fun titles(ws: File, docId: String): Set<String> =
        LibraryStore.open(ws, readOnly = true).use { s -> s.bookmarks(docId).map { it.title }.toSet() }

    @Test
    fun 配对_上传_建缓存_双向同步_删除_只推_变回本地() {
        val url = args.getString("serverUrl")
        val code = args.getString("pairingCode")
        assumeTrue("没给 serverUrl / pairingCode，跳过（用 spike/android-server-e2e.sh 跑）", url != null && code != null)
        val root = File(ctx.cacheDir, "server-e2e").apply { deleteRecursively(); mkdirs() }

        // ① 配对
        val paired = ServerClient.pair(url!!, code!!, "安卓 e2e")
        assertEquals(64, paired.token.length)
        val acc = ServerAccount(url, paired.serverId, paired.serverName.orEmpty(), paired.deviceId, paired.token)
        val client = acc.client()
        assertTrue("配对码错要被拒", runCatching { ServerClient.pair(url, "WRONGCODE0", "x") }.exceptionOrNull() is ServerClient.Failure)

        // ② 造原件 A：一本书（真文件，哈希 = 内容）、一个书签、一张图片、一篇 Markdown 笔记
        val a = Workspace.create(root, "安卓e2e")
        val pdfBytes = "%PDF-1.4\n% unireader e2e\n".toByteArray() + ByteArray(300_000) { (it % 251).toByte() }
        val hash = sha256(pdfBytes)
        val rel = "${Workspace.PDFS_DIR}/E2E-0001.pdf"
        File(a, rel).apply { parentFile?.mkdirs(); writeBytes(pdfBytes) }
        val sha = sha256(png)
        lateinit var docId: String
        LibraryStore.open(a).use { s ->
            docId = s.findOrCreate(hash, "端到端的书", 3, rel, inWorkspace = true).id
            assertNotNull(s.insertBookmark(docId, 0, 0.5f, "A 的书签"))
            File(ImageAssets.folder(a), "$sha.png").apply { parentFile?.mkdirs(); writeBytes(png) }
            s.withMirrorDb { db ->
                db.exec(
                    "INSERT INTO image(sha256,ext,width,height,bytes,created_at) VALUES(?,?,?,?,?,?)",
                    arrayOf<Any?>(sha, "png", 1L, 1L, png.size.toLong(), "2026-10-29T00:00:00.000Z"),
                )
            }
        }
        File(a, "Notes/子目录/笔记.md").apply { parentFile?.mkdirs(); writeText("# 标题\n\n[[别的]]\n") }
        File(a, "Notes/.trash/扔掉的.md").apply { parentFile?.mkdirs(); writeText("不该上传") }

        // ③ 上传：原件就地成为缓存
        val info = LibraryStore.open(a).use { s ->
            s.ensureWorkspaceId()
            val plan = ServerSyncIO.prepareUpload(s, a, File(root, "staging"))
            assertEquals(0, plan.missingPdfs)
            assertTrue(plan.files.any { it.rel == "Notes/子目录/笔记.md" })
            assertFalse("Notes/.trash 不上传", plan.files.any { it.rel.contains(".trash") })
            val info = ServerSyncIO.upload(plan, client) { _, _, _ -> }
            s.setMeta(SyncClient.META_SERVER_URL, url)
            paired.serverId?.let { s.setMeta(SyncClient.META_SERVER_ID, it) }
            s.withMirrorDb { SyncClient.writeBase(it, plan.base, info.head) }
            info
        }
        assertTrue(client.workspaces().any { it.id == info.id })
        val link = ServerSyncIO.serverLink(a)
        assertEquals(url, link?.url)
        assertEquals(info.id.uppercase(), link?.workspaceId?.uppercase())
        // 上传之后本机没有要推的
        LibraryStore.open(a).use { s -> s.withMirrorDb { assertTrue(SyncClient.localChanges(it, a).isEmpty()) } }

        // ④ 另一台设备：建缓存 B（库 + 图片；PDF 没有，按需下载）
        val b = ServerSyncIO.createCache(File(root, "caches/host"), "安卓e2e", url, paired.serverId, client, info.id) {}
        assertEquals(setOf("A 的书签"), titles(b, docId))
        assertTrue("图片补下来了", File(ImageAssets.folder(b), "$sha.png").isFile)
        assertNull("PDF 还没下", LibraryStore.open(b, readOnly = true).use { Workspace.firstOpenablePdf(b, it, docId) })
        assertTrue(ServerSyncIO.downloadPdf(b, docId, client, info.id))
        val got = LibraryStore.open(b, readOnly = true).use { Workspace.firstOpenablePdf(b, it, docId) }
        assertArrayEquals(pdfBytes, got!!.readBytes())
        assertFalse("比上限大的不下", ServerSyncIO.downloadPdf(b, docId, client, info.id, maxBytes = 10))
        assertEquals(b, ServerSyncIO.findCache(info.id, File(root, "caches")))

        // ⑤ 两边各加一个书签 → A 推、B 推拉、A 拉 → 两边都有
        LibraryStore.open(a).use { it.insertBookmark(docId, 1, 0f, "A2") }
        LibraryStore.open(b).use { it.insertBookmark(docId, 2, 0f, "B2") }
        assertEquals(1, ServerSyncIO.closedRound(a, client, info.id).pushed)
        val rb = ServerSyncIO.closedRound(b, client, info.id)
        assertEquals(1, rb.pushed)
        assertTrue(rb.pulled >= 1)
        ServerSyncIO.closedRound(a, client, info.id)
        val want = setOf("A 的书签", "A2", "B2")
        assertEquals(want, titles(a, docId))
        assertEquals(want, titles(b, docId))

        // ⑥ B 删掉 A2 → A 也没了（删除传播）
        LibraryStore.open(b).use { s -> s.bookmarks(docId).first { it.title == "A2" }.let { s.deleteNote(it.id) } }
        ServerSyncIO.closedRound(b, client, info.id)
        ServerSyncIO.closedRound(a, client, info.id)
        assertEquals(setOf("A 的书签", "B2"), titles(a, docId))

        // ⑦ 开着时只推：找改动 → 推 → 只记基线（不开事务）→ 再找就没有了；B 完整一轮拿到它
        LibraryStore.open(a).use { s ->
            s.insertBookmark(docId, 0, 0.9f, "开着时加的")
            s.withMirrorDb { db ->
                val ch = SyncClient.localChanges(db, a)
                assertEquals(1, ch.size)
                ServerSyncIO.uploadAll(ServerSyncIO.filesToUpload(ch, db, a), client, info.id)
                val resp = SyncWire.decodePushResponse(
                    client.push(info.id, SyncWire.encode(SyncWire.PushRequest(com.xvan.unireader.local.store.Schema.VERSION, ch))),
                )
                assertEquals(SyncWire.Outcome.APPLIED, resp.results.single().outcome)
                SyncClient.recordPushed(db, ch, resp)
                assertTrue(SyncClient.localChanges(db, a).isEmpty())
            }
        }
        ServerSyncIO.closedRound(b, client, info.id)
        assertTrue("开着时加的" in titles(b, docId))

        // ⑧ 取消：取消开关一拉，正在飞的 / 之后的请求都抛 Cancelled
        val cancel = ServerClient.Cancel().apply { cancel() }
        assertTrue(runCatching { acc.client(cancel).workspaces() }.exceptionOrNull() is ServerClient.Cancelled)

        // ⑨ 变回本地：服务器记录与基线表都没了，资料一条不少；服务器那份还在
        LibraryStore.open(a).use { s -> s.withMirrorDb { SyncClient.detach(it) } }
        assertNull(ServerSyncIO.serverLink(a))
        LibraryStore.open(a, readOnly = true).use { s -> s.withMirrorDb { assertFalse(MirrorStore.hasSyncBase(it)) } }
        assertEquals(setOf("A 的书签", "B2", "开着时加的"), titles(a, docId))
        assertTrue(client.workspaces().any { it.id == info.id })
    }

    /**
     * Mac ↔ 安卓互通：脚本先让 Mac 造一个工作区传上去（`macWorkspace` 参数），这里建缓存、核对 Mac 写的行与 PDF，
     * 加一个书签推回去；脚本随后让 Mac 拉下来核对（`../spike/android-server-interop.swift verify`）。
     */
    @Test
    fun Mac建的工作区_安卓打开并改回去() {
        val url = args.getString("serverUrl")
        val code = args.getString("pairingCode")
        val macWid = args.getString("macWorkspace")
        assumeTrue("没给 macWorkspace，跳过", url != null && code != null && macWid != null)
        val root = File(ctx.cacheDir, "server-e2e-mac").apply { deleteRecursively(); mkdirs() }
        val paired = ServerClient.pair(url!!, code!!, "安卓 e2e（Mac 那份）")
        val client = ServerClient(url, paired.token)

        val m = ServerSyncIO.createCache(File(root, "caches/host"), "Mac建的", url, paired.serverId, client, macWid!!) {}
        LibraryStore.open(m, readOnly = true).use { s ->
            assertEquals("Mac建的", s.workspaceName())
            s.withMirrorDb { db ->
                assertEquals(1, db.query("SELECT id FROM note WHERE id='MAC-N1'") { it.getString(0) }.size)
            }
        }
        assertTrue(ServerSyncIO.downloadPdf(m, "MD1", client, macWid))
        val f = LibraryStore.open(m, readOnly = true).use { Workspace.firstOpenablePdf(m, it, "MD1") }
        assertEquals(LibraryStore.open(m, readOnly = true).use { it.variants("MD1").single().contentHash }, sha256(f!!.readBytes()))
        LibraryStore.open(m).use { assertNotNull(it.insertBookmark("MD1", 0, 0.2f, "安卓加的")) }
        assertEquals(1, ServerSyncIO.closedRound(m, client, macWid).pushed)
        LibraryStore.open(m).use { s -> s.withMirrorDb { assertTrue(SyncClient.localChanges(it, m).isEmpty()) } }
    }
}
