package com.cursorchat.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API key sealed with an Android Keystore AES-GCM key. The UI only keeps "已连接". */
class ApiKeyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasKey(): Boolean = preferences.contains(PREF_KEY)

    fun load(): String? {
        val encoded = preferences.getString(PREF_KEY, null) ?: return null
        return try {
            val parts = encoded.split('.', limit = 2)
            check(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            preferences.edit().remove(PREF_KEY).apply()
            null
        }
    }

    fun save(raw: String) {
        val apiKey = ApiKeyRules.require(raw)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encoded = listOf(
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP),
        ).joinToString(".")
        preferences.edit().putString(PREF_KEY, encoded).apply()
    }

    fun clear() {
        preferences.edit().remove(PREF_KEY).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFS = "cursor_chat_secure"
        const val PREF_KEY = "api_key"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "cursor_chat_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
