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
        val bChanges = LibraryStore.open(b).use { st -> st.withMirrorDb { db -> SyncClient.localChanges(db, b).map { "${it.table}:${it.op}:${it.rowId.take(8)}" } } }
        val rb = ServerSyncIO.closedRound(b, client, info.id)
        assertEquals("B 要推的：$bChanges", 1, rb.pushed)
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
    /**
     * 笔记文件同步（`NoteSync`，`../docs/PROTOCOL.md §9.5`）对着真服务器：上传时带上去的笔记另一台拉得到、改动来回、
     * 两边都改 → 冲突副本两份都在、删除传播（挪进 `.trash`）、开着时只推。
     */
    @Test
    fun 笔记文件_拉取_来回改_冲突副本_删除() {
        val url = args.getString("serverUrl")
        val code = args.getString("pairingCode")
        val macWid = args.getString("macWorkspace")
        assumeTrue("没给 macWorkspace，跳过（用 spike/android-server-e2e.sh 跑）", url != null && code != null && macWid != null)
        val root = File(ctx.cacheDir, "server-e2e-notes").apply { deleteRecursively(); mkdirs() }
        val paired = ServerClient.pair(url!!, code!!, "安卓 笔记")
        val client = ServerClient(url, paired.token)
        // 两台「设备」= 同一个服务器工作区（Mac 造的那份）的两份缓存
        val wid = macWid!!
        val a = ServerSyncIO.createCache(File(root, "cachesA/host"), "Mac建的", url, paired.serverId, client, wid) {}
        val b = ServerSyncIO.createCache(File(root, "cachesB/host"), "Mac建的", url, paired.serverId, client, wid) {}
        fun note(ws: File, rel: String) = File(ws, "Notes/$rel")
        note(a, "数学/极限 (1)+x&y.md").apply { parentFile?.mkdirs(); writeText("# 极限\n") }
        note(a, "要删的.md").writeText("x")
        note(a, ".trash/扔掉的.md").apply { parentFile?.mkdirs(); writeText("不该上传") }
        fun round(ws: File, dev: String) = NoteSync.sync(ws, client, wid, dev, full = true)

        // ① A 新建的两篇传上去（`.trash` 里的不传），B 拉下来（文件名里的 + & ( ) 空格都原样）
        val ra = round(a, "A")
        assertEquals(ra.summary + ra.failed, 2, ra.pushed)
        val rb = round(b, "B")
        assertEquals(2, rb.pulled)
        assertTrue(note(b, "数学/极限 (1)+x&y.md").isFile && note(b, "要删的.md").isFile)
        assertFalse(note(b, ".trash/扔掉的.md").exists())
        assertEquals("# 极限\n", note(b, "数学/极限 (1)+x&y.md").readText())
        assertTrue(rb.failed.toString(), rb.failed.isEmpty())

        // ② B 改正文、新建一篇 → A 拉到
        note(b, "数学/极限 (1)+x&y.md").writeText("# 极限\n\nB 加了一行\n")
        note(b, "B 新建.md").writeText("b")
        assertEquals(2, round(b, "B").pushed)
        val ra2 = round(a, "A")
        assertEquals(2, ra2.pulled)
        assertEquals("# 极限\n\nB 加了一行\n", note(a, "数学/极限 (1)+x&y.md").readText())

        // ③ 开着时只推：A 改了、不问服务器
        note(a, "B 新建.md").writeText("a 改过")
        val po = NoteSync.sync(a, client, wid, "A", full = false)
        assertEquals(1, po.pushed)
        assertEquals(1, round(b, "B").pulled)
        assertEquals("a 改过", note(b, "B 新建.md").readText())

        // ④ 两边都改同一篇 → 后同步的那台：服务器那份换到原路径，自己那份另存成冲突副本并上传；两份字节都在
        note(a, "数学/极限 (1)+x&y.md").writeText("A 的版本")
        note(b, "数学/极限 (1)+x&y.md").writeText("B 的版本")
        assertEquals(1, round(a, "A").pushed)
        val rc = round(b, "平板(B)")
        assertEquals(1, rc.conflicts.size)
        assertEquals("A 的版本", note(b, "数学/极限 (1)+x&y.md").readText())
        val copy = File(b, rc.conflicts[0])
        assertTrue("副本名带设备名：${copy.name}", copy.name.startsWith("极限 (1)+x&y (冲突 平板-B- ") && copy.name.endsWith(".md"))
        assertEquals("B 的版本", copy.readText())
        val ra4 = round(a, "A")
        assertEquals("A 拉到那份副本", 1, ra4.pulled)
        assertEquals("B 的版本", File(a, rc.conflicts[0]).readText())

        // ⑤ A 删一篇 → 服务器记墓碑 → B 的那篇挪进 Notes/.trash（不是抹掉）
        assertTrue(note(a, "要删的.md").delete())
        assertEquals(1, round(a, "A").removedRemote)
        val rd = round(b, "B")
        assertEquals(1, rd.removedLocal)
        assertFalse(note(b, "要删的.md").exists())
        val trashed = File(b, "Notes/.trash").walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertEquals(listOf("要删的.md"), trashed)
        // 再跑一轮什么都不动（.trash 里的不会被传上去）
        val quiet = round(b, "B")
        assertFalse(quiet.summary, quiet.touched)
        assertTrue(quiet.failed.isEmpty())
        // 基线文件写下来了，能读回
        assertTrue(NoteSync.loadBase(b).keys.any { it.startsWith("Notes/") })
    }

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
