package com.phdrive.companion.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TripInsertDto(
    @SerialName("user_id") val userId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    val status: String = "IN_PROGRESS",
    @SerialName("started_at") val startedAt: String,
    @SerialName("start_odometer_km") val startOdometerKm: Double? = null
)

@Serializable
data class TripUpdateDto(
    val status: String,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("end_odometer_km") val endOdometerKm: Double? = null,
    @SerialName("gps_distance_km") val gpsDistanceKm: Double? = null
)

@Serializable
data class TripResponseDto(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("vehicle_id") val vehicleId: String? = null,
    val status: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("start_odometer_km") val startOdometerKm: Double? = null,
    @SerialName("end_odometer_km") val endOdometerKm: Double? = null,
    @SerialName("gps_distance_km") val gpsDistanceKm: Double? = null
)

@Serializable
data class TripPointInsertDto(
    @SerialName("trip_id") val tripId: String,
    @SerialName("user_id") val userId: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("accuracy_meters") val accuracyMeters: Double? = null,
    @SerialName("speed_kmh") val speedKmh: Double? = null,
    @SerialName("heading_degrees") val headingDegrees: Double? = null,
    @SerialName("altitude_meters") val altitudeMeters: Double? = null,
    @SerialName("captured_at") val capturedAt: String,
    @SerialName("sequence_number") val sequenceNumber: Int,
    val source: String = "MOBILE_GPS"
)
