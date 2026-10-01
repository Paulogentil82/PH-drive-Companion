package com.phdrive.companion.repository

import android.content.Context
import com.phdrive.companion.data.local.PhDriveDatabase
import com.phdrive.companion.model.LocalTrip
import com.phdrive.companion.model.LocalTripPoint
import com.phdrive.companion.model.SyncStatus
import com.phdrive.companion.model.TripStatus
import com.phdrive.companion.model.TripStartMode
import com.phdrive.companion.util.LocationUtils
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class TripRepository(
    private val database: PhDriveDatabase
) {
    constructor(context: Context) : this(PhDriveDatabase.getInstance(context))

    private val tripDao = database.tripDao()
    private val tripPointDao = database.tripPointDao()
    private val vehicleDao = database.vehicleDao()

    fun observeActiveTrip(): Flow<LocalTrip?> = tripDao.observeActiveTrip()
    suspend fun getActiveTrip(): LocalTrip? = tripDao.getActiveTrip()

    fun observeAllTrips(): Flow<List<LocalTrip>> = tripDao.observeAllTrips()
    fun observeTotalTripsCount(): Flow<Int> = tripDao.observeTotalTripsCount()
    fun observeTotalPointsCount(): Flow<Int> = tripPointDao.observeTotalPointsCount()
    suspend fun getTripById(localId: String): LocalTrip? = tripDao.getTripByLocalId(localId)

    fun observePointCount(localTripId: String): Flow<Int> = tripPointDao.observePointCount(localTripId)
    fun observeLatestPoint(localTripId: String): Flow<LocalTripPoint?> = tripPointDao.observeLatestPoint(localTripId)

    suspend fun recordPoint(point: LocalTripPoint): Long = tripPointDao.insertPoint(point)
    suspend fun getPointsForTrip(localTripId: String): List<LocalTripPoint> = tripPointDao.getPointsForTrip(localTripId)
    suspend fun getNextSequenceNumber(localTripId: String): Int = tripPointDao.getMaxSequenceNumber(localTripId) + 1
    suspend fun countPointsForTrip(localTripId: String): Int = tripPointDao.countPoints(localTripId)
    suspend fun countPendingPoints(localTripId: String): Int = tripPointDao.countPendingPoints(localTripId)
    suspend fun getTripByLocalId(localTripId: String): LocalTrip? = tripDao.getTripByLocalId(localTripId)
    suspend fun restoreActiveTrip(): LocalTrip? = tripDao.getActiveTrip()

    suspend fun startTrip(
        vehicleId: String,
        vehicleName: String,
        userId: String,
        startOdometerKm: Double?,
        startMode: TripStartMode = TripStartMode.MANUAL
    ): LocalTrip {
        val currentActive = tripDao.getActiveTrip()
        if (currentActive != null && currentActive.vehicleId == vehicleId) {
            throw IllegalStateException("Já existe uma viagem ativa em andamento para o veículo $vehicleName")
        }

        val localTrip = LocalTrip(
            localId = UUID.randomUUID().toString(),
            remoteTripId = null,
            vehicleId = vehicleId,
            vehicleName = vehicleName,
            userId = userId,
            status = TripStatus.IN_PROGRESS,
            startedAt = System.currentTimeMillis(),
            startOdometerKm = startOdometerKm,
            syncStatus = SyncStatus.PENDING,
            startMode = startMode
        )
        tripDao.insertTrip(localTrip)
        return localTrip
    }

    suspend fun startTripAutomatic(
        vehicleId: String,
        vehicleName: String,
        userId: String,
        startOdometerKm: Double?
    ): LocalTrip {
        return startTrip(
            vehicleId = vehicleId,
            vehicleName = vehicleName,
            userId = userId,
            startOdometerKm = startOdometerKm,
            startMode = TripStartMode.AUTOMATIC
        )
    }

    suspend fun completeTripAutomatic(localTripId: String): LocalTrip {
        return completeTrip(localTripId = localTripId, endOdometerKm = null)
    }

    suspend fun addLocationPoint(
        localTripId: String,
        userId: String,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double?,
        speedKmh: Double?,
        headingDegrees: Double?,
        altitudeMeters: Double?,
        capturedAt: Long
    ): LocalTripPoint {
        val maxSeq = tripPointDao.getMaxSequenceNumber(localTripId)
        val nextSeq = if (maxSeq < 0) 0 else maxSeq + 1
        val lastPoint = tripPointDao.getLatestPoint(localTripId)
        val point = LocalTripPoint(
            localId = UUID.randomUUID().toString(),
            localTripId = localTripId,
            userId = userId,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            speedKmh = speedKmh,
            headingDegrees = headingDegrees,
            altitudeMeters = altitudeMeters,
            capturedAt = capturedAt,
            sequenceNumber = nextSeq,
            syncStatus = SyncStatus.PENDING
        )
        tripPointDao.insertPoint(point)

        // Atualização incremental resiliente da distância acumulada da viagem
        val addedDistance = if (lastPoint != null) {
            LocationUtils.calculateHaversineDistanceKm(
                lastPoint.latitude, lastPoint.longitude,
                latitude, longitude
            )
        } else {
            0.0
        }
        val currentTrip = tripDao.getTripByLocalId(localTripId)
        val currentDistance = currentTrip?.gpsDistanceKm ?: 0.0
        val newDistance = currentDistance + addedDistance
        tripDao.updateGpsDistance(localTripId, newDistance)

        return point
    }

    suspend fun completeTrip(localTripId: String, endOdometerKm: Double?): LocalTrip {
        val trip = tripDao.getTripByLocalId(localTripId)
            ?: throw IllegalArgumentException("Viagem $localTripId não encontrada")

        if (endOdometerKm != null && trip.startOdometerKm != null && endOdometerKm < trip.startOdometerKm) {
            throw IllegalArgumentException("Odômetro final ($endOdometerKm) não pode ser menor que o inicial (${trip.startOdometerKm})")
        }

        val points = tripPointDao.getPointsForTrip(localTripId)
        val totalDistanceKm = LocationUtils.calculateTotalDistanceKm(points)

        val updatedTrip = trip.copy(
            status = TripStatus.COMPLETED,
            endedAt = System.currentTimeMillis(),
            endOdometerKm = endOdometerKm,
            gpsDistanceKm = totalDistanceKm,
            syncStatus = SyncStatus.PENDING,
            updatedAt = System.currentTimeMillis()
        )
        tripDao.updateTrip(updatedTrip)

        if (endOdometerKm != null && endOdometerKm > 0) {
            vehicleDao.updateOdometer(trip.vehicleId, endOdometerKm)
        }

        return updatedTrip
    }

    suspend fun cancelTrip(localTripId: String): LocalTrip {
        val trip = tripDao.getTripByLocalId(localTripId)
            ?: throw IllegalArgumentException("Viagem $localTripId não encontrada")

        val points = tripPointDao.getPointsForTrip(localTripId)
        val totalDistanceKm = LocationUtils.calculateTotalDistanceKm(points)

        val updatedTrip = trip.copy(
            status = TripStatus.CANCELLED,
            endedAt = System.currentTimeMillis(),
            gpsDistanceKm = totalDistanceKm,
            syncStatus = SyncStatus.PENDING,
            updatedAt = System.currentTimeMillis()
        )
        tripDao.updateTrip(updatedTrip)
        return updatedTrip
    }
}
