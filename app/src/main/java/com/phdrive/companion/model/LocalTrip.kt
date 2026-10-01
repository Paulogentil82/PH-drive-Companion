package com.phdrive.companion.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import com.phdrive.companion.model.TripStartMode

@Entity(tableName = "trips")
data class LocalTrip(
    @PrimaryKey val localId: String,
    val remoteTripId: String? = null,
    val vehicleId: String,
    val vehicleName: String = "",
    val userId: String,
    val status: TripStatus = TripStatus.IN_PROGRESS,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val startOdometerKm: Double? = null,
    val endOdometerKm: Double? = null,
    val gpsDistanceKm: Double = 0.0,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val lastSyncAttempt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "MANUAL") val startMode: TripStartMode = TripStartMode.MANUAL
)
