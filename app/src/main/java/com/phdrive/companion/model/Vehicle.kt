package com.phdrive.companion.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(tableName = "vehicles")
@Serializable
data class Vehicle(
    @PrimaryKey val id: String,
    val name: String,
    @SerialName("license_plate") val plate: String? = null,
    val model: String? = null,
    val brand: String? = null,
    val version: String? = null,
    val year: Int? = null,
    @SerialName("fuel_type") val fuelType: String? = null,
    val type: String? = null,
    @SerialName("odometer_km") val currentOdometerKm: Double = 0.0,
    @SerialName("user_id") val userId: String = "",
    val active: Boolean = true,
    @ColumnInfo(defaultValue = "0") val isAutoAutomationEnabled: Boolean = false
)
