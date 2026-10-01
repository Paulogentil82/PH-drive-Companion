package com.phdrive.companion.ui.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.phdrive.companion.data.backup.BackupEnvelope
import com.phdrive.companion.repository.BackupImportSummary
import com.phdrive.companion.repository.BackupRepository
import com.phdrive.companion.repository.BackupValidationResult
import com.phdrive.companion.PHDriveApplication
import com.phdrive.companion.data.local.SyncPreferences
import com.phdrive.companion.data.remote.SessionExpiredException
import com.phdrive.companion.model.LocalTrip
import com.phdrive.companion.model.LocalTripPoint
import com.phdrive.companion.model.Vehicle
import com.phdrive.companion.repository.AuthRepository
import com.phdrive.companion.repository.SyncRepository
import com.phdrive.companion.repository.SyncState
import com.phdrive.companion.repository.TripRepository
import com.phdrive.companion.repository.VehicleRepository
import com.phdrive.companion.service.LocationTrackingService
import com.phdrive.companion.sync.SyncScheduler
import com.phdrive.companion.util.NetworkMonitor
import com.phdrive.companion.bluetooth.BluetoothConnectionMonitor
import com.phdrive.companion.bluetooth.BluetoothConnectionState
import com.phdrive.companion.bluetooth.ConnectedDeviceIdentity
import com.phdrive.companion.bluetooth.PairedBluetoothDevice
import com.phdrive.companion.bluetooth.VehicleConnectionSession
import com.phdrive.companion.car.CarProjectionState
import com.phdrive.companion.model.VehicleBluetoothBinding
import com.phdrive.companion.repository.VehicleBluetoothRepository
import com.phdrive.companion.car.AutoTripOrchestrator
import com.phdrive.companion.car.AutoTripState
import com.phdrive.companion.bluetooth.VehicleIdentificationSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val tripRepo: TripRepository,
    private val vehicleRepo: VehicleRepository,
    private val syncRepo: SyncRepository,
    private val authRepo: AuthRepository,
    private val backupRepo: BackupRepository = BackupRepository(PHDriveApplication.instance.database),
    private val syncPrefs: SyncPreferences = SyncPreferences.getInstance(PHDriveApplication.instance),
    private val bluetoothRepo: VehicleBluetoothRepository = PHDriveApplication.instance.vehicleBluetoothRepository,
    private val bluetoothMonitor: BluetoothConnectionMonitor = PHDriveApplication.instance.bluetoothConnectionMonitor,
    private val autoTripOrchestrator: AutoTripOrchestrator = PHDriveApplication.instance.autoTripOrchestrator
) : ViewModel() {

    init {
        viewModelScope.launch {
            bluetoothMonitor.userFeedbackMessage.collect { feedback ->
                if (!feedback.isNullOrBlank()) {
                    _userMessage.value = feedback
                    bluetoothMonitor.clearFeedbackMessage()
                }
            }
        }
    }

    val bluetoothConnectionState: StateFlow<BluetoothConnectionState> = bluetoothMonitor.connectionState
    val connectedDeviceIdentity: StateFlow<ConnectedDeviceIdentity?> = bluetoothMonitor.connectedDeviceIdentity
    val identifiedVehicle: StateFlow<Vehicle?> = bluetoothMonitor.identifiedVehicle
    val identificationSource: StateFlow<VehicleIdentificationSource> = bluetoothMonitor.identificationSource
    val isBluetoothEnabled: StateFlow<Boolean> = bluetoothMonitor.isBluetoothEnabled
    val carProjectionState: StateFlow<CarProjectionState> = bluetoothMonitor.carProjectionState
    val connectionSession: StateFlow<VehicleConnectionSession> = bluetoothMonitor.connectionSession

    val autoTripState: StateFlow<AutoTripState> = autoTripOrchestrator.autoTripState
    val movementSpeedKmh: StateFlow<Double> = autoTripOrchestrator.movementSpeedKmh
    val movementDurationSec: StateFlow<Long> = autoTripOrchestrator.movementDurationSec
    val movementDistanceM: StateFlow<Double> = autoTripOrchestrator.movementDistanceM
    val cooldownActive: StateFlow<Boolean> = autoTripOrchestrator.cooldownActive
    val cooldownRemainingSec: StateFlow<Long> = autoTripOrchestrator.cooldownRemainingSec
    val disconnectDurationSec: StateFlow<Long> = autoTripOrchestrator.disconnectDurationSec
    val stationaryDurationSec: StateFlow<Long> = autoTripOrchestrator.stationaryDurationSec

    val bluetoothBindings: StateFlow<List<VehicleBluetoothBinding>> = bluetoothRepo.observeAllActiveBindings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeTrip: StateFlow<LocalTrip?> = tripRepo.observeActiveTrip()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val vehicles: StateFlow<List<Vehicle>> = authRepo.currentUserId.flatMapLatest { userId ->
        vehicleRepo.observeVehicles(userId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRefreshingVehicles = MutableStateFlow(false)
    val isRefreshingVehicles: StateFlow<Boolean> = _isRefreshingVehicles.asStateFlow()

    private val _vehicleErrorMessage = MutableStateFlow<String?>(null)
    val vehicleErrorMessage: StateFlow<String?> = _vehicleErrorMessage.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val pointCount: StateFlow<Int> = activeTrip.flatMapLatest { trip ->
        if (trip != null) {
            tripRepo.observePointCount(trip.localId)
        } else {
            flowOf(0)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeTripSyncedPoints: StateFlow<Int> = activeTrip.flatMapLatest { trip ->
        if (trip != null) {
            syncRepo.observeTripSyncedPointsCount(trip.localId)
        } else {
            flowOf(0)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeTripPendingPoints: StateFlow<Int> = activeTrip.flatMapLatest { trip ->
        if (trip != null) {
            syncRepo.observeTripPendingPointsCount(trip.localId)
        } else {
            flowOf(0)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val latestPoint: StateFlow<LocalTripPoint?> = activeTrip.flatMapLatest { trip ->
        if (trip != null) {
            tripRepo.observeLatestPoint(trip.localId)
        } else {
            flowOf(null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val syncState: StateFlow<SyncState> = syncRepo.syncState

    val lastSyncAttemptTime: StateFlow<Long?> = syncPrefs.observeLastSyncAttemptAt()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), syncPrefs.getLastSyncAttemptAt())

    val lastSuccessfulSyncTime: StateFlow<Long?> = syncPrefs.observeLastSuccessfulSyncAt()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), syncPrefs.getLastSuccessfulSyncAt())

    val lastSuccessfulBackupTime: StateFlow<Long?> = syncPrefs.observeLastSuccessfulBackupAt()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), syncPrefs.getLastSuccessfulBackupAt())

    val lastSyncErrorCode: StateFlow<String?> = syncPrefs.observeLastSyncErrorCode()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), syncPrefs.getLastSyncErrorCode())

    val workerState: StateFlow<String> = SyncScheduler.observeWorkerState(PHDriveApplication.instance)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "IDLE")

    val totalPendingPoints: StateFlow<Int> = syncRepo.observePendingPointsCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalFailedPoints: StateFlow<Int> = syncRepo.observeFailedPointsCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalPendingTrips: StateFlow<Int> = syncRepo.observePendingTripsCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalLocalTrips: StateFlow<Int> = tripRepo.observeTotalTripsCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalLocalPoints: StateFlow<Int> = tripRepo.observeTotalPointsCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val tripDiagnostics: StateFlow<List<TripDiagnosticInfo>> = tripRepo.observeAllTrips()
        .flatMapLatest { trips ->
            kotlinx.coroutines.flow.flow {
                val list = trips.map { trip ->
                    val points = tripRepo.getPointsForTrip(trip.localId)
                    TripDiagnosticInfo(
                        localTripId = trip.localId,
                        remoteTripId = trip.remoteTripId,
                        vehicleId = trip.vehicleId,
                        vehicleName = trip.vehicleName,
                        status = trip.status.name,
                        startedAt = trip.startedAt,
                        endedAt = trip.endedAt,
                        syncStatus = trip.syncStatus.name,
                        totalPoints = points.size,
                        pendingPoints = points.count { it.syncStatus == com.phdrive.companion.model.SyncStatus.PENDING },
                        syncedPoints = points.count { it.syncStatus == com.phdrive.companion.model.SyncStatus.SYNCED },
                        failedPoints = points.count { it.syncStatus == com.phdrive.companion.model.SyncStatus.FAILED }
                    )
                }
                emit(list)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isOnline: StateFlow<Boolean> = NetworkMonitor(PHDriveApplication.instance).isOnline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val trackingState: StateFlow<com.phdrive.companion.service.TrackingState> = LocationTrackingService.trackingState
    val secondsSinceLastPoint: StateFlow<Long> = LocationTrackingService.secondsSinceLastPoint
    val discardedLowAccuracyCount: StateFlow<Int> = LocationTrackingService.discardedLowAccuracyCount

    private val rebootPrefs = com.phdrive.companion.data.local.RebootRecoveryPreferences(PHDriveApplication.instance)
    private val _isInterruptedAfterReboot = MutableStateFlow(false)
    val isInterruptedAfterReboot: StateFlow<Boolean> = _isInterruptedAfterReboot.asStateFlow()

    private val _hasLocationPermission = MutableStateFlow(false)
    val hasLocationPermission: StateFlow<Boolean> = _hasLocationPermission.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    init {
        _isInterruptedAfterReboot.value = rebootPrefs.isInterruptedAfterReboot()
        viewModelScope.launch {
            vehicleRepo.cleanDemoVehicles()
            if (authRepo.hasValidSession()) {
                refreshVehicles()
            }
        }
    }

    fun refreshVehicles() {
        viewModelScope.launch {
            if (!authRepo.hasValidSession() && authRepo.getAccessToken().isNullOrBlank()) {
                _vehicleErrorMessage.value = "Sessão expirada. Faça login novamente."
                return@launch
            }
            _isRefreshingVehicles.value = true
            _vehicleErrorMessage.value = null
            val userId = authRepo.getUserId()
            val result = vehicleRepo.refreshVehicles(userId)
            _isRefreshingVehicles.value = false
            result.onFailure { error ->
                Log.e("HomeViewModel", "Erro ao carregar veículos do Supabase: ${error.message}")
                val rawMsg = error.message ?: ""
                _vehicleErrorMessage.value = when {
                    rawMsg.contains("Falha de configuração do Supabase", ignoreCase = true) ||
                    rawMsg.contains("Invalid API key", ignoreCase = true) ||
                    rawMsg.contains("apikey", ignoreCase = true) -> {
                        "Falha de configuração do Supabase."
                    }
                    error is SessionExpiredException || rawMsg.contains("Sessão expirada", ignoreCase = true) -> {
                        "Sessão expirada. Faça login novamente."
                    }
                    else -> {
                        "Falha ao carregar veículos. Verifique sua conexão."
                    }
                }
            }
        }
    }

    fun logout(onLoggedOut: () -> Unit = {}) {
        viewModelScope.launch {
            authRepo.logout()
            onLoggedOut()
        }
    }

    fun updateLocationPermission(granted: Boolean) {
        _hasLocationPermission.value = granted
    }

    fun resumeInterruptedTrip(context: Context, tripId: String) {
        rebootPrefs.clear()
        _isInterruptedAfterReboot.value = false
        LocationTrackingService.start(context, tripId)
        _userMessage.value = "Viagem retomada!"
    }

    fun endInterruptedTrip(context: Context, tripId: String, endOdometerKm: Double? = null) {
        rebootPrefs.clear()
        _isInterruptedAfterReboot.value = false
        viewModelScope.launch {
            try {
                LocationTrackingService.stop(context)
                tripRepo.completeTrip(tripId, endOdometerKm)
                SyncScheduler.enqueueManualSync(context)
                _userMessage.value = "Viagem encerrada com sucesso!"
            } catch (e: Exception) {
                _userMessage.value = "Erro ao encerrar viagem: ${e.message}"
            }
        }
    }

    fun startTrip(context: Context, vehicle: Vehicle, startOdometerKm: Double?) {
        Log.d("AutoTripOrchestrator", "TRIP_START_REQUEST: vehicleId=${vehicle.id}, startMode=MANUAL, source=MANUAL_BUTTON")
        viewModelScope.launch {
            try {
                val userId = authRepo.getUserId()
                val trip = tripRepo.startTrip(
                    vehicleId = vehicle.id,
                    vehicleName = vehicle.name,
                    userId = userId,
                    startOdometerKm = startOdometerKm
                )
                LocationTrackingService.start(context, trip.localId)
                SyncScheduler.enqueueManualSync(context)
                _userMessage.value = "Viagem iniciada para ${vehicle.name}!"
            } catch (e: Exception) {
                _userMessage.value = "Erro ao iniciar viagem: ${e.message}"
            }
        }
    }

    fun endTrip(context: Context, endOdometerKm: Double?) {
        val currentTrip = activeTrip.value ?: return
        viewModelScope.launch {
            try {
                LocationTrackingService.stop(context)
                tripRepo.completeTrip(currentTrip.localId, endOdometerKm)
                SyncScheduler.enqueueManualSync(context)
                _userMessage.value = "Viagem concluída com sucesso!"
            } catch (e: Exception) {
                _userMessage.value = "Erro ao encerrar viagem: ${e.message}"
            }
        }
    }

    fun cancelTrip(context: Context) {
        val currentTrip = activeTrip.value ?: return
        viewModelScope.launch {
            try {
                LocationTrackingService.stop(context)
                tripRepo.cancelTrip(currentTrip.localId)
                autoTripOrchestrator.onUserCancelledTrip()
                SyncScheduler.enqueueManualSync(context)
                _userMessage.value = "Viagem cancelada."
            } catch (e: Exception) {
                _userMessage.value = "Erro ao cancelar viagem: ${e.message}"
            }
        }
    }

    fun syncNow(context: Context) {
        _userMessage.value = "Iniciando sincronização..."
        SyncScheduler.enqueueManualSync(context)
        refreshVehicles()
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    fun showMessage(msg: String) {
        _userMessage.value = msg
    }

    suspend fun generateBackupJson(): String {
        return backupRepo.exportBackupJson()
    }

    fun validateBackupJson(jsonString: String): BackupValidationResult {
        return backupRepo.validateBackupJson(jsonString)
    }

    fun importBackup(
        envelope: com.phdrive.companion.data.backup.BackupEnvelope,
        onSuccess: (com.phdrive.companion.repository.BackupImportSummary) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val summary = backupRepo.importBackup(envelope)
                val msg = "Restauração concluída: ${summary.tripsRestored} viagens restauradas, ${summary.pointsRestored} pontos restaurados."
                _userMessage.value = msg
                saveLastSuccessfulBackupTime()
                onSuccess(summary)
            } catch (e: Exception) {
                val err = "Falha ao importar backup: ${e.message}"
                _userMessage.value = err
                onError(err)
            }
        }
    }

    fun saveLastSuccessfulBackupTime(timestamp: Long = System.currentTimeMillis()) {
        syncPrefs.saveLastSuccessfulBackupAt(timestamp)
    }

    suspend fun prepareBackupPreview(): ExportSummaryData {
        val jsonStr = backupRepo.exportBackupJson()
        val envelope = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<com.phdrive.companion.data.backup.BackupEnvelope>(jsonStr)
        
        val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
        val latestTripStr = envelope.latestTripStartedAt?.let { sdf.format(java.util.Date(it)) } ?: "N/A"
        val oldestTripStr = envelope.oldestTripStartedAt?.let { sdf.format(java.util.Date(it)) } ?: "N/A"
        val estimatedSize = jsonStr.toByteArray(Charsets.UTF_8).size.toDouble() / (1024.0 * 1024.0)

        val trips = envelope.trips
        val points = envelope.tripPoints

        return ExportSummaryData(
            tripsCount = trips.size,
            pointsCount = points.size,
            pendingTrips = trips.count { it.syncStatus == "PENDING" },
            failedTrips = trips.count { it.syncStatus == "FAILED" },
            pendingPoints = points.count { it.syncStatus == "PENDING" },
            failedPoints = points.count { it.syncStatus == "FAILED" },
            latestTripDate = latestTripStr,
            oldestTripDate = oldestTripStr,
            estimatedSizeMb = estimatedSize,
            jsonContent = jsonStr
        )
    }

    fun prepareImportValidation(jsonContent: String): ImportValidationResult {
        return when (val validation = backupRepo.validateBackupJson(jsonContent)) {
            is BackupValidationResult.Valid -> {
                val envelope = validation.envelope
                val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                val latestTripStr = envelope.latestTripStartedAt?.let { sdf.format(java.util.Date(it)) } ?: "N/A"
                val isChecksumValid = envelope.checksum != null && envelope.checksum == backupRepo.calculateEnvelopeChecksum(envelope)
                ImportValidationResult.Compatible(
                    exportedAt = envelope.exportedAt,
                    tripsCount = envelope.trips.size,
                    pointsCount = envelope.tripPoints.size,
                    latestTripDate = latestTripStr,
                    isChecksumValid = isChecksumValid,
                    envelope = envelope
                )
            }
            is BackupValidationResult.Invalid -> {
                ImportValidationResult.Incompatible(validation.reason)
            }
        }
    }

    fun getPairedBluetoothDevices(): List<PairedBluetoothDevice> {
        return bluetoothMonitor.getPairedDevices()
    }

    fun hasBluetoothPermission(): Boolean {
        return bluetoothMonitor.hasBluetoothPermission()
    }

    fun refreshBluetoothState() {
        bluetoothMonitor.checkInitialState()
    }

    fun bindBluetoothDevice(vehicleId: String, device: PairedBluetoothDevice) {
        viewModelScope.launch {
            val userId = authRepo.getUserId()
            val result = bluetoothRepo.bindDeviceToVehicle(
                userId = userId,
                vehicleId = vehicleId,
                deviceName = device.name,
                deviceAddress = device.address
            )
            result.onSuccess {
                _userMessage.value = "Dispositivo '${device.name}' vinculado com sucesso!"
                bluetoothMonitor.checkInitialState()
            }.onFailure { ex ->
                _userMessage.value = ex.message ?: "Falha ao vincular dispositivo."
            }
        }
    }

    fun removeBluetoothBinding(vehicleId: String) {
        viewModelScope.launch {
            try {
                bluetoothRepo.removeBindingForVehicle(vehicleId)
                _userMessage.value = "Vínculo Bluetooth removido."
                bluetoothMonitor.checkInitialState()
            } catch (e: Exception) {
                _userMessage.value = "Erro ao remover vínculo: ${e.message}"
            }
        }
    }

    fun setVehicleAutoAutomation(vehicleId: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                vehicleRepo.updateAutoAutomationEnabled(vehicleId, enabled)
            } catch (e: Exception) {
                _userMessage.value = "Erro ao atualizar automação: ${e.message}"
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = context.applicationContext as? PHDriveApplication ?: PHDriveApplication.instance
            val db = app.database
            return HomeViewModel(
                tripRepo = app.tripRepository,
                vehicleRepo = app.vehicleRepository,
                syncRepo = app.syncRepository,
                authRepo = AuthRepository.getInstance(),
                backupRepo = app.backupRepository,
                syncPrefs = SyncPreferences.getInstance(context),
                bluetoothRepo = app.vehicleBluetoothRepository,
                bluetoothMonitor = app.bluetoothConnectionMonitor,
                autoTripOrchestrator = app.autoTripOrchestrator
            ) as T
        }
    }
}

data class TripDiagnosticInfo(
    val localTripId: String,
    val remoteTripId: String?,
    val vehicleId: String,
    val vehicleName: String,
    val status: String,
    val startedAt: Long,
    val endedAt: Long?,
    val syncStatus: String,
    val totalPoints: Int,
    val pendingPoints: Int,
    val syncedPoints: Int,
    val failedPoints: Int
)

data class ExportSummaryData(
    val tripsCount: Int,
    val pointsCount: Int,
    val pendingTrips: Int,
    val failedTrips: Int,
    val pendingPoints: Int,
    val failedPoints: Int,
    val latestTripDate: String,
    val oldestTripDate: String,
    val estimatedSizeMb: Double,
    val jsonContent: String
)

sealed class ImportValidationResult {
    data class Compatible(
        val exportedAt: String,
        val tripsCount: Int,
        val pointsCount: Int,
        val latestTripDate: String,
        val isChecksumValid: Boolean,
        val envelope: com.phdrive.companion.data.backup.BackupEnvelope
    ) : ImportValidationResult()
    data class Incompatible(val reason: String) : ImportValidationResult()
}

