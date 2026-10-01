package com.phdrive.companion.repository

import android.content.Context
import android.util.Log
import com.phdrive.companion.data.local.PhDriveDatabase
import com.phdrive.companion.data.remote.SupabaseVehicleRemoteDataSource
import com.phdrive.companion.data.remote.VehicleRemoteDataSource
import com.phdrive.companion.model.Vehicle
import kotlinx.coroutines.flow.Flow

class VehicleRepository(
    private val database: PhDriveDatabase,
    private val remoteDataSource: VehicleRemoteDataSource = SupabaseVehicleRemoteDataSource(),
    private val authRepository: AuthRepository = AuthRepository.getInstance()
) {
    constructor(context: Context) : this(
        database = PhDriveDatabase.getInstance(context),
        remoteDataSource = SupabaseVehicleRemoteDataSource(),
        authRepository = AuthRepository.getInstance()
    )

    private val vehicleDao = database.vehicleDao()

    fun observeAllVehicles(): Flow<List<Vehicle>> = vehicleDao.observeAllVehicles()

    fun observeVehicles(userId: String): Flow<List<Vehicle>> = vehicleDao.observeVehiclesForUser(userId)

    suspend fun getVehiclesForUser(userId: String): List<Vehicle> = vehicleDao.getVehiclesForUser(userId)

    suspend fun getVehicleById(id: String): Vehicle? = vehicleDao.getVehicleById(id)

    suspend fun saveVehicle(vehicle: Vehicle) {
        vehicleDao.insertVehicle(vehicle)
    }

    suspend fun updateAutoAutomationEnabled(vehicleId: String, enabled: Boolean) {
        vehicleDao.updateAutoAutomation(vehicleId, enabled)
    }

    suspend fun cleanDemoVehicles() {
        vehicleDao.deleteDemoVehicles()
    }

    suspend fun refreshVehicles(userId: String = authRepository.getUserId()): Result<List<Vehicle>> {
        // 1. Limpar dados demo residuais do cache local
        vehicleDao.deleteDemoVehicles()

        // 2. Buscar exclusivamente dados reais do Supabase (public.vehicles via RLS)
        return remoteDataSource.getVehicles().mapCatching { remoteDtos ->
            val mappedVehicles = remoteDtos.map { dto ->
                Vehicle(
                    id = dto.id,
                    name = dto.name,
                    plate = dto.licensePlate,
                    model = dto.model,
                    brand = dto.brand,
                    version = dto.version,
                    year = dto.year,
                    fuelType = dto.fuelType,
                    type = dto.type,
                    currentOdometerKm = dto.odometerKm ?: 0.0,
                    userId = if (dto.userId.isNotBlank()) dto.userId else userId,
                    active = dto.active ?: true
                )
            }
            if (mappedVehicles.isNotEmpty()) {
                vehicleDao.insertVehicles(mappedVehicles)
            }
            mappedVehicles
        }.onFailure { ex ->
            // NUNCA injetar fallback mockado se a consulta falhar!
            Log.e(TAG, "Falha ao sincronizar veículos do Supabase: ${ex.message}", ex)
        }
    }

    companion object {
        private const val TAG = "VehicleRepository"
    }
}
