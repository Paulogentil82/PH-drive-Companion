package com.phdrive.companion.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "trip_points",
    indices = [
        Index(value = ["localTripId", "sequenceNumber"], unique = true),
        Index(value = ["syncStatus"])
    ]
)
data class LocalTripPoint(
    @PrimaryKey val localId: String = UUID.randomUUID().toString(),
    val localTripId: String,
    val userId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double? = null,
    val speedKmh: Double? = null,
    val headingDegrees: Double? = null,
    val altitudeMeters: Double? = null,
    val capturedAt: Long = System.currentTimeMillis(),
    val sequenceNumber: Int,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val retryCount: Int = 0,
    val lastSyncAttempt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    @Ignore
    constructor(
        localId: String = UUID.randomUUID().toString(),
        localTripId: String,
        remoteTripId: String?,
        userId: String,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double? = null,
        speedKmh: Double? = null,
        headingDegrees: Double? = null,
        altitudeMeters: Double? = null,
        capturedAt: Long = System.currentTimeMillis(),
        sequenceNumber: Int = 0,
        syncStatus: SyncStatus = SyncStatus.PENDING,
        retryCount: Int = 0,
        lastSyncAttempt: Long? = null,
        createdAt: Long = System.currentTimeMillis()
    ) : this(
        localId = localId,
        localTripId = localTripId,
        userId = userId,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        speedKmh = speedKmh,
        headingDegrees = headingDegrees,
        altitudeMeters = altitudeMeters,
        capturedAt = capturedAt,
        sequenceNumber = sequenceNumber,
        syncStatus = syncStatus,
        retryCount = retryCount,
        lastSyncAttempt = lastSyncAttempt,
        createdAt = createdAt
    )
}
