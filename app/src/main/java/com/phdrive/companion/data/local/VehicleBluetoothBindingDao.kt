package com.phdrive.companion.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.phdrive.companion.model.VehicleBluetoothBinding
import kotlinx.coroutines.flow.Flow

/**
 * Interface DAO para gerenciar vínculos locais entre veículos e dispositivos Bluetooth.
 */
@Dao
interface VehicleBluetoothBindingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(binding: VehicleBluetoothBinding)

    @Query("SELECT * FROM vehicle_bluetooth_bindings WHERE vehicleId = :vehicleId ORDER BY createdAt DESC")
    fun getBindingsForVehicle(vehicleId: String): Flow<List<VehicleBluetoothBinding>>

    @Query("SELECT * FROM vehicle_bluetooth_bindings WHERE vehicleId = :vehicleId AND active = 1 LIMIT 1")
    fun getActiveBindingForVehicle(vehicleId: String): Flow<VehicleBluetoothBinding?>

    @Query("SELECT * FROM vehicle_bluetooth_bindings WHERE vehicleId = :vehicleId AND active = 1 LIMIT 1")
    suspend fun getActiveBindingForVehicleOnce(vehicleId: String): VehicleBluetoothBinding?

    @Query("SELECT * FROM vehicle_bluetooth_bindings WHERE active = 1 ORDER BY createdAt DESC")
    fun getActiveBindings(): Flow<List<VehicleBluetoothBinding>>

    @Query("SELECT * FROM vehicle_bluetooth_bindings WHERE active = 1 ORDER BY createdAt DESC")
    suspend fun getActiveBindingsOnce(): List<VehicleBluetoothBinding>

    @Query("SELECT * FROM vehicle_bluetooth_bindings WHERE bluetoothDeviceAddressHash = :deviceHash AND active = 1 LIMIT 1")
    suspend fun findByDeviceHash(deviceHash: String): VehicleBluetoothBinding?

    @Query("SELECT * FROM vehicle_bluetooth_bindings WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): VehicleBluetoothBinding?

    @Query("UPDATE vehicle_bluetooth_bindings SET active = :active, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM vehicle_bluetooth_bindings WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM vehicle_bluetooth_bindings WHERE vehicleId = :vehicleId")
    suspend fun deleteForVehicle(vehicleId: String)

    @Delete
    suspend fun delete(binding: VehicleBluetoothBinding)

    @Query("DELETE FROM vehicle_bluetooth_bindings")
    suspend fun clearAll()
}
