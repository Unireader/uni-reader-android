package com.xvan.unireader.local

import androidx.test.platform.app.InstrumentationRegistry
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.Schema
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **本机新建工作区**（[Workspace.create] + [Schema]）的插桩测试。
 *
 * 与 `store/LibraryStoreTest` 的分工：那边验「Mac 造的库我读得对」，这边验「我造的库结构对」。
 * 两件事都要有——只验自己写自己读的话，安卓建出来的库拿到 Mac 上打不开也发现不了。
 * 所以这里逐列比对表结构（Mac `LibraryStore.migrate()` v12 的产物），而不是只 assert 能写能读。
 *
 * 全程在 `cacheDir` 里做，不需要任何存储权限，也碰不到用户的工作区。
 * 跑：`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.xvan.unireader.local.WorkspaceCreateTest`
 */
class WorkspaceCreateTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun freshParent(): File =
        File(ctx.cacheDir, "wscreate").apply { deleteRecursively(); mkdirs() }

    private fun columnsOf(store: LibraryStore, table: String): Set<String> =
        store.tableColumns(table)

    @Test
    fun 新建的工作区能被自己打开() {
        val parent = freshParent()
        val dir = Workspace.create(parent, "测试书库")
        assertEquals("测试书库.unrd", dir.name)
        assertTrue("PDFs/ 该建出来", File(dir, Workspace.PDFS_DIR).isDirectory)

        val check = Workspace.check(dir)
        assertTrue("刚建的工作区必须通过校验：$check", check is Workspace.Check.OK)
        assertTrue("刚建的库必须可写", !(check as Workspace.Check.OK).readOnly)

        LibraryStore.open(dir).use { store ->
            assertEquals(Schema.VERSION.toString(), store.meta("schema_version"))
            assertEquals("测试书库", store.workspaceName())
            assertNotNull("created_at 该写上", store.meta("created_at"))
            assertTrue("新库里不该有文档", store.allDocuments().isEmpty())
        }
    }

    /** 逐列比对：安卓建的库要能被 Mac 直接打开，缺一列 Mac 那边就是 SQL 报错 */
    @Test
    fun 表结构与Mac的v12一致() {
        val dir = Workspace.create(freshParent(), "结构")
        LibraryStore.open(dir).use { store ->
            assertEquals(
                setOf(
                    "id", "title", "page_count", "added_at", "last_opened_at", "sort_order",
                    "read_page", "read_frac", "read_zoom", "read_hfrac", "group_name", "canvas_mode",
                ),
                columnsOf(store, "document"),
            )
            assertEquals(
                setOf("id", "document_id", "content_hash", "page_count", "added_at"),
                columnsOf(store, "variant"),
            )
            assertEquals(
                setOf(
                    "id", "variant_id", "path", "is_valid", "last_validated_at",
                    "in_workspace", "is_relative",
                ),
                columnsOf(store, "location"),
            )
            assertEquals(
                setOf(
                    "id", "document_id", "kind", "page", "anchor_x", "anchor_y", "anchor_w",
                    "anchor_h", "payload", "created_at", "updated_at",
                ),
                columnsOf(store, "note"),
            )
            assertEquals(
                setOf("content_hash", "page", "provider", "payload", "lang", "created_at"),
                columnsOf(store, "ocr_page"),
            )
            assertEquals(
                setOf("id", "document_id", "name", "color_key", "sort_order", "visible", "created_at"),
                columnsOf(store, "ink_layer"),
            )
            assertEquals(
                setOf(
                    "id", "document_id", "title", "anchor_page", "anchor_x", "anchor_y",
                    "bg", "pattern", "show_page", "created_at", "updated_at",
                ),
                columnsOf(store, "scratch_pad"),
            )
        }
    }

    @Test
    fun 同名不会覆盖已有的工作区() {
        val parent = freshParent()
        Workspace.create(parent, "重名")
        val e = runCatching { Workspace.create(parent, "重名") }.exceptionOrNull()
        assertNotNull("同名必须报错，绝不能覆盖", e)
        assertTrue("要说清是重名：${e?.message}", e!!.message!!.contains("重名.unrd"))
    }

    @Test
    fun 名字里的路径分隔符被净化() {
        assertEquals("a-b-c", Workspace.sanitizeName(" a/b:c "))
        assertEquals("x-y", Workspace.sanitizeName("x?y."))
        assertEquals("", Workspace.sanitizeName("   "))
        val dir = Workspace.create(freshParent(), "上/下")
        assertEquals("上-下.unrd", dir.name)
    }

    /** hash 是文档的身份：同一份内容入两次只该有一本书，第二次只多一条路径 */
    @Test
    fun 同一份内容入库两次不会多出一本书() {
        val dir = Workspace.create(freshParent(), "去重")
        LibraryStore.open(dir).use { store ->
            val h = "0".repeat(64)
            val a = store.findOrCreate(h, "同一本", 10, "PDFs/a.pdf")
            val b = store.findOrCreate(h, "改了个名", 10, "PDFs/b.pdf")
            assertEquals("同 hash 必须复用同一篇文档", a.id, b.id)
            assertEquals("书库里只该有一本", 1, store.allDocuments().size)
            assertEquals("标题以第一次为准", "同一本", store.document(a.id)!!.title)
            assertEquals("两条路径都该在", 2, store.locations(a.id).size)
            assertTrue("路径必须是工作区相对的", store.locations(a.id).all { it.inWorkspace })
        }
    }

    /** 与 Mac `FileHasher.sha256` 同口径：小写十六进制，不带任何前缀 */
    @Test
    fun sha256与Mac同口径() {
        val f = File(ctx.cacheDir, "hash.bin").apply { writeBytes("abc".toByteArray()) }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PdfImport.sha256(f),
        )
    }
}
