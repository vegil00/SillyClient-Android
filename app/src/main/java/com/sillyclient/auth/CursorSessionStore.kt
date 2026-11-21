package com.sillyclient.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretVault {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

class CursorSessionStore(private val vault: SecretVault) {
    fun load(): CursorAccount? {
        val raw = try {
            vault.read()
        } catch (_: Exception) {
            vault.clear()
            return null
        } ?: return null
        val account = CursorAccount.decode(raw)
        if (account == null) {
            vault.clear()
            return null
        }
        return account
    }

    fun save(account: CursorAccount) {
        vault.write(account.encode())
    }

    fun clear() {
        vault.clear()
    }

    companion object {
        fun from(context: Context): CursorSessionStore = CursorSessionStore(AndroidKeystoreVault(context))
    }
}

private class AndroidKeystoreVault(context: Context) : SecretVault {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(): String? {
        val encoded = preferences.getString(KEY, null) ?: return null
        return try {
            val parts = encoded.split('.', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.getDecoder().decode(parts[0]))
            )
            cipher.doFinal(Base64.getDecoder().decode(parts[1])).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            preferences.edit().remove(KEY).apply()
            null
        }
    }

    override fun write(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encoded = listOf(
            Base64.getEncoder().encodeToString(cipher.iv),
            Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
        ).joinToString(".")
        preferences.edit().putString(KEY, encoded).apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = java.security.KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFERENCES = "cursor_account"
        private const val KEY = "session"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "sillyclient.cursor_account"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
