package com.xvan.unireader.local.search;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 在平板上经 app_process 直接调 libunireader_embed.so：EmbedCheck <so> <模型> <查询文件> → 打印加载 / 计算耗时与向量（JSON 数组）。
 * 查询文件按 llama-embedding -f 的口径读（去掉末尾一个换行）。
 */
public final class EmbedCheck {
    public static void main(String[] a) throws Exception {
        System.load(a[0]);
        long t0 = System.nanoTime();
        EmbedNative.nativeLoad(a[1].getBytes(StandardCharsets.UTF_8), 4);
        long t1 = System.nanoTime();
        String q = new String(Files.readAllBytes(Paths.get(a[2])), StandardCharsets.UTF_8);
        if (q.endsWith("\n")) q = q.substring(0, q.length() - 1);
        float[] v = EmbedNative.nativeEmbed(q.getBytes(StandardCharsets.UTF_8));
        long t2 = System.nanoTime();
        float[] v2 = EmbedNative.nativeEmbed(q.getBytes(StandardCharsets.UTF_8));
        long t3 = System.nanoTime();
        EmbedNative.nativeUnload();
        System.err.printf("load %.0f ms, embed %.0f ms, again %.0f ms, dim %d%n", (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6, v.length);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
        System.out.println(sb.append(']'));
        double d = 0; for (int i = 0; i < v.length; i++) d += v[i] * v2[i];
        System.err.printf("self-cos %.6f%n", d);
    }
}
