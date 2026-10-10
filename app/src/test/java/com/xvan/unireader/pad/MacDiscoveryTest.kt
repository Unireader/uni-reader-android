package com.xvan.unireader.pad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 局域网发现的纯函数层（`../PROTOCOL.md §8`）。配对码指纹是**跨端契约**：Mac 侧
 * `Pairing.fingerprint` 算出来广播，这里算本机存的码去比——差一个字节，连过的 Mac 永远显示「码已重置」。
 */
class MacDiscoveryTest {

    @Test
    fun fingerprintVectors() {
        // ↔ Mac spike/pairing-fp-test.swift 同两条
        assertEquals("3eb1bd43", MacDiscovery.fingerprint("0123456789abcdef0123456789abcdef"))
        assertEquals("35230248", MacDiscovery.fingerprint("ffffffffffffffffffffffffffffffff"))
    }

    @Test
    fun `服务名转义还原`() {
        assertEquals("xVan MacBook", MacDiscovery.unescapeName("xVan MacBook"))
        assertEquals("xVan MacBook", MacDiscovery.unescapeName("xVan\\032MacBook"))
        // 「的」= UTF-8 E7 9A 84 = \231\154\132
        assertEquals("xVan 的 Mac", MacDiscovery.unescapeName("xVan\\032\\231\\154\\132\\032Mac"))
        assertEquals("a.b\\c", MacDiscovery.unescapeName("a\\.b\\\\c"))
        // 已经是明文的中文 + 混一个转义
        assertEquals("书房 Mac", MacDiscovery.unescapeName("书房\\032Mac"))
    }

    private fun entry(host: String, token: String) = KnownMacs.Entry(host, token, "", 0)

    private fun found(host: String, tk: String) = MacDiscovery.Found("Mac", "Mac", host, tk)

    @Test
    fun `指纹对上就能连，IP 换过也认得出`() {
        val a = entry("192.168.1.5", "0123456789abcdef0123456789abcdef")
        val m = MacDiscovery.match(found("192.168.1.9", "3eb1bd43"), listOf(entry("10.0.0.2", "x"), a))
        assertEquals(MacDiscovery.State.READY, m.state)
        assertSame(a, m.entry)
    }

    @Test
    fun `同一 IP 但指纹不对 = 码已重置`() {
        val a = entry("192.168.1.5", "0123456789abcdef0123456789abcdef")
        val m = MacDiscovery.match(found("192.168.1.5", "35230248"), listOf(a))
        assertEquals(MacDiscovery.State.STALE, m.state)
        assertSame(a, m.entry)
    }

    @Test
    fun `都对不上 = 没配过`() {
        val m = MacDiscovery.match(found("192.168.1.7", "35230248"), listOf(entry("192.168.1.5", "zz")))
        assertEquals(MacDiscovery.State.NEW, m.state)
        assertNull(m.entry)
    }

    @Test
    fun `广播里没有指纹时同 IP 照常连`() {
        val a = entry("192.168.1.5", "zz")
        assertEquals(MacDiscovery.State.READY, MacDiscovery.match(found("192.168.1.5", ""), listOf(a)).state)
        assertEquals(MacDiscovery.State.NEW, MacDiscovery.match(found("192.168.1.6", ""), listOf(a)).state)
    }
}
