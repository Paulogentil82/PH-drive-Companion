package com.phdrive.companion.data.remote

import android.util.Log
import com.phdrive.companion.BuildConfig
import com.phdrive.companion.data.remote.model.VehicleRemoteDto
import com.phdrive.companion.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

class SupabaseVehicleRemoteDataSource(
    private val authRepository: AuthRepository = AuthRepository.getInstance(),
    private val clientProvider: SupabaseClientProvider = SupabaseClientProvider
) : VehicleRemoteDataSource {

    override suspend fun getVehicles(): Result<List<VehicleRemoteDto>> = withContext(Dispatchers.IO) {
        try {
            var token = authRepository.getOrRefreshAccessToken()

            // 6. VALIDAR ACCESS TOKEN
            clientProvider.validateAndLogConfig(TAG, token)

            if (token.isNullOrBlank()) {
                Log.w(TAG, "Sessão autenticada ausente para consulta de veículos")
                return@withContext Result.failure(
                    SessionExpiredException("Sessão expirada. Faça login novamente.")
                )
            }

            val url = "${clientProvider.baseUrl}/rest/v1/vehicles?select=*&order=name.asc"

            // Primeira tentativa
            val requestBuilder = Request.Builder()
                .url(url)
                .get()

            clientProvider.buildHeaders(token).forEach { (k, v) ->
                requestBuilder.header(k, v)
            }

            var response = clientProvider.client.newCall(requestBuilder.build()).execute()
            var responseBody = response.body?.string() ?: ""

            // 8. TRATAMENTO DE ERRO HTTP 401 COM REFRESH ÚNICO
            if (response.code == 401 || response.code == 403) {
                val isInvalidApiKey = responseBody.contains("Invalid API key", ignoreCase = true) ||
                        responseBody.contains("api key", ignoreCase = true)

                if (isInvalidApiKey) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "HTTP ${response.code} (Invalid API Key): $responseBody")
                    }
                    return@withContext Result.failure(SessionExpiredException("Falha de configuração do Supabase."))
                }

                // 1. Tentar refresh UMA vez
                Log.d(TAG, "HTTP 401 recebido. Tentando refresh token único...")
                val refreshResult = authRepository.refreshToken()
                if (refreshResult.isSuccess) {
                    val newToken = refreshResult.getOrNull()
                    if (!newToken.isNullOrBlank()) {
                        token = newToken
                        // 2. Repetir a requisição UMA vez
                        val retryRequestBuilder = Request.Builder()
                            .url(url)
                            .get()

                        clientProvider.buildHeaders(token).forEach { (k, v) ->
                            retryRequestBuilder.header(k, v)
                        }

                        response = clientProvider.client.newCall(retryRequestBuilder.build()).execute()
                        responseBody = response.body?.string() ?: ""
                    }
                }
            }

            if (response.code in 200..299) {
                val vehicles = clientProvider.json.decodeFromString<List<VehicleRemoteDto>>(responseBody)
                Result.success(vehicles)
            } else if (response.code == 401 || response.code == 403) {
                // 3. Se ainda falhar: marcar sessão inválida e direcionar para login
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "HTTP ${response.code} persistente após tentativa de refresh: $responseBody")
                }
                val isInvalidApiKey = responseBody.contains("Invalid API key", ignoreCase = true) ||
                        responseBody.contains("api key", ignoreCase = true)
                val friendlyMessage = if (isInvalidApiKey) {
                    "Falha de configuração do Supabase."
                } else {
                    authRepository.clearAuth()
                    "Sessão expirada. Faça login novamente."
                }
                Result.failure(SessionExpiredException(friendlyMessage))
            } else {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Erro HTTP ${response.code}: $responseBody")
                }
                Result.failure(IOException("Erro ao consultar veículos (${response.code})"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao buscar veículos do Supabase: ${e.message}", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "SupabaseVehicleRemote"
    }
}
