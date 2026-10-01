package com.phdrive.companion.data.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupEnvelope(
    val app: String = BACKUP_APP_IDENTIFIER,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAt: String,
    val trips: List<BackupTrip> = emptyList(),
    val tripPoints: List<BackupTripPoint> = emptyList(),
    val bluetoothBindings: List<BackupBluetoothBinding> = emptyList(),
    val backupId: String? = null,
    val tripCount: Int? = null,
    val tripPointCount: Int? = null,
    val latestTripStartedAt: Long? = null,
    val oldestTripStartedAt: Long? = null,
    val checksum: String? = null,
    val syncSummary: BackupSyncSummary? = null
) {
    companion object {
        const val BACKUP_APP_IDENTIFIER = "ph-drive-companion"
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class BackupSyncSummary(
    val pendingTrips: Int,
    val failedTrips: Int,
    val syncedTrips: Int,
    val pendingPoints: Int,
    val failedPoints: Int,
    val syncedPoints: Int
)

@Serializable
data class BackupTrip(
    val localId: String,
    val remoteTripId: String? = null,
    val vehicleId: String,
    val vehicleName: String = "",
    val userId: String,
    val status: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val startOdometerKm: Double? = null,
    val endOdometerKm: Double? = null,
    val gpsDistanceKm: Double = 0.0,
    val syncStatus: String,
    val lastSyncAttempt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val startMode: String = "MANUAL"
)

@Serializable
data class BackupTripPoint(
    val localId: String,
    val localTripId: String,
    val userId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double? = null,
    val speedKmh: Double? = null,
    val headingDegrees: Double? = null,
    val altitudeMeters: Double? = null,
    val capturedAt: Long,
    val sequenceNumber: Int,
    val syncStatus: String,
    val retryCount: Int = 0,
    val lastSyncAttempt: Long? = null,
    val createdAt: Long
)

@Serializable
data class BackupBluetoothBinding(
    val id: String,
    val userId: String,
    val vehicleId: String,
    val bluetoothDeviceName: String,
    val bluetoothDeviceAddressHash: String,
    val bluetoothDeviceAddressMasked: String,
    val bluetoothDeviceAddress: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val active: Int
)
