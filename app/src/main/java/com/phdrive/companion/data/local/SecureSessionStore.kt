package com.phdrive.companion.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureSessionStore(context: Context) {

    private val prefs: SharedPreferences = createEncryptedPrefsOrFallback(context)

    init {
        // Migração suave de SharedPreferences legados (se existirem)
        migrateLegacyPrefsIfNeeded(context)
    }

    private fun migrateLegacyPrefsIfNeeded(context: Context) {
        try {
            val legacy = context.getSharedPreferences("phdrive_auth", Context.MODE_PRIVATE)
            val legacyToken = legacy.getString("access_token", null)
            val legacyUserId = legacy.getString("user_id", null)
            val legacyEmail = legacy.getString("user_email", null)

            if (!legacyToken.isNullOrBlank() && prefs.getString(KEY_ACCESS_TOKEN, null).isNullOrBlank()) {
                prefs.edit().apply {
                    putString(KEY_ACCESS_TOKEN, legacyToken)
                    putString(KEY_USER_ID, legacyUserId)
                    putString(KEY_USER_EMAIL, legacyEmail)
                    putLong(KEY_EXPIRES_AT, 0L)
                    apply()
                }
                Log.d(TAG, "Sessão legada migrada com sucesso.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao verificar/migrar prefs legadas: ${e.message}")
        }
    }

    fun saveSession(
        userId: String,
        email: String?,
        accessToken: String,
        refreshToken: String?,
        expiresAtSeconds: Long
    ) {
        prefs.edit().apply {
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_EMAIL, email)
            putString(KEY_ACCESS_TOKEN, accessToken)
            if (!refreshToken.isNullOrBlank()) {
                putString(KEY_REFRESH_TOKEN, refreshToken)
            }
            putLong(KEY_EXPIRES_AT, expiresAtSeconds)
            apply()
        }
    }

    fun updateTokens(
        accessToken: String,
        refreshToken: String?,
        expiresAtSeconds: Long
    ) {
        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            if (!refreshToken.isNullOrBlank()) {
                putString(KEY_REFRESH_TOKEN, refreshToken)
            }
            putLong(KEY_EXPIRES_AT, expiresAtSeconds)
            apply()
        }
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    fun getUserEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)

    fun getExpiresAt(): Long = prefs.getLong(KEY_EXPIRES_AT, 0L)

    fun isAccessTokenValid(): Boolean {
        val token = getAccessToken()
        if (token.isNullOrBlank()) return false
        val expiresAt = getExpiresAt()
        if (expiresAt <= 0L) {
            // Se expiresAt for 0, mas existe token e não temos refreshToken, consideramos válido até 401
            return true
        }
        val currentSeconds = System.currentTimeMillis() / 1000
        // Considera válido se faltar mais de 30 segundos para expirar
        return currentSeconds < (expiresAt - 30)
    }

    fun hasRefreshToken(): Boolean = !getRefreshToken().isNullOrBlank()

    fun hasAnySession(): Boolean {
        return !getAccessToken().isNullOrBlank() || !getRefreshToken().isNullOrBlank()
    }

    fun clearSession() {
        prefs.edit().apply {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_EXPIRES_AT)
            remove(KEY_USER_ID)
            remove(KEY_USER_EMAIL)
            apply()
        }
    }

    companion object {
        private const val TAG = "SecureSessionStore"
        private const val PREFS_FILE = "phdrive_secure_auth"

        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"

        private fun createEncryptedPrefsOrFallback(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Throwable) {
                Log.w(TAG, "EncryptedSharedPreferences indisponível, usando fallback seguro: ${e.message}")
                context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            }
        }
    }
}
