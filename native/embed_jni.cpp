// UniReader 安卓语义搜索：查询向量的 JNI 薄封装（`../../docs/SEMANTIC-SYNC-PLAN.md §4.3`）。
//
// 只做一件事：载入 Qwen3-Embedding-0.6B（GGUF Q8_0），把一段文字（UTF-8 字节）算成 L2 归一化的向量。
// 参数照 Mac / 平板实测（`../../docs/SEARCH-PLAN.md §5.5`）：n_ctx = 512、一段（n_seq_max = 1）、pooling = last、
// 开着权重重排（use_extra_bufts 默认 true）。末尾的 <|endoftext|> 由 llama.cpp 按 GGUF 元数据自动补（add_special）。
//
// Kotlin 侧 `com.xvan.unireader.local.search.EmbedNative`；全部调用在 Kotlin 那边串行，这里再加一把锁兜底。
// 编译：`native/build-embed.sh`（产物 `app/src/main/jniLibs/arm64-v8a/libunireader_embed.so`，提交进仓库）。

#include <jni.h>
#include <android/log.h>

#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "UniReader/Embed"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

constexpr int kCtx = 512;

std::mutex g_lock;
llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;
bool g_backend = false;

void log_cb(ggml_log_level level, const char *text, void *) {
    // 只留警告与错误（加载时的 info 一屏几百行）
    if (level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
    else if (level == GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

void free_all() {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

void throw_java(JNIEnv *env, const std::string &msg) {
    jclass c = env->FindClass("java/lang/IllegalStateException");
    if (c) env->ThrowNew(c, msg.c_str());
}

std::string bytes(JNIEnv *env, jbyteArray a) {
    jsize n = env->GetArrayLength(a);
    std::string s(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_xvan_unireader_local_search_EmbedNative_nativeLoad(JNIEnv *env, jclass, jbyteArray pathUtf8, jint threads) {
    std::lock_guard<std::mutex> g(g_lock);
    if (!g_backend) {
        llama_log_set(log_cb, nullptr);
        llama_backend_init();
        g_backend = true;
    }
    free_all();
    const std::string path = bytes(env, pathUtf8);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;            // 只用 CPU
    // mp.use_extra_bufts 默认 true = 权重重排（查询快约 70%，多占约 0.4 GB）
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) { throw_java(env, "failed to load model"); return; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = kCtx;
    cp.n_batch = kCtx;
    cp.n_ubatch = kCtx;
    cp.n_seq_max = 1;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.embeddings = true;
    cp.pooling_type = LLAMA_POOLING_TYPE_LAST;
    cp.no_perf = true;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { free_all(); throw_java(env, "failed to create context"); return; }
    if (llama_pooling_type(g_ctx) != LLAMA_POOLING_TYPE_LAST) {
        free_all(); throw_java(env, "pooling is not last"); return;
    }
    LOGI("loaded, n_embd = %d, threads = %d", llama_model_n_embd_out(g_model), threads);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_xvan_unireader_local_search_EmbedNative_nativeEmbed(JNIEnv *env, jclass, jbyteArray textUtf8) {
    std::lock_guard<std::mutex> g(g_lock);
    if (!g_ctx) { throw_java(env, "model not loaded"); return nullptr; }
    const std::string text = bytes(env, textUtf8);
    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    // 分词：add_special = true（按 GGUF 元数据补 BOS / EOS，Qwen3 是末尾的 <|endoftext|>），parse_special = true（同 llama-embedding）
    std::vector<llama_token> toks(text.size() + 8);
    int n = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), toks.data(),
                           static_cast<int32_t>(toks.size()), true, true);
    if (n < 0) {
        toks.resize(static_cast<size_t>(-n));
        n = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), toks.data(),
                           static_cast<int32_t>(toks.size()), true, true);
    }
    if (n <= 0) { throw_java(env, "tokenize failed"); return nullptr; }
    if (n > kCtx) {
        // 超长只留前 512 个 token，最后一位换回原来的结尾记号（pooling = last 取的就是它）
        toks[kCtx - 1] = toks[static_cast<size_t>(n - 1)];
        n = kCtx;
    }

    llama_memory_clear(llama_get_memory(g_ctx), true);
    llama_batch batch = llama_batch_init(n, 0, 1);
    for (int i = 0; i < n; i++) {
        batch.token[i] = toks[static_cast<size_t>(i)];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = true;   // 同 llama-embedding：每个位置都出，pooling = last 取最后一个
    }
    batch.n_tokens = n;
    const int rc = llama_decode(g_ctx, batch);
    llama_batch_free(batch);
    if (rc != 0) { throw_java(env, "decode failed: " + std::to_string(rc)); return nullptr; }

    const float *e = llama_get_embeddings_seq(g_ctx, 0);
    if (!e) { throw_java(env, "no embedding"); return nullptr; }
    const int dim = llama_model_n_embd_out(g_model);
    std::vector<float> out(e, e + dim);
    double sum = 0;
    for (float v : out) sum += static_cast<double>(v) * v;
    const float norm = sum > 0 ? static_cast<float>(1.0 / std::sqrt(sum)) : 0.0f;
    for (float &v : out) v *= norm;

    jfloatArray arr = env->NewFloatArray(dim);
    if (arr) env->SetFloatArrayRegion(arr, 0, dim, out.data());
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xvan_unireader_local_search_EmbedNative_nativeUnload(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> g(g_lock);
    free_all();
}
