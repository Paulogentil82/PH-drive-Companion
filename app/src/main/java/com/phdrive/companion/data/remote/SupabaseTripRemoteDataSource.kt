package com.phdrive.companion.data.remote

import android.util.Log
import com.phdrive.companion.data.remote.model.TripInsertDto
import com.phdrive.companion.data.remote.model.TripPointInsertDto
import com.phdrive.companion.data.remote.model.TripResponseDto
import com.phdrive.companion.data.remote.model.TripUpdateDto
import com.phdrive.companion.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class SupabaseTripRemoteDataSource(
    private val authRepository: AuthRepository = AuthRepository.getInstance(),
    private val clientProvider: SupabaseClientProvider = SupabaseClientProvider
) : TripRemoteDataSource {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private suspend fun executeWithAuth(buildRequest: (token: String) -> Request): Pair<Int, String> {
        var token = authRepository.getOrRefreshAccessToken()
        if (token.isNullOrBlank()) {
            throw SessionExpiredException("Sessão expirada. Faça login novamente.")
        }
        clientProvider.validateAndLogConfig(TAG, token)

        var response = clientProvider.client.newCall(buildRequest(token)).execute()
        var responseBody = response.body?.string() ?: ""

        if (response.code == 401 || response.code == 403) {
            Log.d(TAG, "HTTP ${response.code} na chamada de viagens. Tentando refresh único...")
            val refreshResult = authRepository.refreshToken()
            if (refreshResult.isSuccess) {
                val newToken = refreshResult.getOrNull()
                if (!newToken.isNullOrBlank()) {
                    response = clientProvider.client.newCall(buildRequest(newToken)).execute()
                    responseBody = response.body?.string() ?: ""
                }
            }
            if (response.code == 401 || response.code == 403) {
                authRepository.clearAuth()
                throw SessionExpiredException("Sessão expirada. Faça login novamente.")
            }
        }
        return Pair(response.code, responseBody)
    }

    override suspend fun createTrip(dto: TripInsertDto): Result<TripResponseDto> = withContext(Dispatchers.IO) {
        try {
            val url = "${clientProvider.baseUrl}/rest/v1/trips"
            val bodyJson = clientProvider.json.encodeToString(dto)

            val (code, responseBody) = executeWithAuth { token ->
                val builder = Request.Builder()
                    .url(url)
                    .post(bodyJson.toRequestBody(jsonMediaType))
                    .header("Prefer", "return=representation")
                clientProvider.buildHeaders(token).forEach { (k, v) -> builder.header(k, v) }
                builder.build()
            }

            if (code in 200..299) {
                val list = clientProvider.json.decodeFromString<List<TripResponseDto>>(responseBody)
                val created = list.firstOrNull()
                    ?: return@withContext Result.failure(Exception("Resposta vazia ao criar viagem"))
                Result.success(created)
            } else {
                Result.failure(IOException("Erro HTTP $code: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar viagem remota: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun findActiveTrip(userId: String, vehicleId: String): Result<TripResponseDto?> = withContext(Dispatchers.IO) {
        try {
            val url = "${clientProvider.baseUrl}/rest/v1/trips?user_id=eq.$userId&vehicle_id=eq.$vehicleId&status=eq.IN_PROGRESS&select=*&limit=1"

            val (code, responseBody) = executeWithAuth { token ->
                val builder = Request.Builder()
                    .url(url)
                    .get()
                clientProvider.buildHeaders(token).forEach { (k, v) -> builder.header(k, v) }
                builder.build()
            }

            if (code in 200..299) {
                val list = clientProvider.json.decodeFromString<List<TripResponseDto>>(responseBody)
                Result.success(list.firstOrNull())
            } else {
                Result.failure(IOException("Erro HTTP $code: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao consultar viagem ativa remota: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun updateTrip(remoteTripId: String, dto: TripUpdateDto): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "${clientProvider.baseUrl}/rest/v1/trips?id=eq.$remoteTripId"
            val bodyJson = clientProvider.json.encodeToString(dto)

            val (code, responseBody) = executeWithAuth { token ->
                val builder = Request.Builder()
                    .url(url)
                    .patch(bodyJson.toRequestBody(jsonMediaType))
                clientProvider.buildHeaders(token).forEach { (k, v) -> builder.header(k, v) }
                builder.build()
            }

            if (code in 200..299) {
                Result.success(Unit)
            } else {
                Result.failure(IOException("Erro HTTP $code: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao atualizar viagem remota: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun fetchTrip(remoteTripId: String): Result<TripResponseDto?> = withContext(Dispatchers.IO) {
        try {
            val url = "${clientProvider.baseUrl}/rest/v1/trips?id=eq.$remoteTripId&select=*"

            val (code, responseBody) = executeWithAuth { token ->
                val builder = Request.Builder()
                    .url(url)
                    .get()
                clientProvider.buildHeaders(token).forEach { (k, v) -> builder.header(k, v) }
                builder.build()
            }

            if (code in 200..299) {
                val list = clientProvider.json.decodeFromString<List<TripResponseDto>>(responseBody)
                Result.success(list.firstOrNull())
            } else {
                Result.failure(IOException("Erro HTTP $code: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao buscar viagem remota: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun insertTripPointsBatch(batch: List<TripPointInsertDto>): Result<Int> = withContext(Dispatchers.IO) {
        if (batch.isEmpty()) return@withContext Result.success(0)
        try {
            val url = "${clientProvider.baseUrl}/rest/v1/trip_points"
            val bodyJson = clientProvider.json.encodeToString(batch)

            val (code, responseBody) = executeWithAuth { token ->
                val builder = Request.Builder()
                    .url(url)
                    .post(bodyJson.toRequestBody(jsonMediaType))
                clientProvider.buildHeaders(token).forEach { (k, v) -> builder.header(k, v) }
                builder.build()
            }

            if (code in 200..299) {
                Result.success(batch.size)
            } else {
                Result.failure(IOException("Erro HTTP $code: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar lote de pontos para o Supabase: ${e.message}", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "SupabaseTripRemote"
    }
}
