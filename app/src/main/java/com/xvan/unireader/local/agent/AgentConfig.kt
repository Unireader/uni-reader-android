package com.xvan.unireader.local.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 安卓 Agent 的接口配置（`../docs/ANDROID-AGENT-PLAN.md §3`）：OpenAI 兼容地址 + API Key + 模型名。
 *
 * **设备级、不进工作区、不随同步**（用户 2026-11-15 定）：每台设备各配各的。
 * API Key 用 Android Keystore 里的 AES-GCM 密钥加密后再存进 SharedPreferences——密钥本身导不出来，
 * 拷走 prefs 文件也解不开。解不开（换机恢复备份、密钥被系统清掉）一律当「没配过 Key」，让用户重填。
 */
data class AgentSettings(
    /** 形如 `https://api.deepseek.com/v1`：与 OpenAI 官方 SDK 的 base_url 同一写法，后面再拼 [chatPath]。 */
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** 一般不用改；个别服务的路径不是 `chat/completions` 时才填。 */
    val chatPath: String = DEFAULT_CHAT_PATH,
) {
    val isComplete: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_CHAT_PATH = "chat/completions"
    }
}

object AgentConfig {
    private const val TAG = "UniReader/Agent"
    private const val PREFS = "agent"
    private const val K_BASE = "baseUrl"
    private const val K_MODEL = "model"
    private const val K_PATH = "chatPath"
    private const val K_KEY = "apiKeyEnc"
    private const val KEY_ALIAS = "unireader.agent.apikey"

    fun load(ctx: Context): AgentSettings {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AgentSettings(
            baseUrl = p.getString(K_BASE, "").orEmpty(),
            apiKey = p.getString(K_KEY, null)?.let(::decrypt).orEmpty(),
            model = p.getString(K_MODEL, "").orEmpty(),
            chatPath = p.getString(K_PATH, null)?.takeIf { it.isNotBlank() } ?: AgentSettings.DEFAULT_CHAT_PATH,
        )
    }

    fun save(ctx: Context, s: AgentSettings) {
        val enc = if (s.apiKey.isBlank()) null else encrypt(s.apiKey)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_BASE, s.baseUrl.trim())
            .putString(K_MODEL, s.model.trim())
            .putString(K_PATH, s.chatPath.trim())
            .apply { if (enc == null) remove(K_KEY) else putString(K_KEY, enc) }
            .apply()
    }

    // ---- Keystore AES-GCM ----

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** 存成 `base64(iv) : base64(密文)`。 */
    private fun encrypt(plain: String): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(out, Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w(TAG, "加密 API Key 失败", e); null
    }

    private fun decrypt(stored: String): String? = try {
        val (iv, body) = stored.split(':', limit = 2).let { it[0] to it[1] }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(body, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w(TAG, "解不开存着的 API Key，按没配过处理", e); null
    }
}
