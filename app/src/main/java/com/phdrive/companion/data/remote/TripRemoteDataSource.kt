package com.phdrive.companion.data.remote

import com.phdrive.companion.data.remote.model.TripInsertDto
import com.phdrive.companion.data.remote.model.TripPointInsertDto
import com.phdrive.companion.data.remote.model.TripResponseDto
import com.phdrive.companion.data.remote.model.TripUpdateDto

interface TripRemoteDataSource {
    suspend fun createTrip(dto: TripInsertDto): Result<TripResponseDto>
    suspend fun findActiveTrip(userId: String, vehicleId: String): Result<TripResponseDto?>
    suspend fun updateTrip(remoteTripId: String, dto: TripUpdateDto): Result<Unit>
    suspend fun insertTripPointsBatch(batch: List<TripPointInsertDto>): Result<Int>
    suspend fun fetchTrip(remoteTripId: String): Result<TripResponseDto?>
}
