package com.xvan.unireader.local.store

import com.xvan.unireader.shared.Pt3
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 笔迹点集的二进制形态（schema v18，`../BINARY-INK-PLAN.md §2`）：`note.points` / `board_item.points` 列。
 *
 * 格式：`u8 版本 = 1` + n ×（`f32 x` `f32 y` `f32 z`），**小端**；n = (长度 − 1) / 12。
 * 坐标系与 payload 里的 JSON `points` 完全相同，只换编码。
 * **两端契约**：Mac `Sources/Store/InkPointsBlob.swift` 同一份，跨端向量 `../spike/ink-blob-vectors.txt`
 * （本端 `InkPointsBlobTest` 与 Mac `spike/ink-blob-test.swift` 各读一遍，逐字节比）。
 */
object InkPointsBlob {
    const val VERSION: Byte = 1

    fun encode(pts: List<Pt3>): ByteArray {
        val b = ByteBuffer.allocate(1 + pts.size * 12).order(ByteOrder.LITTLE_ENDIAN)
        b.put(VERSION)
        for (p in pts) { b.putFloat(p.x); b.putFloat(p.y); b.putFloat(p.p) }
        return b.array()
    }

    /** 版本不认识 / 长度对不上 / 空 → null（调用方按「没有二进制」处理，退回 JSON） */
    fun decode(d: ByteArray?): List<Pt3>? {
        if (d == null || d.isEmpty() || (d.size - 1) % 12 != 0 || d[0] != VERSION) return null
        val n = (d.size - 1) / 12
        val b = ByteBuffer.wrap(d, 1, d.size - 1).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<Pt3>(n)
        repeat(n) { out.add(Pt3(b.float, b.float, b.float)) }
        return out
    }
}
