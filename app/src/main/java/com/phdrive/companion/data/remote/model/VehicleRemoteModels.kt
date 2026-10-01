package com.phdrive.companion.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VehicleRemoteDto(
    val id: String,
    @SerialName("user_id") val userId: String = "",
    val name: String,
    val type: String? = null,
    val brand: String? = null,
    val model: String? = null,
    val version: String? = null,
    val year: Int? = null,
    @SerialName("license_plate") val licensePlate: String? = null,
    @SerialName("fuel_type") val fuelType: String? = null,
    @SerialName("odometer_km") val odometerKm: Double? = null,
    val active: Boolean? = true
)
