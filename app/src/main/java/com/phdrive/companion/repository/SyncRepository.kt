package com.phdrive.companion.repository

import android.content.Context
import android.util.Log
import com.phdrive.companion.data.local.PhDriveDatabase
import com.phdrive.companion.data.local.SyncPreferences
import com.phdrive.companion.data.remote.SessionExpiredException
import com.phdrive.companion.data.remote.SupabaseTripRemoteDataSource
import com.phdrive.companion.data.remote.TripRemoteDataSource
import com.phdrive.companion.data.remote.model.TripInsertDto
import com.phdrive.companion.data.remote.model.TripPointInsertDto
import com.phdrive.companion.data.remote.model.TripUpdateDto
import com.phdrive.companion.model.LocalTrip
import com.phdrive.companion.model.SyncStatus
import com.phdrive.companion.model.TripStatus
import com.phdrive.companion.util.LocationUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncReport(
    val pointsSynced: Int,
    val tripsSynced: Int,
    val hadErrors: Boolean,
    val sessionExpired: Boolean = false,
    val errorMessage: String? = null
)

sealed interface SyncState {
    object Idle : SyncState
    object Syncing : SyncState
    data class Success(val lastSyncTimestamp: Long, val pointsSynced: Int) : SyncState
    data class Error(val message: String, val timestamp: Long) : SyncState
    data class SessionExpired(val timestamp: Long) : SyncState
}

class SyncRepository(
    private val database: PhDriveDatabase,
    private val remoteDataSource: TripRemoteDataSource = SupabaseTripRemoteDataSource(),
    private val context: Context? = null
) {
    constructor(context: Context) : this(PhDriveDatabase.getInstance(context), SupabaseTripRemoteDataSource(), context)
    constructor(database: PhDriveDatabase, context: Context?) : this(database, SupabaseTripRemoteDataSource(), context)
    constructor(database: PhDriveDatabase) : this(database, SupabaseTripRemoteDataSource(), null)

    private val tripDao = database.tripDao()
    private val tripPointDao = database.tripPointDao()
    private val syncMutex = Mutex()
    private val syncPreferences = context?.let { SyncPreferences.getInstance(it) }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState = _syncState.asStateFlow()

    private val _lastSyncAttemptTime = MutableStateFlow<Long?>(syncPreferences?.getLastSyncAttemptAt())
    val lastSyncAttemptTime = _lastSyncAttemptTime.asStateFlow()

    private val _lastSuccessfulSyncTime = MutableStateFlow<Long?>(syncPreferences?.getLastSuccessfulSyncAt())
    val lastSuccessfulSyncTime = _lastSuccessfulSyncTime.asStateFlow()

    private val _lastSyncErrorCode = MutableStateFlow<String?>(syncPreferences?.getLastSyncErrorCode())
    val lastSyncErrorCode = _lastSyncErrorCode.asStateFlow()

    private fun extractErrorCode(e: Throwable): String {
        val message = e.message ?: ""
        val pgCodeRegex = """code[":\s]+([0-9A-Z]{5})""".toRegex(RegexOption.IGNORE_CASE)
        val matchPg = pgCodeRegex.find(message)
        if (matchPg != null) {
            return matchPg.groupValues[1]
        }

        try {
            val codeField = e.javaClass.getMethod("getErrorCode")
            val code = codeField.invoke(e) as? String
            if (!code.isNullOrBlank()) return code
        } catch (_: Exception) {}

        try {
            val codeField = e.javaClass.getDeclaredField("code")
            codeField.isAccessible = true
            val code = codeField.get(e) as? String
            if (!code.isNullOrBlank()) return code
        } catch (_: Exception) {}

        val httpRegex = """\b(400|401|403|404|409|500)\b""".toRegex()
        val matchHttp = httpRegex.find(message)
        if (matchHttp != null) {
            return "HTTP_" + matchHttp.groupValues[1]
        }

        if (e is kotlinx.serialization.SerializationException) {
            return "SERIALIZATION_ERROR"
        }

        return "UNKNOWN_ERROR"
    }

    private fun sanitizeErrorMessage(message: String): String {
        var msg = message
        try {
            val key = com.phdrive.companion.BuildConfig.SUPABASE_PUBLISHABLE_KEY
            if (key.isNotBlank()) {
                msg = msg.replace(key, "[SUPABASE_KEY_REDACTED]")
            }
            val url = com.phdrive.companion.BuildConfig.SUPABASE_URL
            if (url.isNotBlank()) {
                msg = msg.replace(url, "[SUPABASE_URL_REDACTED]")
            }
        } catch (_: Exception) {}

        val coordinateRegex = """-?\d+\.\d{4,}""".toRegex()
        msg = msg.replace(coordinateRegex, "[COORDINATE_REDACTED]")

        val tokenRegex = """(token|password|passwd|key|secret)[=:\s'"]+([a-zA-Z0-9_\-\.]+)""".toRegex(RegexOption.IGNORE_CASE)
        msg = msg.replace(tokenRegex) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }

        return msg
    }

    suspend fun ensureRemoteTrip(localTrip: LocalTrip): Result<String> {
        if (!localTrip.remoteTripId.isNullOrBlank()) {
            Log.d(TAG_SYNC, "SYNC_REMOTE_TRIP_OK")
            return Result.success(localTrip.remoteTripId)
        }

        val existingTripResult = remoteDataSource.findActiveTrip(
            userId = localTrip.userId,
            vehicleId = localTrip.vehicleId
        )

        if (existingTripResult.isSuccess) {
            val existingRemote = existingTripResult.getOrNull()
            if (existingRemote != null && !existingRemote.id.isBlank()) {
                tripDao.updateRemoteTripId(localTrip.localId, existingRemote.id)
                if (localTrip.status == TripStatus.IN_PROGRESS) {
                    tripDao.markSyncStatus(localTrip.localId, SyncStatus.SYNCED)
                } else {
                    tripDao.markSyncStatus(localTrip.localId, SyncStatus.PENDING)
                }
                Log.d(TAG_SYNC, "SYNC_REMOTE_TRIP_OK")
                return Result.success(existingRemote.id)
            }
        }

        val dto = TripInsertDto(
            userId = localTrip.userId,
            vehicleId = localTrip.vehicleId,
            status = "IN_PROGRESS",
            startedAt = LocationUtils.formatIso8601Utc(localTrip.startedAt),
            startOdometerKm = localTrip.startOdometerKm
        )

        Log.d("PHDriveSync", "REMOTE_TRIP_INSERT_REQUEST userId=${localTrip.userId} vehicleId=${localTrip.vehicleId}")
        val createResult = remoteDataSource.createTrip(dto)
        return if (createResult.isSuccess) {
            val created = createResult.getOrThrow()
            Log.d("PHDriveSync", "REMOTE_TRIP_INSERT_SUCCESS\nremoteTripId=${created.id}")
            tripDao.updateRemoteTripId(localTrip.localId, created.id)
            if (localTrip.status == TripStatus.IN_PROGRESS) {
                tripDao.markSyncStatus(localTrip.localId, SyncStatus.SYNCED)
            } else {
                tripDao.markSyncStatus(localTrip.localId, SyncStatus.PENDING)
            }
            Log.d(TAG_SYNC, "SYNC_REMOTE_TRIP_OK")
            Result.success(created.id)
        } else {
            val ex = createResult.exceptionOrNull()
            val errorCode = extractErrorCode(ex ?: Exception())
            val message = ex?.message ?: ""
            Log.e("PHDriveSync", "REMOTE_TRIP_INSERT_ERROR\nHTTP status=500\nPostgREST code=$errorCode\nmessage=$message\ndetails=\nhint=")
            Log.e(TAG, "Falha ao criar viagem remota no Supabase: $message")
            Result.failure(ex ?: Exception("Falha ao criar viagem remota"))
        }
    }

    suspend fun syncPendingPoints(localTripId: String? = null, batchSize: Int = 20): Result<Int> {
        val trip = if (localTripId != null) {
            tripDao.getTripByLocalId(localTripId)
        } else {
            tripDao.getActiveTrip()
        } ?: return Result.success(0)

        val remoteTripId = if (trip.remoteTripId.isNullOrBlank()) {
            val remoteResult = ensureRemoteTrip(trip)
            if (remoteResult.isFailure) {
                return Result.failure(remoteResult.exceptionOrNull() ?: Exception("Sem remoteTripId"))
            }
            remoteResult.getOrThrow()
        } else {
            trip.remoteTripId
        }

        var totalSynced = 0
        while (true) {
            val batch = tripPointDao.getPendingPointsForTrip(trip.localId, batchSize)
            if (batch.isEmpty()) break

            val dtos = batch.map { point ->
                TripPointInsertDto(
                    tripId = remoteTripId,
                    userId = point.userId,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    accuracyMeters = point.accuracyMeters,
                    speedKmh = point.speedKmh,
                    headingDegrees = point.headingDegrees,
                    altitudeMeters = point.altitudeMeters,
                    capturedAt = LocationUtils.formatIso8601Utc(point.capturedAt),
                    sequenceNumber = point.sequenceNumber,
                    source = "MOBILE_GPS"
                )
            }

            Log.d("PHDriveSync", "REMOTE_TRIP_POINTS_INSERT_REQUEST size=${batch.size} remoteTripId=$remoteTripId")
            val insertResult = remoteDataSource.insertTripPointsBatch(dtos)
            if (insertResult.isSuccess) {
                val pointIds = batch.map { it.localId }
                tripPointDao.markPointsSynced(pointIds)
                totalSynced += batch.size
                Log.d("PHDriveSync", "REMOTE_TRIP_POINTS_INSERT_SUCCESS size=${batch.size}")
            } else {
                val error = insertResult.exceptionOrNull()
                val errorCode = extractErrorCode(error ?: Exception())
                val message = error?.message ?: ""
                Log.e("PHDriveSync", "REMOTE_TRIP_POINTS_INSERT_ERROR\nHTTP status=500\nPostgREST code=$errorCode\nmessage=$message\ndetails=\nhint=")
                _lastSyncErrorCode.value = errorCode
                syncPreferences?.saveLastSyncErrorCode(errorCode)

                if (error is SessionExpiredException) {
                    return Result.failure(error)
                }

                val firstSeq = batch.firstOrNull()?.sequenceNumber ?: 0
                val lastSeq = batch.lastOrNull()?.sequenceNumber ?: 0
                val sanitizedMsg = sanitizeErrorMessage(error?.message ?: "Falha desconhecida")
                Log.d("TripSyncDebug", "[Erro PostgREST] Código: $errorCode, Mensagem: $sanitizedMsg, Lote: ${batch.size}, remoteTripId: $remoteTripId, Primeiro Seq: $firstSeq, Último Seq: $lastSeq")

                if (errorCode == "23505" || isUniqueConstraintViolationMsg(error?.message ?: "")) {
                    Log.w(TAG, "Detectado conflito 23505 no lote. Sincronizando um a um de forma idempotente.")
                    var localSyncedInBatch = 0
                    for (i in batch.indices) {
                        val pt = batch[i]
                        val dto = dtos[i]
                        val singleResult = remoteDataSource.insertTripPointsBatch(listOf(dto))
                        if (singleResult.isSuccess) {
                            tripPointDao.markPointsSynced(listOf(pt.localId))
                            localSyncedInBatch++
                        } else {
                            val singleError = singleResult.exceptionOrNull()
                            val singleErrorCode = extractErrorCode(singleError ?: Exception())
                            if (singleErrorCode == "23505" || isUniqueConstraintViolationMsg(singleError?.message ?: "")) {
                                Log.d(TAG, "Ponto seq ${pt.sequenceNumber} já existia na nuvem (23505). Marcando como SYNCED.")
                                tripPointDao.markPointsSynced(listOf(pt.localId))
                                localSyncedInBatch++
                            } else {
                                Log.e(TAG, "Falha real ao enviar ponto seq ${pt.sequenceNumber}: ${singleError?.message}")
                                tripPointDao.markPointFailed(pt.localId)
                            }
                        }
                    }
                    totalSynced += localSyncedInBatch
                } else {
                    val pointIds = batch.map { it.localId }
                    tripPointDao.markPointsFailed(pointIds)
                    return Result.failure(error ?: Exception("Falha ao enviar lote de pontos"))
                }
            }

            if (batch.size < batchSize) break
        }

        return Result.success(totalSynced)
    }

    private fun isUniqueConstraintViolationMsg(msg: String): Boolean {
        val lower = msg.lowercase()
        return lower.contains("23505") ||
                lower.contains("duplicate key") ||
                lower.contains("unique constraint") ||
                lower.contains("trip_points_trip_id_sequence_number_key")
    }

    suspend fun syncTripStatus(localTripId: String): Result<Unit> {
        val trip = tripDao.getTripByLocalId(localTripId)
            ?: return Result.failure(IllegalArgumentException("Viagem $localTripId não encontrada"))

        val remoteId = if (trip.remoteTripId.isNullOrBlank()) {
            val remoteResult = ensureRemoteTrip(trip)
            if (remoteResult.isFailure) {
                return Result.failure(remoteResult.exceptionOrNull() ?: Exception("Sem remoteTripId"))
            }
            remoteResult.getOrThrow()
        } else {
            trip.remoteTripId
        }

        val resolvedGpsDistance = if (trip.gpsDistanceKm != null && trip.gpsDistanceKm > 0) {
            trip.gpsDistanceKm
        } else {
            val points = tripPointDao.getPointsForTrip(localTripId)
            val calculated = LocationUtils.calculateTotalDistanceKm(points)
            if (calculated > 0) {
                tripDao.updateGpsDistance(localTripId, calculated)
                calculated
            } else {
                trip.gpsDistanceKm
            }
        }

        val updateDto = when (trip.status) {
            TripStatus.COMPLETED -> TripUpdateDto(
                status = "COMPLETED",
                endedAt = trip.endedAt?.let { LocationUtils.formatIso8601Utc(it) } ?: LocationUtils.formatIso8601Utc(System.currentTimeMillis()),
                endOdometerKm = trip.endOdometerKm,
                gpsDistanceKm = resolvedGpsDistance
            )
            TripStatus.CANCELLED -> TripUpdateDto(
                status = "CANCELLED",
                endedAt = trip.endedAt?.let { LocationUtils.formatIso8601Utc(it) } ?: LocationUtils.formatIso8601Utc(System.currentTimeMillis()),
                endOdometerKm = trip.endOdometerKm,
                gpsDistanceKm = resolvedGpsDistance
            )
            TripStatus.IN_PROGRESS -> TripUpdateDto(
                status = "IN_PROGRESS"
            )
        }

        val updateResult = remoteDataSource.updateTrip(remoteId, updateDto)
        return if (updateResult.isSuccess) {
            tripDao.markSyncStatus(localTripId, SyncStatus.SYNCED)
            Log.d(TAG, "Status da viagem remota $remoteId atualizado para ${trip.status}")
            Result.success(Unit)
        } else {
            val error = updateResult.exceptionOrNull()
            tripDao.markSyncStatus(localTripId, SyncStatus.FAILED)
            Result.failure(error ?: Exception("Falha ao atualizar status da viagem remota"))
        }
    }

    suspend fun syncAllPending(): Result<SyncReport> {
        return syncMutex.withLock {
            val attemptTime = System.currentTimeMillis()
            _lastSyncAttemptTime.value = attemptTime
            syncPreferences?.saveLastSyncAttemptAt(attemptTime)
            _syncState.value = SyncState.Syncing

            var totalPointsSynced = 0
            var totalTripsSynced = 0
            var hasErrors = false
            var sessionExpired = false
            var lastErrorMessage: String? = null

            try {
                val authUserId = AuthRepository.getInstance().getUserId()
                if (authUserId.isNotBlank()) {
                    Log.d(TAG_SYNC, "SYNC_SESSION_OK")
                }

                // 1. RECONCILIAÇÃO DE INTEGRIDADE (Integrity & Reconciliação)
                Log.d("PHDriveSync", "SYNC_SCAN_START")
                val allLocalTrips = tripDao.getAllTrips()
                Log.d("PHDriveSync", "LOCAL_TRIPS_TOTAL=${allLocalTrips.size}")

                for (trip in allLocalTrips) {
                    val remoteId = trip.remoteTripId
                    if (trip.syncStatus == SyncStatus.SYNCED && remoteId.isNullOrBlank()) {
                        Log.e("PHDriveSync", "SYNC_INTEGRITY_ERROR: localTripId=${trip.localId} está marcado como SYNCED mas remoteTripId é nulo/vazio.")
                        tripDao.markSyncStatus(trip.localId, SyncStatus.PENDING)
                    } else if (!remoteId.isNullOrBlank()) {
                        val remoteCheckResult = remoteDataSource.fetchTrip(remoteId)
                        if (remoteCheckResult.isSuccess) {
                            val remoteTrip = remoteCheckResult.getOrNull()
                            if (remoteTrip == null) {
                                Log.e("PHDriveSync", "SYNC_INTEGRITY_ERROR: localTripId=${trip.localId} tem remoteTripId=$remoteId que não existe no Supabase. Realizando reconciliação.")
                                tripDao.updateRemoteTripId(trip.localId, "")
                                tripDao.markSyncStatus(trip.localId, SyncStatus.PENDING)

                                val points = tripPointDao.getPointsForTrip(trip.localId)
                                val syncedPointIds = points.filter { it.syncStatus == SyncStatus.SYNCED }.map { it.localId }
                                if (syncedPointIds.isNotEmpty()) {
                                    tripPointDao.markPointsFailed(syncedPointIds)
                                }
                            }
                        } else {
                            Log.w("PHDriveSync", "Falha ao verificar existência da viagem remota $remoteId: ${remoteCheckResult.exceptionOrNull()?.message}")
                        }
                    }
                }

                val pendingTrips = tripDao.getTripsNeedingSync()
                Log.d("PHDriveSync", "TRIPS_NEEDING_SYNC=${pendingTrips.size}")

                val pendingPointsCount = tripPointDao.countAllPendingPoints()
                Log.d("PHDriveSync", "POINTS_NEEDING_SYNC=$pendingPointsCount")

                for (trip in pendingTrips) {
                    Log.d("PHDriveSync", "SYNC_TRIP_START\nlocalTripId=${trip.localId}\nremoteTripId=${trip.remoteTripId}\nsyncStatus=${trip.syncStatus}")
                    val remoteTripIdResult = ensureRemoteTrip(trip)
                    if (remoteTripIdResult.isFailure) {
                        val ex = remoteTripIdResult.exceptionOrNull()
                        if (ex is SessionExpiredException) {
                            sessionExpired = true
                            _syncState.value = SyncState.SessionExpired(attemptTime)
                            return@withLock Result.failure(ex)
                        }
                        hasErrors = true
                        lastErrorMessage = ex?.message
                    }
                }

                val tripIdsWithPoints = tripPointDao.getTripIdsWithPendingPoints()
                for (tripId in tripIdsWithPoints) {
                    val pointsResult = syncPendingPoints(tripId)
                    if (pointsResult.isSuccess) {
                        totalPointsSynced += pointsResult.getOrDefault(0)
                    } else {
                        val ex = pointsResult.exceptionOrNull()
                        if (ex is SessionExpiredException) {
                            sessionExpired = true
                            _syncState.value = SyncState.SessionExpired(attemptTime)
                            return@withLock Result.failure(ex)
                        }
                        hasErrors = true
                        lastErrorMessage = ex?.message
                    }
                }

                val tripsToUpdate = tripDao.getTripsNeedingSync()
                for (trip in tripsToUpdate) {
                    if (trip.status != TripStatus.IN_PROGRESS) {
                        val statusResult = syncTripStatus(trip.localId)
                        if (statusResult.isSuccess) {
                            totalTripsSynced++
                        } else {
                            hasErrors = true
                            lastErrorMessage = statusResult.exceptionOrNull()?.message
                        }
                    } else {
                        totalTripsSynced++
                    }
                }

                val report = SyncReport(
                    pointsSynced = totalPointsSynced,
                    tripsSynced = totalTripsSynced,
                    hadErrors = hasErrors,
                    sessionExpired = sessionExpired,
                    errorMessage = lastErrorMessage
                )

                if (sessionExpired) {
                    _syncState.value = SyncState.SessionExpired(attemptTime)
                } else if (hasErrors) {
                    _syncState.value = SyncState.Error(
                        message = lastErrorMessage ?: "Erro durante sincronização",
                        timestamp = attemptTime
                    )
                } else {
                    _lastSuccessfulSyncTime.value = attemptTime
                    syncPreferences?.saveLastSuccessfulSyncAt(attemptTime)
                    _lastSyncErrorCode.value = null
                    syncPreferences?.saveLastSyncErrorCode(null)
                    _syncState.value = SyncState.Success(
                        lastSyncTimestamp = attemptTime,
                        pointsSynced = totalPointsSynced
                    )
                }

                Result.success(report)
            } catch (e: Exception) {
                Log.e(TAG, "Erro geral durante syncAllPending: ${e.message}", e)
                val code = extractErrorCode(e)
                _lastSyncErrorCode.value = code
                syncPreferences?.saveLastSyncErrorCode(code)
                _syncState.value = SyncState.Error(e.message ?: "Erro inesperado", attemptTime)
                Result.failure(e)
            }
        }
    }

    fun observePendingPointsCount(): Flow<Int> = tripPointDao.observeAllPendingPointsCount()
    fun observeFailedPointsCount(): Flow<Int> = tripPointDao.observeAllFailedPointsCount()
    fun observePendingTripsCount(): Flow<Int> = tripDao.observePendingTripsCount()

    fun observeTripPendingPointsCount(localTripId: String): Flow<Int> =
        tripPointDao.observePendingPointCount(localTripId)

    fun observeTripSyncedPointsCount(localTripId: String): Flow<Int> =
        tripPointDao.observeSyncedPointCount(localTripId)

    companion object {
        private const val TAG = "SyncRepository"
        private const val TAG_SYNC = "PHDriveSync"
    }
}
