package com.xvan.unireader.local.store

import androidx.test.platform.app.InstrumentationRegistry
import com.xvan.unireader.local.Workspace
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [StoreQueue] 的插桩测试（同 [LibraryStoreTest]：数据层只能跑在设备上）。
 *
 * 这里要证明的是三件**架构性质**，不是某个功能：
 * ① 库只被一条线程碰（`LibraryStore` 非线程安全，这是它能在后台跑的全部前提）；
 * ② 提交顺序 = 执行顺序（「写完再重读」靠的就是这个，不再靠主线程消息队列排队）；
 * ③ `close()` 排在队尾——退出时刚提交的那次进度一定先落盘，再走关库的 `wal_checkpoint`。
 */
class StoreQueueTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixtureDir(): File? =
        File(LibraryStoreTest.FIXTURE).listFiles()
            ?.firstOrNull { it.isDirectory && Workspace.looksLikeWorkspace(it) }

    private fun copyOfFixture(): File {
        val src = fixtureDir()!!
        val dst = File(ctx.cacheDir, "qtest/${src.name}")
        dst.deleteRecursively()
        src.copyRecursively(dst, overwrite = true)
        return dst
    }

    @Test
    fun 全部作业串行跑在同一条线程上且按提交顺序执行() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        val store = LibraryStore.open(dir)
        val docId = store.allDocuments().first().id
        val threads = Collections.synchronizedSet(HashSet<String>())
        val order = Collections.synchronizedList(ArrayList<Int>())

        val q = StoreQueue(store)
        for (i in 0 until 30) {
            q.submit("写进度 $i") { s ->
                threads.add(Thread.currentThread().name)
                order.add(i)
                s.updateProgress(docId, i, i / 100.0, 1.0, 0.0)
            }
        }
        q.close()
        assertTrue("队列该在 5s 内收尾", q.awaitClosed(5000))

        assertEquals("库只许被一条线程碰，实际 $threads", setOf("unireader-store"), threads)
        assertEquals("执行顺序必须等于提交顺序", (0 until 30).toList(), order)
        // 关库排在队尾 → 最后一次进度一定已经落盘
        LibraryStore.open(dir, readOnly = true).use {
            assertEquals(29, it.document(docId)!!.readPage)
        }
    }

    @Test
    fun 结果回到主线程且关掉之后再提交不崩也不写() {
        assumeTrue("没有 fixture，跳过", fixtureDir() != null)
        val dir = copyOfFixture()
        val store = LibraryStore.open(dir)
        val docId = store.allDocuments().first().id
        val q = StoreQueue(store)

        val latch = CountDownLatch(1)
        var seenOnMain = false
        var count = -1
        q.submit(
            "数一下笔迹",
            { it.noteCount(docId, NoteKind.INK) },
            { n ->
                seenOnMain = android.os.Looper.myLooper() === android.os.Looper.getMainLooper()
                count = n
                latch.countDown()
            },
        )
        assertTrue("2s 内该拿到回调", latch.await(2, TimeUnit.SECONDS))
        assertTrue("回调必须在主线程", seenOnMain)
        assertTrue("笔迹条数该读得出来", count >= 0)

        q.submit("关之前的最后一次进度") { it.updateProgress(docId, 7, 0.5, 1.0, 0.0) }
        q.close()
        assertTrue(q.awaitClosed(5000))
        // 关掉之后再提交：既不能崩（界面销毁后画布的回调可能还在飞），也不能写进去
        q.submit("关掉之后还来写") { it.updateProgress(docId, 999, 0.9, 1.0, 0.0) }
        q.close()   // 重复 close 也得是空操作

        LibraryStore.open(dir, readOnly = true).use {
            assertEquals("关之后提交的那次不许生效", 7, it.document(docId)!!.readPage)
        }
    }
}
