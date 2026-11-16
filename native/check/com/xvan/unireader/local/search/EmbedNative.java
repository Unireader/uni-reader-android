package com.xvan.unireader.local.search;

/** 只给 pad-check.sh 用：与 App 里 Kotlin `EmbedNative` 同名同签名的 native 方法（JNI 符号一样）。 */
public final class EmbedNative {
    static native void nativeLoad(byte[] pathUtf8, int threads);
    static native float[] nativeEmbed(byte[] textUtf8);
    static native void nativeUnload();
}
