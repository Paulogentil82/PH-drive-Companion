package com.phdrive.companion.data.remote

import com.phdrive.companion.data.remote.model.VehicleRemoteDto

interface VehicleRemoteDataSource {
    suspend fun getVehicles(): Result<List<VehicleRemoteDto>>
}
