package com.xvan.unireader.local.store

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.xvan.unireader.local.Workspace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认图层按文档区分（v29，2026-11-24，`LibraryStore.inkLayerRowId`）：默认图层在库里的行 id = 所属文档 id，
 * 模型里照旧是 `00…01`。同 Mac `spike/default-layer-test.swift` 的 1）~3）。
 *
 * 全程在 `cacheDir` 里做，不碰用户的工作区。
 * 跑：`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.xvan.unireader.local.store.DefaultLayerStoreTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`
 */
class DefaultLayerStoreTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val def = LibInkLayer.DEFAULT_ID

    private fun freshWs(name: String): File {
        val parent = File(ctx.cacheDir, "layertest").apply { mkdirs() }
        File(parent, "$name.unrd").deleteRecursively()
        return Workspace.create(parent, name)
    }

    private fun rows(ws: File): List<Triple<String, String, String>> =
        SQLiteDatabase.openDatabase(File(ws, "UniReader/library.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id, document_id, name FROM ink_layer ORDER BY document_id", null).use { c ->
                buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) }
            }
        }

    private fun layer(id: String, doc: String, name: String, visible: Boolean = true) =
        LibInkLayer(id, doc, name, "red", 0, visible, "2026-11-24T00:00:00.000Z")

    @Test
    fun 两篇各有默认层_互不覆盖() {
        val ws = freshWs("two")
        LibraryStore.open(ws).use { s ->
            val a = s.findOrCreate("HASH-A", "A", 3, "PDFs/a.pdf").id
            val b = s.findOrCreate("HASH-B", "B", 3, "PDFs/b.pdf").id
            s.upsertInkLayer(layer(def, a, "A 的图层", visible = false))
            s.ensureDefaultLayer(b)
            val r = rows(ws)
            assertEquals("库里两行（修之前只有一行）", 2, r.size)
            assertTrue(r.any { it.first == a && it.second == a && it.third == "A 的图层" })
            assertTrue(r.any { it.first == b && it.second == b })
            val la = s.inkLayers(a).single()
            assertEquals(def, la.id)
            assertEquals("A 的图层", la.name)
            assertEquals("B 补建默认层没动 A 的可见性", false, la.visible)
            assertEquals(def, s.inkLayers(b).single().id)
            s.ensureDefaultLayer(b)
            assertEquals("再补一次不多出行", 2, rows(ws).size)
        }
    }

    @Test
    fun 老库的默认层行改成所属文档id() {
        val ws = freshWs("legacy")
        val b = LibraryStore.open(ws).use { it.findOrCreate("HASH-B", "B", 3, "PDFs/b.pdf").id }
        SQLiteDatabase.openDatabase(File(ws, "UniReader/library.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "INSERT INTO ink_layer(id,document_id,name,color_key,sort_order,visible,created_at) VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any?>(def, b, "老行", "red", 0, 1, "2026-08-11T00:00:00Z"),
            )
        }
        LibraryStore.open(ws).use { s ->
            assertEquals(1, s.normalizeDefaultInkLayers())
            val r = rows(ws).single()
            assertEquals(b, r.first)
            assertEquals("老行", r.third)
            assertEquals(def, s.inkLayers(b).single().id)
            assertEquals("再跑一次什么都不做", 0, s.normalizeDefaultInkLayers())
        }
    }
}
