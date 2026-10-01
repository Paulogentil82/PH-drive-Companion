package com.phdrive.companion.data.remote

import android.util.Log
import com.phdrive.companion.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Provedor centralizado da configuração HTTP e cliente Supabase.
 * Garante uma única fonte consistente para baseUrl, publishableKey e OkHttpClient
 * em todas as chamadas remotas (veículos, viagens, etc).
 */
object SupabaseClientProvider {

    @Volatile
    var customPublishableKey: String? = null

    @Volatile
    var customBaseUrl: String? = null

    @Volatile
    var customClient: OkHttpClient? = null

    val baseUrl: String
        get() = (customBaseUrl ?: BuildConfig.SUPABASE_URL).trim().trimEnd('/')

    val publishableKey: String
        get() = (customPublishableKey ?: BuildConfig.SUPABASE_PUBLISHABLE_KEY).trim()

    val isUrlMatch: Boolean
        get() = baseUrl == "https://rzuuxbrkzuphcnwctutg.supabase.co"

    val isKeyPrefixOk: Boolean
        get() = publishableKey.startsWith("sb_publishable_")

    val keyPrefix: String
        get() = if (publishableKey.startsWith("sb_publishable_")) {
            "sb_publishable_"
        } else if (publishableKey.length >= 15) {
            publishableKey.substring(0, 15)
        } else {
            "invalid"
        }

    val keyLength: Int
        get() = publishableKey.length

    val client: OkHttpClient
        get() = customClient ?: defaultClient

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }
    }

    /**
     * Valida e registra o estado seguro da configuração antes de requisições.
     * NUNCA imprime o token de acesso nem a chave completa.
     * Registra apenas:
     * - AUTH_SESSION_PRESENT=true/false
     * - API_KEY_PRESENT=true/false
     * - API_KEY_PREFIX=sb_publishable_
     * - SUPABASE_CONFIG_URL_MATCH=true/false
     * - SUPABASE_CONFIG_KEY_PREFIX_OK=true/false
     */
    fun validateAndLogConfig(tag: String, token: String?) {
        val authPresent = !token.isNullOrBlank()
        val keyPresent = publishableKey.isNotBlank()
        if (BuildConfig.DEBUG) {
            Log.d(
                tag,
                "AUTH_SESSION_PRESENT=$authPresent, API_KEY_PRESENT=$keyPresent, API_KEY_PREFIX=$keyPrefix, API_KEY_LENGTH=$keyLength, SUPABASE_CONFIG_URL_MATCH=$isUrlMatch, SUPABASE_CONFIG_KEY_PREFIX_OK=$isKeyPrefixOk"
            )
        }
    }

    /**
     * Constrói cabeçalhos padronizados para chamadas Supabase REST.
     *
     * Regras obrigatórias:
     * - apikey: recebe exclusivamente a SUPABASE_PUBLISHABLE_KEY.
     * - Authorization: recebe exclusivamente "Bearer $accessToken" se presente.
     * - Publishable key NUNCA é usada como Bearer token.
     * - Access token NUNCA é usado como apikey.
     * - Accept: application/json
     */
    fun buildHeaders(accessToken: String?): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["apikey"] = publishableKey
        if (!accessToken.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $accessToken"
        }
        headers["Accept"] = "application/json"
        headers["Content-Type"] = "application/json"
        return headers
    }
}
