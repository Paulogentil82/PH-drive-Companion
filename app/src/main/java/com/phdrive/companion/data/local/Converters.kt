package com.phdrive.companion.data.local

import androidx.room.TypeConverter
import com.phdrive.companion.model.TripStartMode
import com.phdrive.companion.model.TripStatus
import com.phdrive.companion.model.SyncStatus

class Converters {
    @TypeConverter
    fun fromTripStartMode(value: TripStartMode): String = value.name

    @TypeConverter
    fun toTripStartMode(value: String): TripStartMode = TripStartMode.valueOf(value)

    @TypeConverter
    fun fromTripStatus(value: TripStatus): String = value.name

    @TypeConverter
    fun toTripStatus(value: String): TripStatus = TripStatus.valueOf(value)

    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String = value.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)
}
