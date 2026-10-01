package com.phdrive.companion.bluetooth

/**
 * Estados da sessão combinada de conexão do veículo (Bluetooth Handshake + Android Auto Projection).
 */
enum class SessionState {
    IDLE,
    HANDSHAKE,
    PROJECTED,
    DISCONNECTING
}

/**
 * Estado conceitual da sessão de conexão do veículo (AJUSTE MICROETAPA 3B.1).
 */
data class VehicleConnectionSession(
    val candidateVehicleId: String? = null,
    val candidateVehicleName: String? = null,
    val bluetoothDeviceName: String? = null,
    val bluetoothDeviceAddressHash: String? = null,
    val bluetoothHandshakeAt: Long? = null,
    val projectionActive: Boolean = false,
    val identifiedVehicleId: String? = null,
    val identifiedVehicleName: String? = null,
    val sessionState: SessionState = SessionState.IDLE,
    val lastConnectionChangeAt: Long = System.currentTimeMillis()
)
