package dev.beszel.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("beszel_session", Context.MODE_PRIVATE)
    private val tokenCodec = TokenCodec()

    fun loadSession(): Session? {
        val url = preferences.getString(KEY_URL, null) ?: return null
        val storedToken = preferences.getString(KEY_TOKEN, null) ?: return null
        val token = tokenCodec.decrypt(storedToken) ?: run {
            clearSession()
            return null
        }
        if (!storedToken.startsWith(TokenCodec.PREFIX)) {
            preferences.edit { putString(KEY_TOKEN, tokenCodec.encrypt(token)) }
        }
        return Session(url, token, preferences.getString(KEY_EMAIL, "").orEmpty())
    }

    fun saveSession(session: Session) {
        preferences.edit {
            putString(KEY_URL, session.hubUrl)
            putString(KEY_TOKEN, tokenCodec.encrypt(session.token))
            putString(KEY_EMAIL, session.email)
        }
    }

    fun clearSession() {
        preferences.edit { remove(KEY_TOKEN).remove(KEY_EMAIL).remove(KEY_URL) }
    }

    var themeMode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(preferences.getString(KEY_THEME, ThemeMode.SYSTEM.name).orEmpty())
        }.getOrDefault(ThemeMode.SYSTEM)
        set(value) = preferences.edit { putString(KEY_THEME, value.name) }

    var dynamicColor: Boolean
        get() = preferences.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(value) = preferences.edit { putBoolean(KEY_DYNAMIC_COLOR, value) }

    companion object {
        private const val KEY_URL = "hub_url"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_EMAIL = "account_email"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    }
}

private class TokenCodec {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String? {
        if (!value.startsWith(PREFIX)) return value
        return runCatching {
            val bytes = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            require(bytes.size > IV_LENGTH)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_LENGTH)))
            cipher.doFinal(bytes.copyOfRange(IV_LENGTH, bytes.size)).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun key(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
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

    companion object {
        const val PREFIX = "v1:"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "beszel_auth_token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
    }
}
