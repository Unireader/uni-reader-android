package com.xvan.unireader.local.search

/**
 * `libunireader_embed.so`（llama.cpp + JNI 薄封装，源码 `android/native/`，重编 `android/native/build-embed.sh`）。
 * 一个进程只有一份模型；调用方（[QueryEmbedder]）负责串行。文字 / 路径按 UTF-8 字节传（JNI 的 modified UTF-8 处理不了 emoji）。
 */
object EmbedNative {
    @Volatile private var libLoaded = false

    private fun lib() {
        if (libLoaded) return
        synchronized(this) {
            if (!libLoaded) { System.loadLibrary("unireader_embed"); libLoaded = true }
        }
    }

    /** 载入模型（已载入的先卸掉）。失败抛 IllegalStateException。 */
    fun load(path: String, threads: Int) { lib(); nativeLoad(path.toByteArray(Charsets.UTF_8), threads) }

    /** 一段文字 → L2 归一化的向量（1024 维）。没载入 / 出错抛 IllegalStateException。 */
    fun embed(text: String): FloatArray { lib(); return nativeEmbed(text.toByteArray(Charsets.UTF_8)) }

    fun unload() { if (libLoaded) nativeUnload() }

    @JvmStatic private external fun nativeLoad(pathUtf8: ByteArray, threads: Int)
    @JvmStatic private external fun nativeEmbed(textUtf8: ByteArray): FloatArray
    @JvmStatic private external fun nativeUnload()
}
