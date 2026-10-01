package com.phdrive.companion.repository

import androidx.room.withTransaction
import com.phdrive.companion.data.backup.BackupEnvelope
import com.phdrive.companion.data.backup.BackupTrip
import com.phdrive.companion.data.backup.BackupTripPoint
import com.phdrive.companion.data.backup.BackupBluetoothBinding
import com.phdrive.companion.data.backup.BackupSyncSummary
import com.phdrive.companion.data.local.PhDriveDatabase
import com.phdrive.companion.model.LocalTrip
import com.phdrive.companion.model.LocalTripPoint
import com.phdrive.companion.model.VehicleBluetoothBinding
import com.phdrive.companion.model.SyncStatus
import com.phdrive.companion.model.TripStatus
import com.phdrive.companion.model.TripStartMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

sealed class BackupValidationResult {
    data class Valid(val envelope: BackupEnvelope) : BackupValidationResult()
    data class Invalid(val reason: String) : BackupValidationResult()
}

data class BackupImportSummary(
    val tripsInBackup: Int,
    val tripsRestored: Int,
    val tripsMerged: Int,
    val tripsIgnored: Int,
    val pointsInBackup: Int,
    val pointsRestored: Int,
    val pointsMerged: Int,
    val pointsIgnored: Int,
    val bluetoothInBackup: Int,
    val bluetoothRestored: Int,
    val bluetoothSkipped: Int
) {
    val tripsImported: Int get() = tripsRestored
    val pointsImported: Int get() = pointsRestored
    val pointsSkippedOrMerged: Int get() = pointsMerged + pointsIgnored
}

class BackupRepository(
    private val database: PhDriveDatabase
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = false
    }

    private fun calculateSha256(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun calculateEnvelopeChecksum(envelope: BackupEnvelope): String {
        val copy = envelope.copy(checksum = null)
        val copyStr = json.encodeToString(BackupEnvelope.serializer(), copy)
        return calculateSha256(copyStr)
    }

    /**
     * Exporta todos os dados locais funcionais para o formato JSON versionado com manifesto e checksum.
     */
    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val tripDao = database.tripDao()
        val pointDao = database.tripPointDao()
        val bluetoothDao = database.vehicleBluetoothBindingDao()

        val localTrips = tripDao.getAllTrips()
        val localPoints = pointDao.getAllPoints()
        val localBluetooth = bluetoothDao.getActiveBindingsOnce()

        val backupTrips = localTrips.map { trip ->
            BackupTrip(
                localId = trip.localId,
                remoteTripId = trip.remoteTripId,
                vehicleId = trip.vehicleId,
                vehicleName = trip.vehicleName,
                userId = trip.userId,
                status = trip.status.name,
                startedAt = trip.startedAt,
                endedAt = trip.endedAt,
                startOdometerKm = trip.startOdometerKm,
                endOdometerKm = trip.endOdometerKm,
                gpsDistanceKm = trip.gpsDistanceKm,
                syncStatus = trip.syncStatus.name,
                lastSyncAttempt = trip.lastSyncAttempt,
                createdAt = trip.createdAt,
                updatedAt = trip.updatedAt,
                startMode = trip.startMode.name
            )
        }

        val backupPoints = localPoints.map { pt ->
            BackupTripPoint(
                localId = pt.localId,
                localTripId = pt.localTripId,
                userId = pt.userId,
                latitude = pt.latitude,
                longitude = pt.longitude,
                accuracyMeters = pt.accuracyMeters,
                speedKmh = pt.speedKmh,
                headingDegrees = pt.headingDegrees,
                altitudeMeters = pt.altitudeMeters,
                capturedAt = pt.capturedAt,
                sequenceNumber = pt.sequenceNumber,
                syncStatus = pt.syncStatus.name,
                retryCount = pt.retryCount,
                lastSyncAttempt = pt.lastSyncAttempt,
                createdAt = pt.createdAt
            )
        }

        val backupBluetooth = localBluetooth.map { b ->
            BackupBluetoothBinding(
                id = b.id,
                userId = b.userId,
                vehicleId = b.vehicleId,
                bluetoothDeviceName = b.bluetoothDeviceName,
                bluetoothDeviceAddressHash = b.bluetoothDeviceAddressHash,
                bluetoothDeviceAddressMasked = b.bluetoothDeviceAddressMasked,
                bluetoothDeviceAddress = b.bluetoothDeviceAddress,
                createdAt = b.createdAt,
                updatedAt = b.updatedAt,
                active = if (b.active) 1 else 0
            )
        }

        val latestTrip = backupTrips.maxOfOrNull { it.startedAt }
        val oldestTrip = backupTrips.minOfOrNull { it.startedAt }

        val tempEnvelope = BackupEnvelope(
            app = BackupEnvelope.BACKUP_APP_IDENTIFIER,
            schemaVersion = BackupEnvelope.CURRENT_SCHEMA_VERSION,
            exportedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            trips = backupTrips,
            tripPoints = backupPoints,
            bluetoothBindings = backupBluetooth,
            backupId = UUID.randomUUID().toString(),
            tripCount = backupTrips.size,
            tripPointCount = backupPoints.size,
            latestTripStartedAt = latestTrip,
            oldestTripStartedAt = oldestTrip,
            syncSummary = BackupSyncSummary(
                pendingTrips = backupTrips.count { it.syncStatus == "PENDING" },
                failedTrips = backupTrips.count { it.syncStatus == "FAILED" },
                syncedTrips = backupTrips.count { it.syncStatus == "SYNCED" },
                pendingPoints = backupPoints.count { it.syncStatus == "PENDING" },
                failedPoints = backupPoints.count { it.syncStatus == "FAILED" },
                syncedPoints = backupPoints.count { it.syncStatus == "SYNCED" }
            )
        )

        // Verificação de Integridade Pré-Salvamento (Requisito 4)
        if (latestTrip != null && localTrips.any { it.startedAt > latestTrip }) {
            throw IllegalStateException("Falha de integridade: existem viagens locais que não seriam incluídas no backup.")
        }

        val checksumValue = calculateEnvelopeChecksum(tempEnvelope)
        val finalEnvelope = tempEnvelope.copy(checksum = checksumValue)

        json.encodeToString(BackupEnvelope.serializer(), finalEnvelope)
    }

    /**
     * Valida o conteúdo JSON antes da importação.
     * Rejeita payloads arbitrários, app incompatível, versões não suportadas ou dados corrompidos.
     */
    fun validateBackupJson(jsonString: String): BackupValidationResult {
        if (jsonString.isBlank()) {
            return BackupValidationResult.Invalid("Arquivo de backup vazio.")
        }

        val envelope: BackupEnvelope = try {
            json.decodeFromString(BackupEnvelope.serializer(), jsonString)
        } catch (e: Exception) {
            return BackupValidationResult.Invalid("Formato JSON inválido: ${e.message}")
        }

        // Validação de Identificador de App
        if (envelope.app != BackupEnvelope.BACKUP_APP_IDENTIFIER) {
            return BackupValidationResult.Invalid(
                "Aplicativo incompatível no backup: '${envelope.app}'. Esperado '${BackupEnvelope.BACKUP_APP_IDENTIFIER}'."
            )
        }

        // Validação de Schema Version
        if (envelope.schemaVersion != BackupEnvelope.CURRENT_SCHEMA_VERSION) {
            return BackupValidationResult.Invalid(
                "Versão de schema não suportada: ${envelope.schemaVersion}. Versão suportada atual: ${BackupEnvelope.CURRENT_SCHEMA_VERSION}."
            )
        }

        // Validação de Checksum (Requisito 6)
        if (envelope.checksum != null) {
            val expected = calculateEnvelopeChecksum(envelope)
            if (envelope.checksum != expected) {
                return BackupValidationResult.Invalid("Arquivo de backup corrompido ou modificado (Falha de Checksum).")
            }
        }

        // Validação das Viagens
        for ((index, trip) in envelope.trips.withIndex()) {
            if (trip.localId.isBlank()) {
                return BackupValidationResult.Invalid("Viagem na posição $index possui localId vazio.")
            }
            if (trip.vehicleId.isBlank()) {
                return BackupValidationResult.Invalid("Viagem ${trip.localId} possui vehicleId vazio.")
            }
            if (trip.userId.isBlank()) {
                return BackupValidationResult.Invalid("Viagem ${trip.localId} possui userId vazio.")
            }
            val statusValid = TripStatus.entries.any { it.name == trip.status }
            if (!statusValid) {
                return BackupValidationResult.Invalid("Viagem ${trip.localId} possui status desconhecido: ${trip.status}")
            }
            val syncValid = SyncStatus.entries.any { it.name == trip.syncStatus }
            if (!syncValid) {
                return BackupValidationResult.Invalid("Viagem ${trip.localId} possui syncStatus desconhecido: ${trip.syncStatus}")
            }
        }

        // Validação dos Pontos
        for ((index, pt) in envelope.tripPoints.withIndex()) {
            if (pt.localId.isBlank()) {
                return BackupValidationResult.Invalid("Ponto na posição $index possui localId vazio.")
            }
            if (pt.localTripId.isBlank()) {
                return BackupValidationResult.Invalid("Ponto ${pt.localId} possui localTripId vazio.")
            }
            if (pt.sequenceNumber < 0) {
                return BackupValidationResult.Invalid("Ponto ${pt.localId} possui sequenceNumber negativo: ${pt.sequenceNumber}")
            }
            if (pt.latitude !in -90.0..90.0) {
                return BackupValidationResult.Invalid("Ponto ${pt.localId} possui latitude inválida: ${pt.latitude}")
            }
            if (pt.longitude !in -180.0..180.0) {
                return BackupValidationResult.Invalid("Ponto ${pt.localId} possui longitude inválida: ${pt.longitude}")
            }
            val syncValid = SyncStatus.entries.any { it.name == pt.syncStatus }
            if (!syncValid) {
                return BackupValidationResult.Invalid("Ponto ${pt.localId} possui syncStatus desconhecido: ${pt.syncStatus}")
            }
        }

        return BackupValidationResult.Valid(envelope)
    }

    /**
     * Importa o envelope de backup de forma idempotente em uma única transação Room.
     */
    suspend fun importBackup(envelope: BackupEnvelope): BackupImportSummary = withContext(Dispatchers.IO) {
        val tripDao = database.tripDao()
        val pointDao = database.tripPointDao()
        val bluetoothDao = database.vehicleBluetoothBindingDao()

        database.withTransaction {
            var tripsRestored = 0
            var tripsMerged = 0
            var tripsIgnored = 0

            var pointsRestored = 0
            var pointsMerged = 0
            var pointsIgnored = 0

            var bluetoothRestored = 0
            var bluetoothSkipped = 0

            // 1. Processa viagens
            for (backupTrip in envelope.trips) {
                val existing = tripDao.getTripByLocalId(backupTrip.localId)
                val tripStatus = TripStatus.valueOf(backupTrip.status)
                val syncStatus = SyncStatus.valueOf(backupTrip.syncStatus)
                val startMode = try {
                    TripStartMode.valueOf(backupTrip.startMode)
                } catch (_: Exception) {
                    TripStartMode.MANUAL
                }

                if (existing == null) {
                    val newTrip = LocalTrip(
                        localId = backupTrip.localId,
                        remoteTripId = backupTrip.remoteTripId,
                        vehicleId = backupTrip.vehicleId,
                        vehicleName = backupTrip.vehicleName,
                        userId = backupTrip.userId,
                        status = tripStatus,
                        startedAt = backupTrip.startedAt,
                        endedAt = backupTrip.endedAt,
                        startOdometerKm = backupTrip.startOdometerKm,
                        endOdometerKm = backupTrip.endOdometerKm,
                        gpsDistanceKm = backupTrip.gpsDistanceKm,
                        syncStatus = syncStatus,
                        lastSyncAttempt = backupTrip.lastSyncAttempt,
                        createdAt = backupTrip.createdAt,
                        updatedAt = backupTrip.updatedAt,
                        startMode = startMode
                    )
                    tripDao.insertTrip(newTrip)
                    tripsRestored++
                } else {
                    val resolvedRemoteTripId = existing.remoteTripId ?: backupTrip.remoteTripId
                    val resolvedSyncStatus = if (existing.syncStatus == SyncStatus.SYNCED) {
                        SyncStatus.SYNCED
                    } else {
                        syncStatus
                    }

                    if (backupTrip.updatedAt > existing.updatedAt) {
                        val updatedTrip = existing.copy(
                            remoteTripId = resolvedRemoteTripId,
                            vehicleName = backupTrip.vehicleName.ifBlank { existing.vehicleName },
                            status = tripStatus,
                            endedAt = backupTrip.endedAt ?: existing.endedAt,
                            endOdometerKm = backupTrip.endOdometerKm ?: existing.endOdometerKm,
                            gpsDistanceKm = maxOf(existing.gpsDistanceKm, backupTrip.gpsDistanceKm),
                            syncStatus = resolvedSyncStatus,
                            updatedAt = backupTrip.updatedAt,
                            startMode = startMode
                        )
                        tripDao.updateTrip(updatedTrip)
                        tripsMerged++
                    } else {
                        if (existing.remoteTripId == null && backupTrip.remoteTripId != null) {
                            tripDao.updateRemoteTripId(existing.localId, backupTrip.remoteTripId)
                            tripsMerged++
                        } else {
                            tripsIgnored++
                        }
                    }
                }
            }

            // 2. Processa pontos de viagem de forma idempotente
            for (backupPt in envelope.tripPoints) {
                val existingById = pointDao.getPointByLocalId(backupPt.localId)
                val existingBySeq = pointDao.getPointByTripAndSequence(backupPt.localTripId, backupPt.sequenceNumber)

                if (existingById == null && existingBySeq == null) {
                    val ptSyncStatus = SyncStatus.valueOf(backupPt.syncStatus)
                    val newPoint = LocalTripPoint(
                        localId = backupPt.localId,
                        localTripId = backupPt.localTripId,
                        userId = backupPt.userId,
                        latitude = backupPt.latitude,
                        longitude = backupPt.longitude,
                        accuracyMeters = backupPt.accuracyMeters,
                        speedKmh = backupPt.speedKmh,
                        headingDegrees = backupPt.headingDegrees,
                        altitudeMeters = backupPt.altitudeMeters,
                        capturedAt = backupPt.capturedAt,
                        sequenceNumber = backupPt.sequenceNumber,
                        syncStatus = ptSyncStatus,
                        retryCount = backupPt.retryCount,
                        lastSyncAttempt = backupPt.lastSyncAttempt,
                        createdAt = backupPt.createdAt
                    )
                    pointDao.insertPoint(newPoint)
                    pointsRestored++
                } else {
                    if (existingById != null && existingById.syncStatus != SyncStatus.SYNCED && backupPt.syncStatus == "SYNCED") {
                        // Merge syncStatus if backup has synced point but local is not
                        pointDao.markPointsSynced(listOf(existingById.localId))
                        pointsMerged++
                    } else {
                        pointsIgnored++
                    }
                }
            }

            // 3. Processa Bluetooth bindings de forma segura
            for (backupBinding in envelope.bluetoothBindings) {
                val existing = bluetoothDao.findById(backupBinding.id)
                if (existing == null) {
                    val newBinding = VehicleBluetoothBinding(
                        id = backupBinding.id,
                        userId = backupBinding.userId,
                        vehicleId = backupBinding.vehicleId,
                        bluetoothDeviceName = backupBinding.bluetoothDeviceName,
                        bluetoothDeviceAddressHash = backupBinding.bluetoothDeviceAddressHash,
                        bluetoothDeviceAddressMasked = backupBinding.bluetoothDeviceAddressMasked,
                        bluetoothDeviceAddress = backupBinding.bluetoothDeviceAddress,
                        createdAt = backupBinding.createdAt,
                        updatedAt = backupBinding.updatedAt,
                        active = backupBinding.active != 0
                    )
                    bluetoothDao.insertOrUpdate(newBinding)
                    bluetoothRestored++
                } else {
                    bluetoothSkipped++
                }
            }

            // 4. Verificação Pós-Restore (Requisito 10)
            if (envelope.tripCount != null) {
                val currentTripsCount = tripDao.countAllTrips()
                if (currentTripsCount < envelope.trips.size) {
                    throw IllegalStateException("Restauração incompleta: Número de viagens restauradas ($currentTripsCount) é menor do que o backup contêm (${envelope.trips.size}).")
                }
            }
            if (envelope.tripPointCount != null) {
                val currentPointsCount = pointDao.countTotalPoints()
                if (currentPointsCount < envelope.tripPoints.size) {
                    throw IllegalStateException("Restauração incompleta: Número de pontos restaurados ($currentPointsCount) é menor do que o backup contêm (${envelope.tripPoints.size}).")
                }
            }

            BackupImportSummary(
                tripsInBackup = envelope.trips.size,
                tripsRestored = tripsRestored,
                tripsMerged = tripsMerged,
                tripsIgnored = tripsIgnored,
                pointsInBackup = envelope.tripPoints.size,
                pointsRestored = pointsRestored,
                pointsMerged = pointsMerged,
                pointsIgnored = pointsIgnored,
                bluetoothInBackup = envelope.bluetoothBindings.size,
                bluetoothRestored = bluetoothRestored,
                bluetoothSkipped = bluetoothSkipped
            )
        }
    }
}
