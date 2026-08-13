package com.xvan.unireader.pad

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [KnownMacs] 名单规则的插桩测试。**为什么不是 JVM 单测**：这几个函数落在 `org.json` 与
 * `android.util.Log` 上，两者在 JVM 单测里都是空壳（一调就抛 not mocked）。
 *
 * 验的是「列表长期用下去会不会烂掉」的那几条：重连不丢名字、换 IP 不留僵尸、超量截断、坏数据不炸。
 */
class KnownMacsTest {

    private fun e(host: String, token: String = "t", name: String = "", at: Long = 0) =
        KnownMacs.Entry(host, token, name, at)

    @Test
    fun 重连同一台只留一条且保留已探到的机器名() {
        val old = listOf(e("192.168.1.5", "old", "MacBook Pro"), e("192.168.1.9", "x", "Mac mini"))
        val next = KnownMacs.remembered(old, "192.168.1.5", "new", now = 100)
        assertEquals(2, next.size)
        assertEquals("192.168.1.5", next[0].host)   // 最近连的排最前
        assertEquals("new", next[0].token)          // token 用这次的
        assertEquals("MacBook Pro", next[0].name)   // 名字沿用，不闪回 IP
        assertEquals(100L, next[0].at)
    }

    @Test
    fun 换了IP之后同名的旧地址被清掉() {
        val old = listOf(e("192.168.1.9", "new"), e("192.168.1.5", "old", "MacBook Pro"))
        val next = KnownMacs.renamed(old, "192.168.1.9", "MacBook Pro")
        assertEquals(1, next.size)
        assertEquals("192.168.1.9", next[0].host)
        assertEquals("MacBook Pro", next[0].name)
    }

    @Test
    fun 探测失败或名字没变时原样返回不写盘() {
        val old = listOf(e("192.168.1.5", "t", "MacBook Pro"))
        assertSame(old, KnownMacs.renamed(old, "192.168.1.5", ""))            // 探不到名字
        assertSame(old, KnownMacs.renamed(old, "192.168.1.5", "MacBook Pro")) // 名字没变
        assertSame(old, KnownMacs.renamed(old, "10.0.0.2", "别的 Mac"))        // 已被用户删掉
    }

    @Test
    fun 超过上限时从最旧的开始丢() {
        var list = emptyList<KnownMacs.Entry>()
        for (i in 1..KnownMacs.MAX + 3) list = KnownMacs.remembered(list, "10.0.0.$i", "t$i", now = i.toLong())
        val back = KnownMacs.parse(KnownMacs.serialize(list))
        assertEquals(KnownMacs.MAX, back.size)
        assertEquals("10.0.0.${KnownMacs.MAX + 3}", back.first().host)         // 最新的还在
        assertEquals("10.0.0.4", back.last().host)                             // 最旧的三条被丢掉
    }

    @Test
    fun 序列化往返不丢字段_中文机器名也不丢() {
        val list = listOf(e("192.168.1.5", "abc123", "xVan 的 MacBook Pro", at = 1234567890123L))
        val back = KnownMacs.parse(KnownMacs.serialize(list))
        assertEquals(1, back.size)
        assertEquals("192.168.1.5", back[0].host)
        assertEquals("abc123", back[0].token)
        assertEquals("xVan 的 MacBook Pro", back[0].name)
        assertEquals(1234567890123L, back[0].at)
    }

    @Test
    fun 坏数据按空处理而不是崩掉() {
        assertEquals(0, KnownMacs.parse(null).size)
        assertEquals(0, KnownMacs.parse("").size)
        assertEquals(0, KnownMacs.parse("{不是数组").size)
        // 缺 host 的条目直接跳过（点了也连不上，留着只会占位）
        assertEquals(0, KnownMacs.parse("""[{"token":"t"}]""").size)
    }

    @Test
    fun 存进prefs再读回来还是同一份() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        KnownMacs.forget(ctx, "10.7.7.7")
        KnownMacs.remember(ctx, "10.7.7.7", "tok")
        KnownMacs.setName(ctx, "10.7.7.7", "测试 Mac")
        val hit = KnownMacs.list(ctx).first { it.host == "10.7.7.7" }
        assertEquals("tok", hit.token)
        assertEquals("测试 Mac", hit.label)
        KnownMacs.forget(ctx, "10.7.7.7")
        assertEquals(0, KnownMacs.list(ctx).count { it.host == "10.7.7.7" })
    }
}
