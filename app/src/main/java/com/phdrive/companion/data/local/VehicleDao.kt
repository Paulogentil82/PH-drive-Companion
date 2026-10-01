package com.phdrive.companion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.phdrive.companion.model.Vehicle
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVehicle(vehicle: Vehicle)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVehicles(vehicles: List<Vehicle>)

    @Update
    suspend fun updateVehicle(vehicle: Vehicle)

    @Query("SELECT * FROM vehicles WHERE id = :id LIMIT 1")
    suspend fun getVehicleById(id: String): Vehicle?

    @Query("SELECT * FROM vehicles ORDER BY name ASC")
    fun observeAllVehicles(): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicles WHERE (:userId = '' OR userId = :userId) AND (active = 1 OR active IS NULL) ORDER BY name ASC")
    fun observeVehiclesForUser(userId: String): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicles WHERE (:userId = '' OR userId = :userId) AND (active = 1 OR active IS NULL) ORDER BY name ASC")
    suspend fun getVehiclesForUser(userId: String): List<Vehicle>

    @Query("SELECT * FROM vehicles ORDER BY name ASC")
    suspend fun getAllVehicles(): List<Vehicle>

    @Query("UPDATE vehicles SET currentOdometerKm = :newOdometer WHERE id = :vehicleId")
    suspend fun updateOdometer(vehicleId: String, newOdometer: Double)

    @Query("UPDATE vehicles SET isAutoAutomationEnabled = :enabled WHERE id = :vehicleId")
    suspend fun updateAutoAutomation(vehicleId: String, enabled: Boolean)

    @Query("DELETE FROM vehicles WHERE id IN ('v1-honda-cg', 'v2-yamaha-factor', 'v3-fiat-strada') OR plate IN ('ABC1D23', 'XYZ9K87', 'PHD4E56') OR name IN ('Honda CG 160 Titan', 'Yamaha Factor 150', 'Fiat Strada Endurance')")
    suspend fun deleteDemoVehicles()

    @Query("DELETE FROM vehicles WHERE userId = :userId")
    suspend fun deleteVehiclesForUser(userId: String)

    @Query("DELETE FROM vehicles")
    suspend fun clearAllVehicles()
}
