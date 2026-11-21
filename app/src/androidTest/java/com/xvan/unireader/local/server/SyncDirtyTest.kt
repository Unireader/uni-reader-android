package com.xvan.unireader.local.server

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.local.mirror.MirrorStore
import com.xvan.unireader.local.store.Db
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Pt3
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开着服务器工作区时「只查改过的行」（[SyncDirty] + 带 dirty 的 [SyncClient.localChanges]）的插桩测试。
 * 必须跑在设备上：要的就是安卓 `SQLiteDatabase` 开 WAL 后那个连接池的真实行为（JVM 单测里 `android.database` 是空壳）。
 *
 * 要证明的是：
 * ① [Db.totalChanges] 看得见写入（直接 SELECT 落到池里的只读连接上，永远是 0——2026-11-21 平板开着时一笔都不推就是它）；
 * ② 一串各式各样的写（落笔、改、删、级联删、改主键、meta、OCR、改基线、清基线）之后，只查改过的行得出的改动
 *    与整库扫描**逐字节相同**（编码成推送请求比）；
 * ③ 外层带 `OR ABORT` / `OR FAIL` 的写不会被触发器弄失败；取走 / 放回 / 拆掉都对。
 *
 * 全程在 `cacheDir` 里新建工作区，碰不到用户的工作区。
 * 跑（🔴 必带 leaveApksInstalledAfterRun，否则跑完卸载 App、清掉平板上的 App 数据）：
 * `./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
 *  -Pandroid.testInstrumentationRunnerArguments.class=com.xvan.unireader.local.server.SyncDirtyTest`
 */
class SyncDirtyTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun freshWorkspace(): File {
        val parent = File(ctx.cacheDir, "syncdirty").apply { deleteRecursively(); mkdirs() }
        return Workspace.create(parent, "只查改过的行")
    }

    private fun addDoc(db: Db, id: String) = db.exec(
        "INSERT INTO document(id,title,page_count,added_at,last_opened_at) VALUES(?,?,?,?,?)",
        arrayOf<Any?>(id, "书 $id", 10, "2026-11-21T00:00:00.000Z", "2026-11-21T00:00:00.000Z"),
    )

    private fun addNote(db: Db, id: String, doc: String, page: Int) = db.exec(
        "INSERT INTO note(id,document_id,kind,page,anchor_x,anchor_y,anchor_w,anchor_h,payload,created_at,updated_at) " +
            "VALUES(?,?,0,?,0.1,0.2,0.3,0.4,?,?,?)",
        arrayOf<Any?>(id, doc, page, "{\"text\":\"$id\"}".toByteArray(), "2026-11-21T00:00:00.000Z", "2026-11-21T00:00:00.000Z"),
    )

    private fun encoded(ch: List<SyncWire.RowChange>): ByteArray =
        SyncWire.encode(SyncWire.PushRequest(com.xvan.unireader.local.store.Schema.VERSION, ch))

    @Test
    fun 主连接上读total_changes看得见写入() {
        LibraryStore.open(freshWorkspace()).use { store ->
            val db = store.withMirrorDb { it }
            val before = db.totalChanges()
            val rawBefore = db.query("SELECT total_changes()") { it.getLong(0) }.first()
            addDoc(db, "D1")
            val after = db.totalChanges()
            val rawAfter = db.query("SELECT total_changes()") { it.getLong(0) }.first()
            Log.i("SyncDirtyTest", "total_changes 主连接 $before→$after，直接 SELECT $rawBefore→$rawAfter")
            assertTrue("写了一行，主连接上的 total_changes 该变：$before → $after", after > before)
        }
    }

    @Test
    fun 只查改过的行与整库扫描结果相同() {
        val dir = freshWorkspace()
        LibraryStore.open(dir).use { store ->
            val db = store.withMirrorDb { it }
            for (d in listOf("D1", "D2")) addDoc(db, d)
            for ((i, d) in listOf("D1", "D1", "D1", "D2", "D2").withIndex()) addNote(db, "n$i", d, i)
            db.exec("INSERT OR REPLACE INTO meta(key,value) VALUES('note_types','[]')")
            db.exec(MirrorStore.SYNC_BASE_DDL.trimIndent())
            MirrorStore.rebuildSyncBase(db)
            assertTrue("基线刚建好，整库扫描该没有改动", SyncClient.localChanges(db, dir).isEmpty())

            assertTrue("触发器该挂得上", SyncDirty.install(db))
            assertTrue("挂两次不出错", SyncDirty.install(db))
            assertEquals(0, SyncDirty.peek(db)!!.count)

            // 一串写：走 App 自己的落笔路径 + 各种 SQL
            val t0 = System.currentTimeMillis()
            store.insertStroke("D1", 2, Pen(10, 20, 30, 1f, 8f, 0), listOf(Pt3(0.1f, 0.1f, 0.5f), Pt3(0.2f, 0.3f, 0.5f)))
            db.exec("UPDATE note SET payload=? WHERE id='n0'", arrayOf<Any?>("{\"text\":\"改过\"}".toByteArray()))
            db.exec("UPDATE OR ABORT note SET page=7 WHERE id='n0'")          // 同一行第二次被记：不许冲突失败
            db.exec("UPDATE OR FAIL note SET page=8 WHERE id='n0'")
            db.exec("DELETE FROM note WHERE id='n1'")
            db.exec("UPDATE note SET id='n2b' WHERE id='n2'")                   // 改主键：旧 id 删、新 id 增
            db.exec("DELETE FROM document WHERE id='D2'")                         // 级联删掉 n3、n4
            db.exec("INSERT OR REPLACE INTO meta(key,value) VALUES('note_types','[1]')")
            db.exec("INSERT OR REPLACE INTO meta(key,value) VALUES('srv_pulled_rev','99')")   // 不同步的键
            db.exec(
                "INSERT INTO ocr_page(content_hash,page,provider,payload,created_at) VALUES('h',12,'apple',?,?)",
                arrayOf<Any?>("{}".toByteArray(), "2026-11-21T00:00:00.000Z"),
            )
            val peek = SyncDirty.peek(db)!!
            assertTrue("该记下写入时刻：${peek.lastAt} vs $t0", peek.lastAt >= t0 - 5)

            val dirty = SyncDirty.take(db)!!
            val inc = SyncClient.localChanges(db, dir, dirty)
            val full = SyncClient.localChanges(db, dir)
            Log.i("SyncDirtyTest", "整库 ${full.size} 条：${full.map { "${it.table}/${it.rowId}/${it.op}" }}")
            assertTrue("这串写该有改动", full.size >= 8)
            assertEquals(full.map { "${it.table}/${it.rowId}/${it.op}" }, inc.map { "${it.table}/${it.rowId}/${it.op}" })
            assertTrue("编码成推送请求要逐字节相同", encoded(full).contentEquals(encoded(inc)))
            assertEquals("取走之后记录该清空", 0, SyncDirty.peek(db)!!.count)

            // 放回去、再取：同样的结果
            assertTrue(SyncDirty.putBack(db, dirty))
            val back = SyncDirty.peek(db)!!
            assertEquals(dirty.values.sumOf { it.size }, back.count)
            assertEquals("放回去的不算用户在写", 0L, back.lastAt)
            assertTrue(encoded(full).contentEquals(encoded(SyncClient.localChanges(db, dir, SyncDirty.take(db)!!))))

            // 当作推成功了：基线换成现在的样子（记基线会经 sync_base 的触发器把这些行再记一遍，时刻 0）。
            // 推完之后那次复查：只查这些行该什么都没有，和整库扫描一致
            MirrorStore.rebuildSyncBase(db)       // 只管三方判定表；通道表（OCR）照推送后记基线的样子补上
            SyncClient.setBase(db, SyncClient.Key(SyncRows.OCR, SyncRows.ocrRowId(com.xvan.unireader.local.mirror.MirrorDiff.OcrKey("h", 12, "apple"))), "")
            assertEquals("记基线不算用户在写", 0L, SyncDirty.peek(db)!!.lastAt)
            assertTrue(SyncClient.localChanges(db, dir, SyncDirty.take(db)!!).isEmpty())
            assertTrue(SyncClient.localChanges(db, dir).isEmpty())

            // 改基线、清基线（行没动、基线动了）：整库扫描看得见，只查改过的行也要看得见
            SyncClient.setBase(db, SyncClient.Key("note", "n0"), "不对的指纹")
            SyncClient.clearBase(db, listOf("document"))
            val t1 = System.currentTimeMillis()
            SyncDirty.settle(db, t1)      // 没东西可改，不出错
            val inc2 = SyncClient.localChanges(db, dir, SyncDirty.take(db)!!)
            val full2 = SyncClient.localChanges(db, dir)
            assertTrue("改基线 / 清基线该有改动", full2.size >= 2)
            assertEquals(full2.map { "${it.table}/${it.rowId}/${it.op}" }, inc2.map { "${it.table}/${it.rowId}/${it.op}" })
            assertTrue(encoded(full2).contentEquals(encoded(inc2)))

            // 拉取写的行：settle 之后还在（要复查），但不算用户在写
            val t2 = System.currentTimeMillis()
            addNote(db, "pulled", "D1", 3)
            SyncDirty.settle(db, t2)
            val p = SyncDirty.peek(db)!!
            assertEquals(1, p.count)
            assertEquals(0L, p.lastAt)

            SyncDirty.uninstall(db)
            assertNull("拆掉之后 peek 该是 null", SyncDirty.peek(db))
            addNote(db, "after", "D1", 4)       // 拆掉后照常能写
            assertNotNull(SyncClient.localChanges(db, dir))
        }
    }
}
