package com.xvan.unireader.local.mirror

import androidx.test.platform.app.InstrumentationRegistry
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.local.store.Db
import com.xvan.unireader.local.store.Iso
import com.xvan.unireader.local.store.LibLocation
import com.xvan.unireader.local.store.LibNote
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.MirrorFp
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 建离线镜像（[MirrorBuilder] + [MirrorStore]）的插桩测试。方案 `../OFFLINE-MIRROR-PLAN.md` §8.1。
 *
 * **为什么在 androidTest 而不是 JVM 单测**：`android.database.sqlite` 在 JVM 里是空壳，
 * 而这一整套的要害全在库上（VACUUM INTO / sync_base / 血缘 meta）。`org.json` 同理。
 *
 * 与 Mac `spike/mirror-build-test.swift` 是**同一份用例的两端实现**——那边 50 项，这边逐条对齐
 * 关键的几项，另加一条 Mac 生成的借出记录 JSON 向量做字节比对。
 *
 * 全程在 `cacheDir` 里做，不需要任何存储权限，也碰不到用户的工作区。
 * 跑：`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.xvan.unireader.local.mirror.MirrorBuilderTest`
 */
class MirrorBuilderTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun freshRoot(): File =
        File(ctx.cacheDir, "mirrortest").apply { deleteRecursively(); mkdirs() }

    private fun writeFile(f: File, bytes: Int) {
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes) { 0x41 })
    }

    /** 与 [Workspace.resolvePdf] 同口径：工作区内/相对 → 拼工作区目录；否则当绝对路径 */
    private fun resolver(ws: File): (LibLocation) -> File? = { loc ->
        if (loc.inWorkspace || loc.isRelative) File(ws, loc.path) else File(loc.path)
    }

    /** 造一个有三本书的源工作区：① 工作区内副本 ② 外部文件 ③ 有文件但不进计划 */
    private class Fixture(val root: File, val ws: File, val store: LibraryStore) {
        lateinit var docA: String
        lateinit var docB: String
        lateinit var docC: String
        lateinit var relA: String
        lateinit var relC: String
        lateinit var extFile: File
    }

    private fun makeSource(root: File): Fixture {
        val ws = Workspace.create(root, "源")
        val f = Fixture(root, ws, LibraryStore.open(ws))
        val store = f.store

        // 安卓的 findOrCreate 一步建 document+variant+location（与 Mac 的两步 API 不同），
        // `inWorkspace` 决定那条 location 存的是工作区相对路径还是绝对路径。
        f.relA = "${Workspace.PDFS_DIR}/aaaaaaaa-0000-4000-8000-000000000001.pdf"
        writeFile(File(ws, f.relA), 1024)
        f.docA = store.findOrCreate("hA", "工作区内的书", 10, f.relA, inWorkspace = true).id

        f.extFile = File(root, "外部/外面的书.pdf")
        writeFile(f.extFile, 2048)
        f.docB = store.findOrCreate("hB", "外部的书", 20, f.extFile.absolutePath, inWorkspace = false).id

        f.relC = "${Workspace.PDFS_DIR}/cccccccc-0000-4000-8000-000000000003.pdf"
        writeFile(File(ws, f.relC), 4096)
        f.docC = store.findOrCreate("hC", "不带正文的书", 30, f.relC, inWorkspace = true).id

        for ((i, id) in listOf(f.docA, f.docB, f.docC).withIndex()) {
            for (k in 0 until 3) {
                store.upsertNote(
                    LibNote(
                        id = UUID.randomUUID().toString(), documentId = id, kind = 2, page = k,
                        anchorX = 0.1, anchorY = 0.2, anchorW = 0.3, anchorH = 0.4,
                        payload = """{"w":$k}""".toByteArray(), createdAt = Iso.now(), updatedAt = Iso.now(),
                    ),
                )
            }
            store.updateProgress(id, i, 0.5, 1.5, 0.1)
        }
        store.setMeta("note_types", """[{"k":"v"}]""")
        return f
    }

    private fun metaOf(db: Db, k: String): String? =
        db.query("SELECT value FROM meta WHERE key=?", arrayOf(k)) { it.getString(0) }.firstOrNull()

    private fun countOf(db: Db, sql: String): Int =
        db.query(sql) { it.getInt(0) }.firstOrNull() ?: -1

    @Test
    fun 建镜像_库全量而PDF选择性() {
        val root = freshRoot()
        val f = makeSource(root)
        f.store.use { store ->
            assertEquals("源库 9 条笔记", 9, store.noteCount())

            val plan = MirrorBuilder.Plan(setOf(f.docA, f.docB), sourceHint = "U 盘 / 源.unrd")
            val est = MirrorBuilder.estimate(f.ws, store, plan, resolver(f.ws))
            assertEquals("算出 2 个文件（③ 没勾）", 2, est.files)
            assertTrue("字节数含库本身 + 两个 PDF", est.bytes > 1024 + 2048)
            assertTrue(est.unresolved.isEmpty())

            val dst = File(root, "镜像/源.unrd")
            val steps = ArrayList<String>()
            val res = MirrorBuilder.create(
                f.ws, store, dst, plan, deviceId = "test-device", resolve = resolver(f.ws),
                progress = { s, _ -> if (steps.lastOrNull() != s) steps.add(s) },
            )
            assertEquals("拷了 2 个 PDF", 2, res.copiedFiles)
            assertEquals("外部文件内化 1 条", 1, res.internalized)
            assertEquals("完成", steps.lastOrNull())
            // `VACUUM INTO` 要 SQLite 3.27（API 30）。本模块 minSdk 26，所以代码里留了「整文件拷」
            // 的兜底；但只要跑在 30+ 上就必须走 VACUUM 这条——不断言的话哪天 VACUUM 悄悄失效、
            // 全都退到兜底路径也没人知道（那条路径只在独占队列上才安全，不该是常态）。
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                assertFalse("API ${android.os.Build.VERSION.SDK_INT} 上该走 VACUUM INTO 而不是兜底", res.usedFileCopyFallback)
            }

            assertTrue("镜像有 library.sqlite", File(dst, Workspace.DB_REL).isFile)
            assertTrue(
                "工作区内副本保持同一条相对路径（镜像库那行 location 原样有效）",
                File(dst, f.relA).isFile,
            )
            assertFalse("没勾的书不拷 PDF（库全量、PDF 选择性）", File(dst, f.relC).isFile)
            val strays = File(dst, Workspace.PDFS_DIR).listFiles()?.filter { it.name.endsWith(".part") }.orEmpty()
            assertTrue("没有 .part 残留（原子 rename）：$strays", strays.isEmpty())

            Db.open(File(dst, Workspace.DB_REL)).use { mdb ->
                assertEquals("3 本书全在（含没带 PDF 的那本）", 3, countOf(mdb, "SELECT COUNT(*) FROM document"))
                assertEquals("9 条笔记全在", 9, countOf(mdb, "SELECT COUNT(*) FROM note"))
                assertEquals(
                    "阅读进度跟着过来", 1.5,
                    mdb.query("SELECT read_zoom FROM document WHERE id=?", arrayOf(f.docA)) { it.getDouble(0) }
                        .first(), 1e-9,
                )

                // 外部文件内化
                val locsB = mdb.query(
                    "SELECT path,in_workspace FROM location WHERE variant_id IN " +
                        "(SELECT id FROM variant WHERE document_id=?)",
                    arrayOf(f.docB),
                ) { it.getString(0) to it.getInt(1) }
                assertEquals(
                    "原来那条外部路径的行留着不动（location 本就不参与同步，删它没收益且是破坏性操作）",
                    2, locsB.size,
                )
                val inWs = locsB.filter { it.second == 1 }
                assertEquals("镜像库里补了 1 条 in_workspace 的 location", 1, inWs.size)
                assertTrue("那条相对路径在镜像里解析得到", File(dst, inWs.first().first).isFile)

                // 血缘 meta
                assertEquals("mirror_of == 源库 workspace_id", store.workspaceId(), metaOf(mdb, MirrorStore.META_MIRROR_OF))
                assertEquals(res.mirrorId, metaOf(mdb, MirrorStore.META_MIRROR_ID))
                assertEquals("U 盘 / 源.unrd", metaOf(mdb, MirrorStore.META_MIRROR_SOURCE_HINT))
                assertTrue(
                    "🔴 镜像换了自己的 workspace_id（不换的话『扫盘按 id 找源』会把镜像自己也认成源）",
                    metaOf(mdb, "workspace_id") != store.workspaceId(),
                )
                assertNull("『本机开着哪几篇』已清", metaOf(mdb, "open_documents"))
                assertNull("源库的借出记录没跟着复制到镜像里", metaOf(mdb, MirrorStore.META_CHECKOUTS))
                assertEquals("工作区名照旧（它参与同步）", "源", metaOf(mdb, "workspace_name"))

                // sync_base 基线
                assertTrue(MirrorStore.hasSyncBase(mdb))
                val base = MirrorStore.syncBase(mdb)
                assertEquals("note 基线 9 行", 9, base["note"]?.size)
                assertEquals("document 基线 3 行", 3, base["document"]?.size)
                assertNull("🔴 location 不进基线（设备本地事实）", base["location"])
                assertEquals(
                    "meta 只收白名单两个键", listOf("note_types", "workspace_name"),
                    base["meta"]?.keys?.sorted(),
                )
                assertEquals(res.baseRows, base.values.sumOf { it.size })
                assertEquals(
                    "🔴 刚建好的镜像重算 note 指纹 == 基线（diff 为空，这是整个合并方案的地基）",
                    base["note"], MirrorStore.fingerprints(mdb, MirrorFp.spec("note")!!),
                )
            }

            // 源库的借出记录
            val cos = MirrorStore.decodeCheckouts(store.meta(MirrorStore.META_CHECKOUTS))
            assertEquals(1, cos.size)
            assertEquals(res.mirrorId, cos.first().mirrorId)
            assertEquals("test-device", cos.first().deviceId)
            assertEquals("记下了借走时的笔记条数（纯展示）", 9, cos.first().noteCount)
            assertNull("从未同步过 → lastSyncedAt 为 null", cos.first().lastSyncedAt)
        }
    }

    @Test
    fun 拦截_目标已存在与镜像的镜像() {
        val root = freshRoot()
        val f = makeSource(root)
        f.store.use { store ->
            val plan = MirrorBuilder.Plan(setOf(f.docA))
            val dst = File(root, "镜像/源.unrd")
            MirrorBuilder.create(f.ws, store, dst, plan, "test-device", resolver(f.ws))

            try {
                MirrorBuilder.create(f.ws, store, dst, plan, "test-device", resolver(f.ws))
                fail("目标已存在应当报错而不是覆盖")
            } catch (e: MirrorBuilder.Failure) {
                assertTrue("错误文案是人话：${e.message}", e.message!!.contains("换个名字"))
            }

            LibraryStore.open(dst).use { mirrorStore ->
                try {
                    MirrorBuilder.create(
                        dst, mirrorStore, File(root, "镜像的镜像.unrd"), plan, "test-device", resolver(dst),
                    )
                    fail("镜像的镜像应当被拦")
                } catch (e: MirrorBuilder.Failure) {
                    assertTrue(e.message!!.contains("离线镜像"))
                }
            }
        }
    }

    @Test
    fun 中途失败要连整个骨架一起清掉() {
        val root = freshRoot()
        val f = makeSource(root)
        f.store.use { store ->
            // 造一次**建好目录之后**才失败的：把外部 PDF 的读权限去掉，拷到它那一步炸。
            // （在建目录之前就失败的那种没意思——本来也没建出东西）
            //
            // ⚠️ 别改成「把源文件换成目录」：`pickSource` 用 `isFile` 过滤，目录会被**静默跳过**，
            // 于是整次建镜像成功、这条用例假绿。第一版就是这么写的，模拟器上当场抓到。
            val dst = File(root, "半途而废.unrd")
            assertTrue("去读权限要成功，否则这条用例证明不了任何事", f.extFile.setReadable(false, false))
            try {
                MirrorBuilder.create(
                    f.ws, store, dst, MirrorBuilder.Plan(setOf(f.docA, f.docB)),
                    "test-device", resolver(f.ws),
                )
                fail("源文件读不了时应当失败")
            } catch (e: Exception) {
                assertFalse(
                    "失败后不留半个骨架（留着的话下次扫描列出来、点开又说没库，比没建成难查）",
                    dst.exists(),
                )
            } finally {
                f.extFile.setReadable(true, false)
            }
        }
    }

    @Test
    fun 借出记录的跨端向量() {
        // Mac `spike/mirror-build-test.swift` ⑪ 生成的那一行，逐字比对。
        // 两处最容易两端跑偏：① 键序；② lastSyncedAt 为 null 时**整个键省略**（Swift
        // JSONEncoder 对 nil Optional 的默认行为），写成 "last_synced_at":null 字节就对不上。
        val vector = """[{"device_id":"d-1","device_name":"小米 Pad 6","mirror_id":"m-1",""" +
            """"note_count":9,"taken_at":"2026-08-30T10:00:00.000Z"},""" +
            """{"device_id":"d-2","device_name":"MacBook","last_synced_at":"2026-08-30T12:00:00.000Z",""" +
            """"mirror_id":"m-2","note_count":12,"taken_at":"2026-08-30T11:00:00.000Z"}]"""
        val canonical = listOf(
            MirrorStore.Checkout("m-1", "d-1", "小米 Pad 6", "2026-08-30T10:00:00.000Z", null, 9),
            MirrorStore.Checkout("m-2", "d-2", "MacBook", "2026-08-30T11:00:00.000Z", "2026-08-30T12:00:00.000Z", 12),
        )
        assertEquals("编码与 Mac 逐字一致", vector, MirrorStore.encodeCheckouts(canonical))
        assertEquals("解 Mac 写的那份", canonical, MirrorStore.decodeCheckouts(vector))
        assertTrue("坏 JSON 按空处理（不让一条脏记录挡住开工作区）", MirrorStore.decodeCheckouts("这不是 JSON").isEmpty())
        assertTrue(MirrorStore.decodeCheckouts(null).isEmpty())

        val dup = MirrorStore.upsertCheckout(canonical, canonical[0].copy(deviceName = "另一台"))
        assertEquals("同 mirrorId 覆盖而不是越攒越多", 2, dup.size)
        assertNotNull(dup.firstOrNull { it.deviceName == "另一台" })
    }
}
