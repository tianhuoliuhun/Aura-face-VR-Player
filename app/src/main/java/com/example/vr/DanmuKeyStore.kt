package com.example.vr

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
 * v2.4.41（弹幕审查 #14）：AI 弹幕 **API Key 的落盘加密**。
 *
 * ## 为什么需要
 * 审查指出：Key 此前以**明文**存在 `vr_player_prefs` 里，而 `AndroidManifest` 的
 * `android:allowBackup="true"` 会让 `adb backup` / 系统云备份把它**原样带出设备**。
 *
 * ## 做法（审查建议的 (a)：Keystore 加密，不引入新依赖）
 * - 用 **Android Keystore** 里的 AES-256/GCM 密钥加密，密钥**不可导出**（`targetSdk 36` 下由系统硬件保护）；
 * - 密文以 `enc1:` 前缀标记后存进 prefs —— 换机 / 恢复备份后**本机 Keystore 里没有对应密钥**，
 *   解不开（返回空串，用户重填即可），从而 Key **不会**随备份泄露；
 * - 历史明文值（无前缀）[decrypt] 时**原样返回**（平滑迁移，用户无感），下次写入时自动转成密文。
 *
 * ## 失败策略：**绝不阻断功能**
 * Keystore 在个别设备/异常场景可能不可用 —— 此时 [encrypt] 退回明文、[decrypt] 退回原样，
 * 只打一行日志。安全增强不应把「AI 弹幕」整个弄坏。
 */
object DanmuKeyStore {

    private const val TAG = "DanmuKeyStore"
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "aura_danmu_api_key"
    /** 密文前缀：用于与历史明文区分（也避免对已加密值重复加密） */
    private const val PREFIX = "enc1:"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** 取（缺失则生成）Keystore 里的 AES 密钥；不可用时返回 null。 */
    private fun secretKey(): SecretKey? = try {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) {
            existing
        } else {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
            gen.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            gen.generateKey()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Keystore 不可用，API Key 将按原样处理", t)
        null
    }

    /**
     * 加密。返回 `enc1:` + base64(iv + 密文)。
     * Keystore 不可用或加密失败 → **原样返回明文**（功能优先，绝不因此失败）。
     */
    fun encrypt(plain: String): String {
        if (plain.isEmpty() || plain.startsWith(PREFIX)) return plain
        val key = secretKey() ?: return plain
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.w(TAG, "加密失败 → 退化为明文", t)
            plain
        }
    }

    /**
     * 解密。**没有 `enc1:` 前缀的值按历史明文原样返回**（平滑迁移）。
     * 解密失败（换机 / 清数据导致密钥丢失）返回空串 —— 用户重填即可。
     */
    fun decrypt(stored: String): String {
        if (stored.isEmpty() || !stored.startsWith(PREFIX)) return stored
        val key = secretKey() ?: return ""
        return try {
            val blob = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (blob.size <= IV_LEN) return ""
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
            String(cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "解密失败（密钥不在本机，如换机恢复备份）→ 视为空", t)
            ""
        }
    }
}
