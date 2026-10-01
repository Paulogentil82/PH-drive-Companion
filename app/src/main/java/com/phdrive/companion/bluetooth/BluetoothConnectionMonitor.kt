package com.phdrive.companion.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.phdrive.companion.car.CarProjectionMonitor
import com.phdrive.companion.car.CarProjectionState
import com.phdrive.companion.model.Vehicle
import com.phdrive.companion.model.VehicleBluetoothBinding
import com.phdrive.companion.repository.VehicleBluetoothRepository
import com.phdrive.companion.repository.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Estados da conexão Bluetooth (MICROETAPA 3B.1).
 */
enum class BluetoothConnectionState {
    UNAVAILABLE,
    PERMISSION_REQUIRED,
    DISCONNECTED,
    CONNECTED
}

/**
 * Fonte de identificação do veículo (MICROETAPA 3B.2).
 */
enum class VehicleIdentificationSource {
    NENHUMA,
    BLUETOOTH,
    ANDROID_AUTO,
    SESSAO_PERSISTIDA
}

/**
 * Informações do dispositivo Bluetooth conectado ou com handshake concluído.
 */
data class ConnectedDeviceIdentity(
    val name: String,
    val addressHash: String,
    val maskedAddress: String
)

/**
 * Dispositivo Bluetooth pareado no Android.
 */
data class PairedBluetoothDevice(
    val name: String,
    val address: String,
    val maskedAddress: String,
    val addressHash: String
)

/**
 * Monitor central de Conexão do Veículo:
 * - Bluetooth = Identificação inicial / Handshake
 * - Android Auto Projection = Conexão persistente com a central
 *
 * Regras (MICROETAPA 3B.1 e 3B.2):
 * 1. Bluetooth conecta (CAR_BT_36A152) -> Identifica Titan 125 como candidata (source: BLUETOOTH).
 * 2. Android Auto conecta (PROJECTION) -> Confirma a sessão (PROJECTED) (source: SESSAO_PERSISTIDA ou ANDROID_AUTO).
 * 3. Bluetooth desconecta -> Se PROJECTION estiver ativo ou dentro do handshake, Titan 125 CONTINUA IDENTIFICADA.
 * 4. Android Auto desconecta -> Aguarda debounce de 15 segundos antes de encerrar a sessão.
 * 5. Reabertura de app com Android Auto ativo recupera o vínculo se não houver ambiguidade.
 */
class BluetoothConnectionMonitor(
    private val context: Context,
    private val bluetoothRepository: VehicleBluetoothRepository = VehicleBluetoothRepository(context),
    private val vehicleRepository: VehicleRepository = VehicleRepository(context),
    private val carProjectionMonitor: CarProjectionMonitor = CarProjectionMonitor.getInstance(context)
) {
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appContext = context.applicationContext

    private val bluetoothManager: BluetoothManager? by lazy {
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }

    private val _connectionState = MutableStateFlow(BluetoothConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BluetoothConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceIdentity: MutableStateFlow<ConnectedDeviceIdentity?> = MutableStateFlow(null)
    val connectedDeviceIdentity: StateFlow<ConnectedDeviceIdentity?> = _connectedDeviceIdentity.asStateFlow()

    private val _identifiedVehicle: MutableStateFlow<Vehicle?> = MutableStateFlow(null)
    val identifiedVehicle: StateFlow<Vehicle?> = _identifiedVehicle.asStateFlow()

    private val _identificationSource: MutableStateFlow<VehicleIdentificationSource> =
        MutableStateFlow(VehicleIdentificationSource.NENHUMA)
    val identificationSource: StateFlow<VehicleIdentificationSource> = _identificationSource.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(true)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _userFeedbackMessage: MutableStateFlow<String?> = MutableStateFlow(null)
    val userFeedbackMessage: StateFlow<String?> = _userFeedbackMessage.asStateFlow()

    // Estado da Projeção Android Auto e Sessão Integrada
    val carProjectionState: StateFlow<CarProjectionState> = carProjectionMonitor.projectionState

    private val _connectionSession = MutableStateFlow(VehicleConnectionSession())
    val connectionSession: StateFlow<VehicleConnectionSession> = _connectionSession.asStateFlow()

    private var isReceiverRegistered = false
    private var disconnectDebounceJob: Job? = null
    private val stateMutex = Mutex()

    @Suppress("DEPRECATION")
    private fun extractBluetoothDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            Log.d(TAG, "BluetoothBroadcastReceiver recebeu ação: $action")

            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = extractBluetoothDevice(intent)
                    if (device != null) {
                        handleDeviceConnected(device)
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = extractBluetoothDevice(intent)
                    if (device != null) {
                        handleDeviceDisconnected(device)
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val isEnabled = state == BluetoothAdapter.STATE_ON
                    _isBluetoothEnabled.value = isEnabled
                    Log.d(TAG, "Estado do adaptador Bluetooth alterado: isEnabled=$isEnabled")
                    if (!isEnabled) {
                        _connectionState.value = BluetoothConnectionState.DISCONNECTED
                        if (carProjectionMonitor.projectionState.value != CarProjectionState.PROJECTION) {
                            _connectedDeviceIdentity.value = null
                            _identifiedVehicle.value = null
                            _identificationSource.value = VehicleIdentificationSource.NENHUMA
                            _connectionSession.value = VehicleConnectionSession(sessionState = SessionState.IDLE)
                            _userFeedbackMessage.value = "Bluetooth desligado"
                        } else {
                            _userFeedbackMessage.value = "Bluetooth desligado (Projeção Android Auto ativa)"
                        }
                    } else {
                        checkInitialState()
                    }
                }
            }
        }
    }

    init {
        checkInitialState()
        observeCarProjectionChanges()
    }

    private fun observeCarProjectionChanges() {
        monitorScope.launch {
            carProjectionMonitor.projectionState.collect { projectionState ->
                handleProjectionStateChange(projectionState)
            }
        }
    }

    /**
     * Trata mudanças no estado de projeção Android Auto.
     */
    fun handleProjectionStateChange(projectionState: CarProjectionState) {
        monitorScope.launch {
            stateMutex.withLock {
                Log.d(TAG, "handleProjectionStateChange: $projectionState | sessionState=${_connectionSession.value.sessionState}")
                Log.d(TAG, "PROJECTION_STATE_CHANGED: $projectionState")

                when (projectionState) {
                    CarProjectionState.PROJECTION -> {
                        disconnectDebounceJob?.cancel()
                        disconnectDebounceJob = null

                        val currentSession = _connectionSession.value
                        val candidateId = currentSession.candidateVehicleId
                        val handshakeTime = currentSession.bluetoothHandshakeAt ?: 0L
                        val isWithinHandshake = (System.currentTimeMillis() - handshakeTime) <= HANDSHAKE_WINDOW_MS

                        if (candidateId != null && (isWithinHandshake || _identifiedVehicle.value != null)) {
                            val vehicle = _identifiedVehicle.value ?: vehicleRepository.getVehicleById(candidateId)
                            _identifiedVehicle.value = vehicle
                            _identificationSource.value = VehicleIdentificationSource.SESSAO_PERSISTIDA
                            _connectionSession.value = currentSession.copy(
                                projectionActive = true,
                                identifiedVehicleId = vehicle?.id,
                                identifiedVehicleName = vehicle?.name,
                                sessionState = SessionState.PROJECTED,
                                lastConnectionChangeAt = System.currentTimeMillis()
                            )
                            _userFeedbackMessage.value = "Veículo identificado: ${vehicle?.name ?: "Veículo conectado"} (Projeção ativa)"
                            Log.i(TAG, "Sessão confirmada via Android Auto PROJECTION para veículo: ${vehicle?.name}")
                        } else if (_identifiedVehicle.value == null) {
                            val activeBindings = bluetoothRepository.getAllActiveBindings()
                            if (activeBindings.size == 1) {
                                val singleBinding = activeBindings.first()
                                val vehicle = vehicleRepository.getVehicleById(singleBinding.vehicleId)
                                _identifiedVehicle.value = vehicle
                                _identificationSource.value = VehicleIdentificationSource.ANDROID_AUTO
                                _connectionSession.value = VehicleConnectionSession(
                                    candidateVehicleId = vehicle?.id,
                                    candidateVehicleName = vehicle?.name,
                                    bluetoothDeviceName = singleBinding.bluetoothDeviceName,
                                    bluetoothDeviceAddressHash = singleBinding.bluetoothDeviceAddressHash,
                                    bluetoothHandshakeAt = System.currentTimeMillis(),
                                    projectionActive = true,
                                    identifiedVehicleId = vehicle?.id,
                                    identifiedVehicleName = vehicle?.name,
                                    sessionState = SessionState.PROJECTED,
                                    lastConnectionChangeAt = System.currentTimeMillis()
                                )
                                _userFeedbackMessage.value = "Sessão Android Auto recuperada: ${vehicle?.name}"
                                Log.i(TAG, "Sessão Android Auto recuperada para veículo único vinculado: ${vehicle?.name}")
                            } else {
                                // Se há múltiplos vínculos ou nenhum, verificar se há exatamente 1 veículo com automação ativa
                                try {
                                    val allVehicles = vehicleRepository.observeVehicles("").first()
                                    val autoEnabledVehicles = allVehicles.filter { it.isAutoAutomationEnabled }
                                    if (autoEnabledVehicles.size == 1) {
                                        val singleAuto = autoEnabledVehicles.first()
                                        _identifiedVehicle.value = singleAuto
                                        _identificationSource.value = VehicleIdentificationSource.ANDROID_AUTO
                                        _connectionSession.value = VehicleConnectionSession(
                                            candidateVehicleId = singleAuto.id,
                                            candidateVehicleName = singleAuto.name,
                                            projectionActive = true,
                                            identifiedVehicleId = singleAuto.id,
                                            identifiedVehicleName = singleAuto.name,
                                            sessionState = SessionState.PROJECTED,
                                            lastConnectionChangeAt = System.currentTimeMillis()
                                        )
                                        _userFeedbackMessage.value = "Android Auto conectado: ${singleAuto.name}"
                                        Log.i(TAG, "Veículo único com automação ativa identificado via Android Auto: ${singleAuto.name}")
                                    } else {
                                        _identificationSource.value = VehicleIdentificationSource.NENHUMA
                                        _connectionSession.value = currentSession.copy(
                                            projectionActive = true,
                                            sessionState = SessionState.PROJECTED,
                                            lastConnectionChangeAt = System.currentTimeMillis()
                                        )
                                        Log.d(TAG, "Android Auto conectado, mas há ${activeBindings.size} vínculos cadastrados. Aguardando handshake Bluetooth para desambiguação.")
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Falha ao verificar veículos com automação ativa: ${e.message}")
                                }
                            }
                        } else {
                            _connectionSession.value = currentSession.copy(
                                projectionActive = true,
                                sessionState = SessionState.PROJECTED,
                                lastConnectionChangeAt = System.currentTimeMillis()
                            )
                        }
                    }

                    CarProjectionState.NOT_CONNECTED -> {
                        val currentSession = _connectionSession.value
                        if (currentSession.projectionActive || currentSession.sessionState == SessionState.PROJECTED) {
                            _connectionSession.value = currentSession.copy(
                                sessionState = SessionState.DISCONNECTING,
                                lastConnectionChangeAt = System.currentTimeMillis()
                            )
                            Log.d(TAG, "Android Auto desconectado. Iniciando debounce de 15 segundos antes de encerrar sessão...")

                            disconnectDebounceJob?.cancel()
                            disconnectDebounceJob = monitorScope.launch {
                                delay(DISCONNECT_DEBOUNCE_MS)
                                stateMutex.withLock {
                                    if (carProjectionMonitor.projectionState.value == CarProjectionState.NOT_CONNECTED &&
                                        _connectionState.value != BluetoothConnectionState.CONNECTED
                                    ) {
                                        val hadIdentified = _identifiedVehicle.value != null
                                        _identifiedVehicle.value = null
                                        _identificationSource.value = VehicleIdentificationSource.NENHUMA
                                        _connectedDeviceIdentity.value = null
                                        _connectionSession.value = VehicleConnectionSession(
                                            sessionState = SessionState.IDLE,
                                            lastConnectionChangeAt = System.currentTimeMillis()
                                        )
                                        if (hadIdentified) {
                                            _userFeedbackMessage.value = "Conexão com o veículo encerrada"
                                            Log.i(TAG, "Debounce concluído. Sessão do veículo encerrada.")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        // UNKNOWN / NATIVE
                    }
                }
            }
        }
    }

    /**
     * Verifica permissão BLUETOOTH_CONNECT para Android 12+ (API 31+).
     */
    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Inicia monitoramento de eventos Bluetooth e Android Auto.
     */
    fun startMonitoring() {
        carProjectionMonitor.startMonitoring()
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        try {
            appContext.registerReceiver(bluetoothReceiver, filter)
            isReceiverRegistered = true
            Log.d(TAG, "BluetoothConnectionMonitor registrado com sucesso.")
            checkInitialState()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao registrar BroadcastReceiver Bluetooth: ${e.message}", e)
        }
    }

    /**
     * Encerra monitoramento liberando o receiver e o carProjectionMonitor.
     */
    fun stopMonitoring() {
        carProjectionMonitor.stopMonitoring()
        if (!isReceiverRegistered) return
        try {
            appContext.unregisterReceiver(bluetoothReceiver)
            isReceiverRegistered = false
            Log.d(TAG, "BluetoothConnectionMonitor desregistrado.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desregistrar BroadcastReceiver Bluetooth: ${e.message}", e)
        }
    }

    fun clearFeedbackMessage() {
        _userFeedbackMessage.value = null
    }

    /**
     * Atualiza o estado inicial verificando adaptador, permissões e conexões existentes.
     */
    fun checkInitialState() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            _connectionState.value = BluetoothConnectionState.UNAVAILABLE
            _isBluetoothEnabled.value = false
            return
        }

        if (!adapter.isEnabled) {
            _isBluetoothEnabled.value = false
            if (carProjectionMonitor.projectionState.value != CarProjectionState.PROJECTION) {
                _connectionState.value = BluetoothConnectionState.DISCONNECTED
            }
            return
        }

        _isBluetoothEnabled.value = true

        if (!hasBluetoothPermission()) {
            _connectionState.value = BluetoothConnectionState.PERMISSION_REQUIRED
            return
        }

        if (_connectedDeviceIdentity.value == null &&
            carProjectionMonitor.projectionState.value != CarProjectionState.PROJECTION
        ) {
            _connectionState.value = BluetoothConnectionState.DISCONNECTED
        }
    }

    /**
     * Retorna a lista de dispositivos pareados no sistema Android (BluetoothAdapter.bondedDevices).
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<PairedBluetoothDevice> {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled || !hasBluetoothPermission()) {
            return emptyList()
        }

        return try {
            adapter.bondedDevices.map { device ->
                val name = try {
                    device.name ?: "Dispositivo Bluetooth"
                } catch (_: Exception) {
                    "Dispositivo Bluetooth"
                }
                val address = device.address ?: ""
                PairedBluetoothDevice(
                    name = name,
                    address = address,
                    maskedAddress = VehicleBluetoothBinding.maskAddress(address),
                    addressHash = VehicleBluetoothBinding.hashAddress(address)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao listar dispositivos Bluetooth pareados: ${e.message}", e)
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun handleDeviceConnected(device: BluetoothDevice) {
        disconnectDebounceJob?.cancel()
        disconnectDebounceJob = null

        val address = device.address ?: ""
        val name = try {
            device.name ?: "Dispositivo Bluetooth"
        } catch (_: Exception) {
            "Dispositivo Bluetooth"
        }
        val addressHash = VehicleBluetoothBinding.hashAddress(address)
        val masked = VehicleBluetoothBinding.maskAddress(address)

        Log.d(TAG, "BT_DEVICE_CONNECTED: addressHash=$addressHash, deviceName=$name")

        val identity = ConnectedDeviceIdentity(
            name = name,
            addressHash = addressHash,
            maskedAddress = masked
        )
        _connectedDeviceIdentity.value = identity
        _connectionState.value = BluetoothConnectionState.CONNECTED

        monitorScope.launch {
            stateMutex.withLock {
                val activeBindings = bluetoothRepository.getAllActiveBindings()
                Log.d(TAG, "BT_BINDING_MATCH_START: bindingsCount=${activeBindings.size}")

                val binding = bluetoothRepository.findBindingByDeviceHash(addressHash)
                if (binding != null && binding.active) {
                    val oldVehicleId = _identifiedVehicle.value?.id
                    val vehicle = vehicleRepository.getVehicleById(binding.vehicleId)
                    _identifiedVehicle.value = vehicle
                    _identificationSource.value = VehicleIdentificationSource.BLUETOOTH
                    val vehicleName = vehicle?.name ?: "Veículo cadastrado"

                    Log.d(TAG, "BT_BINDING_MATCH_SUCCESS: vehicleId=${binding.vehicleId}, bindingId=${binding.id}")
                    Log.d(TAG, "IDENTIFIED_VEHICLE_CHANGED: oldVehicleId=$oldVehicleId, newVehicleId=${vehicle?.id}, reason=BT_CONNECTED")

                    val isProjectionActive = carProjectionMonitor.projectionState.value == CarProjectionState.PROJECTION
                    _connectionSession.value = VehicleConnectionSession(
                        candidateVehicleId = vehicle?.id,
                        candidateVehicleName = vehicleName,
                        bluetoothDeviceName = name,
                        bluetoothDeviceAddressHash = addressHash,
                        bluetoothHandshakeAt = System.currentTimeMillis(),
                        projectionActive = isProjectionActive,
                        identifiedVehicleId = vehicle?.id,
                        identifiedVehicleName = vehicleName,
                        sessionState = if (isProjectionActive) SessionState.PROJECTED else SessionState.HANDSHAKE,
                        lastConnectionChangeAt = System.currentTimeMillis()
                    )

                    _userFeedbackMessage.value = "Veículo identificado: $vehicleName"
                    Log.i(TAG, "Handshake Bluetooth realizado ($name). Veículo candidato/identificado: $vehicleName (${binding.vehicleId})")
                } else {
                    Log.d(TAG, "BT_BINDING_MATCH_NONE")
                    val oldVehicleId = _identifiedVehicle.value?.id
                    _identifiedVehicle.value = null
                    _identificationSource.value = VehicleIdentificationSource.NENHUMA
                    Log.d(TAG, "IDENTIFIED_VEHICLE_CHANGED: oldVehicleId=$oldVehicleId, newVehicleId=null, reason=BT_CONNECTED_NO_BINDING")

                    _connectionSession.value = _connectionSession.value.copy(
                        bluetoothDeviceName = name,
                        bluetoothDeviceAddressHash = addressHash,
                        bluetoothHandshakeAt = System.currentTimeMillis()
                    )
                    Log.d(TAG, "Dispositivo Bluetooth conectado ($name), mas não está vinculado a nenhum veículo.")
                }
            }
        }
    }

    fun handleDeviceDisconnected(device: BluetoothDevice) {
        val address = device.address ?: ""
        val addressHash = VehicleBluetoothBinding.hashAddress(address)

        monitorScope.launch {
            stateMutex.withLock {
                if (_connectedDeviceIdentity.value?.addressHash == addressHash || address.isBlank()) {
                    _connectionState.value = BluetoothConnectionState.DISCONNECTED

                    val isProjectionActive = carProjectionMonitor.projectionState.value == CarProjectionState.PROJECTION
                    if (isProjectionActive && _identifiedVehicle.value != null) {
                        // Se a projeção Android Auto estiver ativa, NÃO limpar identifiedVehicle!
                        _connectionSession.value = _connectionSession.value.copy(
                            projectionActive = true,
                            sessionState = SessionState.PROJECTED,
                            lastConnectionChangeAt = System.currentTimeMillis()
                        )
                        _identificationSource.value = VehicleIdentificationSource.SESSAO_PERSISTIDA
                        _userFeedbackMessage.value = "Handshake Bluetooth concluído / Projeção ativa"
                        Log.i(TAG, "Bluetooth desconectado após Handshake, mas Android Auto PROJECTION está ativo. Mantendo veículo: ${_identifiedVehicle.value?.name}")
                    } else {
                        val oldVehicleId = _identifiedVehicle.value?.id
                        val hadIdentifiedVehicle = _identifiedVehicle.value != null
                        _connectedDeviceIdentity.value = null
                        _identifiedVehicle.value = null
                        _identificationSource.value = VehicleIdentificationSource.NENHUMA
                        Log.d(TAG, "IDENTIFIED_VEHICLE_CHANGED: oldVehicleId=$oldVehicleId, newVehicleId=null, reason=BT_DISCONNECTED_NO_PROJECTION")

                        val currentSession = _connectionSession.value
                        _connectionSession.value = VehicleConnectionSession(
                            candidateVehicleId = currentSession.candidateVehicleId,
                            candidateVehicleName = currentSession.candidateVehicleName,
                            bluetoothDeviceName = currentSession.bluetoothDeviceName,
                            bluetoothDeviceAddressHash = currentSession.bluetoothDeviceAddressHash,
                            bluetoothHandshakeAt = currentSession.bluetoothHandshakeAt,
                            sessionState = SessionState.IDLE,
                            lastConnectionChangeAt = System.currentTimeMillis()
                        )

                        if (hadIdentifiedVehicle) {
                            _userFeedbackMessage.value = "Bluetooth do veículo desconectado"
                            Log.i(TAG, "Dispositivo Bluetooth do veículo desconectado sem projeção ativa.")
                        }
                    }
                }
            }
        }
    }

    fun cleanup() {
        stopMonitoring()
        monitorScope.cancel()
    }

    companion object {
        private const val TAG = "BluetoothMonitor"
        const val HANDSHAKE_WINDOW_MS = 120_000L // 120 segundos
        const val DISCONNECT_DEBOUNCE_MS = 15_000L // 15 segundos

        @Volatile
        private var instance: BluetoothConnectionMonitor? = null

        fun getInstance(context: Context): BluetoothConnectionMonitor {
            return instance ?: synchronized(this) {
                instance ?: BluetoothConnectionMonitor(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun setInstanceForTesting(monitor: BluetoothConnectionMonitor?) {
            instance = monitor
        }
    }
}
