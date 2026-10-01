package com.phdrive.companion.data.remote

import android.util.Log
import com.phdrive.companion.BuildConfig
import com.phdrive.companion.data.remote.model.AuthErrorResponseDto
import com.phdrive.companion.data.remote.model.AuthTokenResponseDto
import com.phdrive.companion.data.remote.model.PasswordLoginRequest
import com.phdrive.companion.data.remote.model.RefreshTokenRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

interface AuthRemoteDataSource {
    suspend fun login(email: String, password: String): Result<AuthTokenResponseDto>
    suspend fun refreshToken(refreshToken: String): Result<AuthTokenResponseDto>
    suspend fun logout(accessToken: String?): Result<Unit>
}

class SupabaseAuthRemoteDataSource(
    private val clientProvider: SupabaseClientProvider = SupabaseClientProvider
) : AuthRemoteDataSource {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun login(email: String, password: String): Result<AuthTokenResponseDto> = withContext(Dispatchers.IO) {
        // 7. DIAGNÓSTICO DEBUG: Logs permitidos (NUNCA logar senha, token ou API key completa)
        clientProvider.validateAndLogConfig(TAG, null)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "AUTH_LOGIN_REQUEST_STARTED")
            Log.d(TAG, "AUTH_LOGIN_EMAIL_PRESENT=${email.isNotBlank()}")
            Log.d(TAG, "AUTH_LOGIN_PASSWORD_LENGTH=${password.length}")
        }

        try {
            // 2. ENDPOINT CORRETO: POST https://<url>/auth/v1/token?grant_type=password
            val url = "${clientProvider.baseUrl}/auth/v1/token?grant_type=password"

            // 3 & 4. BODY CORRETO: JSON com email e password exato digitado (sem trim na senha, sem hash, sem grant_type no body)
            val requestDto = PasswordLoginRequest(email = email, password = password)
            val bodyJson = clientProvider.json.encodeToString(requestDto)

            // Headers: apikey, Content-Type, Accept (SEM Authorization Bearer)
            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(jsonMediaType))
                .header("apikey", clientProvider.publishableKey)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build()

            val response = clientProvider.client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "AUTH_LOGIN_HTTP_STATUS=${response.code}")
            }

            if (response.isSuccessful) {
                try {
                    val tokenResponse = clientProvider.json.decodeFromString<AuthTokenResponseDto>(responseBody)
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "AUTH_LOGIN_RESPONSE_HAS_ACCESS_TOKEN=${tokenResponse.accessToken.isNotBlank()}")
                    }
                    Result.success(tokenResponse)
                } catch (e: SerializationException) {
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, "Erro de serialização na resposta do login: ${e.message}")
                        Log.d(TAG, "AUTH_LOGIN_RESPONSE_HAS_ACCESS_TOKEN=false")
                        Log.d(TAG, "AUTH_LOGIN_ERROR_CODE=SERIALIZATION_ERROR")
                    }
                    Result.failure(AuthException("Erro ao processar resposta do servidor.", response.code))
                }
            } else {
                val errorDto = tryParseErrorDto(responseBody)
                val errorCode = errorDto?.errorCode ?: errorDto?.error ?: errorDto?.code ?: "UNKNOWN"

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "AUTH_LOGIN_RESPONSE_HAS_ACCESS_TOKEN=false")
                    Log.d(TAG, "AUTH_LOGIN_ERROR_CODE=$errorCode")
                }

                // 8. ERRO REAL: Diferenciar 401 Invalid API Key de credenciais inválidas
                val isInvalidApiKey = response.code == 401 && (
                    responseBody.contains("Invalid API key", ignoreCase = true) ||
                    responseBody.contains("api key", ignoreCase = true) ||
                    errorCode.contains("invalid_api_key", ignoreCase = true)
                )

                val friendlyMessage = when {
                    isInvalidApiKey -> {
                        "Falha de configuração do Supabase."
                    }
                    response.code == 400 || response.code == 401 || response.code == 422 -> {
                        "E-mail ou senha inválidos."
                    }
                    response.code == 429 -> {
                        "Muitas tentativas. Aguarde um momento e tente novamente."
                    }
                    else -> {
                        "Erro na autenticação (${response.code})."
                    }
                }

                Result.failure(AuthException(friendlyMessage, response.code, errorCode))
            }
        } catch (e: IOException) {
            // 8. Erro de rede -> "Não foi possível conectar ao servidor."
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "AUTH_LOGIN_ERROR_CODE=NETWORK_ERROR")
            }
            Result.failure(AuthException("Não foi possível conectar ao servidor.", null, "NETWORK_ERROR"))
        } catch (e: AuthException) {
            Result.failure(e)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "AUTH_LOGIN_ERROR_CODE=UNEXPECTED_ERROR: ${e.message}")
            }
            Result.failure(AuthException(e.message ?: "Erro inesperado", null, "UNEXPECTED_ERROR"))
        }
    }

    override suspend fun refreshToken(refreshToken: String): Result<AuthTokenResponseDto> = withContext(Dispatchers.IO) {
        try {
            val url = "${clientProvider.baseUrl}/auth/v1/token?grant_type=refresh_token"
            val requestDto = RefreshTokenRequestDto(refreshToken = refreshToken)
            val bodyJson = clientProvider.json.encodeToString(requestDto)

            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(jsonMediaType))
                .header("apikey", clientProvider.publishableKey)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build()

            val response = clientProvider.client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val tokenResponse = clientProvider.json.decodeFromString<AuthTokenResponseDto>(responseBody)
                Result.success(tokenResponse)
            } else {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Refresh token falhou HTTP ${response.code}: $responseBody")
                }
                Result.failure(SessionExpiredException("Sessão expirada. Faça login novamente."))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Erro de rede no refresh token: ${e.message}")
            Result.failure(IOException("Não foi possível conectar ao servidor.", e))
        } catch (e: Exception) {
            Log.e(TAG, "Erro inesperado no refresh token: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun logout(accessToken: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "${clientProvider.baseUrl}/auth/v1/logout"
            val requestBuilder = Request.Builder()
                .url(url)
                .post("{}".toRequestBody(jsonMediaType))
                .header("apikey", clientProvider.publishableKey)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")

            if (!accessToken.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $accessToken")
            }

            val response = clientProvider.client.newCall(requestBuilder.build()).execute()
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Logout response: ${response.code}")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Falha na chamada remota de logout (limpeza local deve prosseguir): ${e.message}")
            Result.success(Unit)
        }
    }

    private fun tryParseErrorDto(responseBody: String): AuthErrorResponseDto? {
        return try {
            clientProvider.json.decodeFromString<AuthErrorResponseDto>(responseBody)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "SupabaseAuthRemote"
    }
}

class AuthException(
    message: String,
    val statusCode: Int? = null,
    val errorCode: String? = null
) : Exception(message)
