package com.xvan.unireader.local

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.xvan.unireader.local.exercise.ExFilter
import com.xvan.unireader.local.store.LibraryStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 习题的库读写（`LibraryStore` 习题一节 + `Trash.deleteExercises`）：读题、记作答与统计、「上一次做错的」按库里的统计挑、
 * 题目笔记、删题先归档再删 + 从回收站恢复。导入只在 Mac 做，所以题目行用裸 SQL 造（列同 `Schema.kt`）。全程在 `cacheDir` 里。
 */
class ExerciseStoreTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun freshWs(name: String): File {
        val parent = File(ctx.cacheDir, "extest").apply { mkdirs() }
        File(parent, "$name.unrd").deleteRecursively()
        return Workspace.create(parent, name)
    }

    /** 往库里造几道单选题（答案都是 B），返回文档 id。 */
    private fun seed(ws: File, ids: List<String>): String {
        val docId = LibraryStore.open(ws).use { it.findOrCreate("HASH-EX", "习题册", 10, "PDFs/ex.pdf").id }
        val f = File(ws, "UniReader/library.sqlite")
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            ids.forEachIndexed { i, id ->
                val payload = """{"stem":"题 $id","options":[{"key":"A","text":"甲"},{"key":"B","text":"乙"}],"answer":["B"]}"""
                db.execSQL(
                    "INSERT INTO exercise(id,document_id,page,seq,kind,number,payload,created_at,updated_at) VALUES(?,?,?,?,0,?,?,?,?)",
                    arrayOf<Any?>(id, docId, 0, i, "${i + 1}", payload.toByteArray(), "2026-11-01T00:00:00.000Z", "2026-11-01T00:00:00.000Z"),
                )
            }
        }
        return docId
    }

    @Test
    fun 作答统计与上一次做错的() {
        val ws = freshWs("stats")
        val docId = seed(ws, listOf("E1", "E2", "E3"))
        LibraryStore.open(ws).use { s ->
            assertEquals(listOf("E1", "E2", "E3"), s.exercises(docId).map { it.id })
            s.recordExerciseAttempt("E1", "A", false, 1000)   // 错 → 进错题本
            s.recordExerciseAttempt("E2", "B", true, 1000)
            var st = s.exerciseAttemptStats()
            val by = s.exercises(docId).associateBy { it.id }
            assertTrue(by["E1"]!!.wrong)
            assertFalse(by["E2"]!!.wrong)
            assertTrue(by["E1"]!!.lastResultWrong(st["E1"]))
            assertFalse(by["E2"]!!.lastResultWrong(st["E2"]))
            assertFalse("没做过不算", by["E3"]!!.lastResultWrong(st["E3"]))
            assertTrue(ExFilter.UNANSWERED.includes(by["E3"]!!, st["E3"]))
            // 再答对一次 → 不算了（下次重做就不在里面）；错题本不自动移出
            s.recordExerciseAttempt("E1", "B", true, 1000, at = "2099-01-01T00:00:00.000Z")
            st = s.exerciseAttemptStats()
            assertFalse(by["E1"]!!.lastResultWrong(st["E1"]))
            assertEquals(2, st["E1"]!!.attempts)
            assertTrue(s.exercise("E1")!!.wrong)
        }
    }

    @Test
    fun 题目笔记() {
        val ws = freshWs("note")
        seed(ws, listOf("E1"))
        LibraryStore.open(ws).use { s ->
            assertEquals("", s.exercise("E1")!!.note)
            s.setExerciseNote("E1", "注意符号 \$x^2\$", at = "2026-11-10T00:00:00.000Z")
            val e = s.exercise("E1")!!
            assertEquals("注意符号 \$x^2\$", e.note)
            assertEquals("改了 updated_at（同步按它取新）", "2026-11-10T00:00:00.000Z", e.updatedAt)
            s.setExerciseNote("E1", "")
            assertEquals("", s.exercise("E1")!!.note)
        }
    }

    @Test
    fun 删题先归档再删_能恢复() {
        val ws = freshWs("delete")
        val docId = seed(ws, listOf("E1", "E2", "E3"))
        LibraryStore.open(ws).use { s ->
            s.recordExerciseAttempt("E1", "A", false, 1000)
            s.setExerciseNote("E1", "笔记")
            val e = Trash.deleteExercises(s, ws, listOf("E1", "E2"), "2 道习题", "习题册")
            assertEquals(listOf("E3"), s.exercises(docId).map { it.id })
            assertNull("作答随外键一起删了", s.exerciseAttemptStats()["E1"])
            assertEquals(2, e.manifest.counts.exercise)
            val m = Trash.scan(ws).single().manifest
            assertEquals(Trash.KIND_EXERCISES, m.kind)
            assertEquals("2 道习题", m.title)
            assertEquals("习题册", m.documentTitle)
            // 恢复：题、笔记、作答都回来
            Trash.restore(s, Trash.scan(ws).single())
            assertEquals(listOf("E1", "E2", "E3"), s.exercises(docId).map { it.id })
            assertEquals("笔记", s.exercise("E1")!!.note)
            assertNotNull(s.exerciseAttemptStats()["E1"])
            assertTrue(Trash.scan(ws).isEmpty())
        }
    }

    @Test
    fun 归档到的题数对不上就不删() {
        val ws = freshWs("mismatch")
        val docId = seed(ws, listOf("E1"))
        LibraryStore.open(ws).use { s ->
            val r = runCatching { Trash.deleteExercises(s, ws, listOf("E1", "不存在"), "2 道习题", "习题册") }
            assertTrue(r.isFailure)
            assertEquals("一道都没删", listOf("E1"), s.exercises(docId).map { it.id })
            assertTrue("半截目录清掉了", Trash.scan(ws).isEmpty())
        }
    }
}
