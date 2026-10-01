package com.phdrive.companion.repository

import android.content.Context
import com.phdrive.companion.data.local.PhDriveDatabase
import com.phdrive.companion.model.VehicleBluetoothBinding
import kotlinx.coroutines.flow.Flow

/**
 * Repositório para gerenciamento de vínculos locais entre Veículos e Dispositivos Bluetooth (MICROETAPA 3B.1).
 *
 * Regras:
 * 1. Não permite vincular o mesmo dispositivo ativo a dois veículos distintos (Prevenção de conflito).
 * 2. Mantém 1 dispositivo Bluetooth ativo por veículo nesta versão.
 * 3. Armazena dados puramente locais (não envia endereços ao Supabase).
 */
class VehicleBluetoothRepository(
    private val database: PhDriveDatabase
) {
    constructor(context: Context) : this(PhDriveDatabase.getInstance(context))

    private val bindingDao = database.vehicleBluetoothBindingDao()
    private val vehicleDao = database.vehicleDao()

    fun observeBindingsForVehicle(vehicleId: String): Flow<List<VehicleBluetoothBinding>> =
        bindingDao.getBindingsForVehicle(vehicleId)

    fun observeActiveBindingForVehicle(vehicleId: String): Flow<VehicleBluetoothBinding?> =
        bindingDao.getActiveBindingForVehicle(vehicleId)

    suspend fun getActiveBindingForVehicle(vehicleId: String): VehicleBluetoothBinding? =
        bindingDao.getActiveBindingForVehicleOnce(vehicleId)

    fun observeAllActiveBindings(): Flow<List<VehicleBluetoothBinding>> =
        bindingDao.getActiveBindings()

    suspend fun getAllActiveBindings(): List<VehicleBluetoothBinding> =
        bindingDao.getActiveBindingsOnce()

    suspend fun findBindingByDeviceAddress(address: String): VehicleBluetoothBinding? {
        val hash = VehicleBluetoothBinding.hashAddress(address)
        return bindingDao.findByDeviceHash(hash)
    }

    suspend fun findBindingByDeviceHash(hash: String): VehicleBluetoothBinding? {
        return bindingDao.findByDeviceHash(hash)
    }

    /**
     * Vincula um dispositivo Bluetooth ao veículo especificado.
     * Retorna falha se o mesmo dispositivo já estiver vinculado a outro veículo.
     */
    suspend fun bindDeviceToVehicle(
        userId: String,
        vehicleId: String,
        deviceName: String,
        deviceAddress: String
    ): Result<VehicleBluetoothBinding> {
        val addressHash = VehicleBluetoothBinding.hashAddress(deviceAddress)

        // 1. Validar conflito: verificar se o dispositivo já está vinculado a outro veículo
        val existingWithDevice = bindingDao.findByDeviceHash(addressHash)
        if (existingWithDevice != null && existingWithDevice.active && existingWithDevice.vehicleId != vehicleId) {
            val otherVehicle = vehicleDao.getVehicleById(existingWithDevice.vehicleId)
            val otherVehicleName = otherVehicle?.name ?: "outro veículo"
            return Result.failure(
                IllegalStateException("Este dispositivo já está vinculado ao veículo $otherVehicleName.")
            )
        }

        // 2. Limpar/substituir vínculo anterior deste veículo (1 dispositivo ativo por veículo na 3B.1)
        bindingDao.deleteForVehicle(vehicleId)

        // 3. Criar e persistir novo vínculo local
        val newBinding = VehicleBluetoothBinding.create(
            userId = userId,
            vehicleId = vehicleId,
            deviceName = deviceName,
            deviceAddress = deviceAddress
        )
        bindingDao.insertOrUpdate(newBinding)

        return Result.success(newBinding)
    }

    suspend fun removeBindingForVehicle(vehicleId: String) {
        bindingDao.deleteForVehicle(vehicleId)
    }

    suspend fun removeBinding(bindingId: String) {
        bindingDao.deleteById(bindingId)
    }

    suspend fun setBindingActive(bindingId: String, active: Boolean) {
        bindingDao.setActive(bindingId, active)
    }
}
