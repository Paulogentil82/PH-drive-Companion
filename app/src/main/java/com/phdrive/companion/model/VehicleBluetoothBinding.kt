package com.phdrive.companion.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest
import java.util.UUID

/**
 * Modelo de vínculo local entre um veículo cadastrado e um dispositivo Bluetooth (MICROETAPA 3B.1).
 *
 * Características:
 * - Persistência puramente local em Room.
 * - Endereço MAC não é enviado ao Supabase (privacidade estrita).
 * - Identificação baseada em hash determinístico SHA-256 do endereço Bluetooth.
 */
@Entity(
    tableName = "vehicle_bluetooth_bindings",
    indices = [
        Index(value = ["vehicleId"]),
        Index(value = ["bluetoothDeviceAddressHash"])
    ]
)
data class VehicleBluetoothBinding(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val vehicleId: String,
    val bluetoothDeviceName: String,
    val bluetoothDeviceAddressHash: String,
    val bluetoothDeviceAddressMasked: String,
    /**
     * Endereço físico armazenado apenas localmente no SQLite do dispositivo para
     * correspondência direta com BluetoothDevice.address das APIs Android, sem exposição em rede.
     */
    val bluetoothDeviceAddress: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val active: Boolean = true
) {
    companion object {
        fun hashAddress(address: String): String {
            val normalized = address.trim().uppercase()
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(normalized.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun maskAddress(address: String): String {
            val trimmed = address.trim()
            if (trimmed.length < 5) return "••:••:••:••:••"
            val lastPart = trimmed.takeLast(5)
            return "••:••:••:$lastPart"
        }

        fun create(
            userId: String,
            vehicleId: String,
            deviceName: String,
            deviceAddress: String
        ): VehicleBluetoothBinding {
            return VehicleBluetoothBinding(
                id = UUID.randomUUID().toString(),
                userId = userId,
                vehicleId = vehicleId,
                bluetoothDeviceName = deviceName.ifBlank { "Dispositivo Bluetooth" },
                bluetoothDeviceAddressHash = hashAddress(deviceAddress),
                bluetoothDeviceAddressMasked = maskAddress(deviceAddress),
                bluetoothDeviceAddress = deviceAddress.trim().uppercase(),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                active = true
            )
        }
    }
}
