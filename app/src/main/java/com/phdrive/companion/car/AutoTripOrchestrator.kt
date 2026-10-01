package com.phdrive.companion.car

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phdrive.companion.MainActivity
import com.phdrive.companion.bluetooth.BluetoothConnectionMonitor
import com.phdrive.companion.bluetooth.BluetoothConnectionState
import com.phdrive.companion.bluetooth.SessionState
import com.phdrive.companion.model.LocalTrip
import com.phdrive.companion.model.TripStartMode
import com.phdrive.companion.model.TripStatus
import com.phdrive.companion.model.Vehicle
import com.phdrive.companion.repository.AuthRepository
import com.phdrive.companion.repository.TripRepository
import com.phdrive.companion.repository.VehicleRepository
import com.phdrive.companion.service.LocationTrackingService
import com.phdrive.companion.sync.SyncScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orquestrador central e conservador da automação de viagens (MICROETAPA 3B.2).
 *
 * Responsabilidades:
 * 1. Decide AUTO START de forma conservadora (Veículo identificado + Automação ON + PROJECTION + Movimento GPS).
 * 2. Decide AUTO END de forma resiliente (PROJECTION NOT_CONNECTED >30s + Veículo parado >60s + apenas para viagens AUTOMATIC).
 * 3. Gerencia transições de estado, cooldowns, proteção de concorrência com Mutex e notificações ao usuário.
 * 4. Não duplica viagens em reconexões ou recreation de processos.
 */
class AutoTripOrchestrator(
    private val context: Context,
    private val bluetoothMonitor: BluetoothConnectionMonitor,
    private val vehicleRepository: VehicleRepository,
    private val tripRepository: TripRepository,
    private val movementDetector: VehicleMovementDetector,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val startMutex = Mutex()
    private val endMutex = Mutex()

    private val _autoTripState = MutableStateFlow(AutoTripState.IDLE)
    val autoTripState: StateFlow<AutoTripState> = _autoTripState.asStateFlow()

    private val _cooldownActive = MutableStateFlow(false)
    val cooldownActive: StateFlow<Boolean> = _cooldownActive.asStateFlow()

    private val _cooldownRemainingSec = MutableStateFlow(0L)
    val cooldownRemainingSec: StateFlow<Long> = _cooldownRemainingSec.asStateFlow()

    private val _disconnectDurationSec = MutableStateFlow(0L)
    val disconnectDurationSec: StateFlow<Long> = _disconnectDurationSec.asStateFlow()

    private val _stationaryDurationSec = MutableStateFlow(0L)
    val stationaryDurationSec: StateFlow<Long> = _stationaryDurationSec.asStateFlow()

    // Telemetria delegada do movement detector para UI/Debug
    val movementSpeedKmh: StateFlow<Double> = movementDetector.currentSpeedKmh
    val movementDurationSec: StateFlow<Long> = movementDetector.movementDurationSeconds
    val movementDistanceM: StateFlow<Double> = movementDetector.displacementMeters
    val sampleCount: StateFlow<Int> = movementDetector.sampleCount
    val isGpsFresh: StateFlow<Boolean> = movementDetector.isGpsFresh

    private var cooldownJob: Job? = null
    private var stopEvaluationJob: Job? = null
    private var projectionDisconnectedAt: Long? = null
    private var stationarySince: Long? = null
    private var lastAutoEndedSessionHash: String? = null

    private var isStarted = false

    init {
        createNotificationChannel()
    }

    fun start() {
        if (isStarted) return
        isStarted = true
        observeInputs()
        Log.i(TAG, "AutoTripOrchestrator iniciado e observando eventos.")
    }

    private fun observeInputs() {
        scope.launch {
            combine(
                bluetoothMonitor.connectionSession,
                bluetoothMonitor.carProjectionState,
                bluetoothMonitor.identifiedVehicle,
                tripRepository.observeActiveTrip(),
                movementDetector.isMoving
            ) { session, projection, vehicle, activeTrip, isMoving ->
                AutomationInputs(session, projection, vehicle, activeTrip, isMoving)
            }.collect { inputs ->
                evaluateAutomationState(inputs)
            }
        }
    }

    private data class AutomationInputs(
        val session: com.phdrive.companion.bluetooth.VehicleConnectionSession,
        val projection: CarProjectionState,
        val identifiedVehicle: Vehicle?,
        val activeTrip: LocalTrip?,
        val isMoving: Boolean
    )

    private suspend fun evaluateAutomationState(inputs: AutomationInputs) {
        val activeTrip = inputs.activeTrip
        val projection = inputs.projection
        val vehicle = inputs.identifiedVehicle
        val session = inputs.session
        val isMoving = inputs.isMoving

        // 1. Se já existe viagem ativa (seja automática ou manual, inclusive após process restart)
        if (activeTrip != null && activeTrip.status == TripStatus.IN_PROGRESS) {
            movementDetector.stopDetection()

            if (projection == CarProjectionState.PROJECTION) {
                // Projeção ativa durante viagem
                projectionDisconnectedAt = null
                _disconnectDurationSec.value = 0L
                stationarySince = null
                _stationaryDurationSec.value = 0L
                stopEvaluationJob?.cancel()
                stopEvaluationJob = null

                _autoTripState.value = AutoTripState.TRIP_ACTIVE
                logAutoEndCheck(activeTrip, projection, isMoving, "PROJECTION_ACTIVE", "Viagem em andamento com projeção ativa.")
            } else {
                // Projeção desconectada durante viagem
                if (_autoTripState.value == AutoTripState.TRIP_ACTIVE) {
                    _autoTripState.value = AutoTripState.WAITING_FOR_STOP
                    if (projectionDisconnectedAt == null) {
                        projectionDisconnectedAt = System.currentTimeMillis()
                    }
                    startStopEvaluationLoop(activeTrip)
                }
            }
            return
        }

        // 2. Sem viagem ativa: avaliar elegibilidade para AUTO START
        projectionDisconnectedAt = null
        _disconnectDurationSec.value = 0L
        stationarySince = null
        _stationaryDurationSec.value = 0L
        stopEvaluationJob?.cancel()
        stopEvaluationJob = null

        // Verificar se há cooldown ativo
        if (_cooldownActive.value) {
            _autoTripState.value = AutoTripState.IDLE
            logAutoStartCheck(false, vehicle != null, vehicle?.id, projection == CarProjectionState.PROJECTION, isMoving, activeTrip, "BLOCKED_COOLDOWN", "Orquestrador em período de cooldown.")
            return
        }

        if (vehicle == null) {
            movementDetector.stopDetection()
            _autoTripState.value = AutoTripState.IDLE
            logAutoStartCheck(false, false, null, projection == CarProjectionState.PROJECTION, isMoving, activeTrip, "WAITING_VEHICLE", "Nenhum veículo identificado.")
            Log.d(TAG, "AUTO_START_EVALUATION: vehicleId=null, eligible=false, reason=WAITING_VEHICLE")
            return
        }

        // Obter estado atualizado de auto-automação do veículo
        val freshVehicle = vehicleRepository.getVehicleById(vehicle.id) ?: vehicle
        val isAutoEnabled = freshVehicle.isAutoAutomationEnabled

        Log.d(TAG, "AUTO_MODE_RESOLUTION: vehicleId=${vehicle.id}, vehicleAutomationEnabled=$isAutoEnabled, identifiedVehicle=${vehicle.name}, result=${if (isAutoEnabled) "ENABLED" else "DISABLED"}")

        if (!isAutoEnabled) {
            movementDetector.stopDetection()
            _autoTripState.value = AutoTripState.DISABLED
            logAutoStartCheck(false, true, vehicle.id, projection == CarProjectionState.PROJECTION, isMoving, activeTrip, "DISABLED", "Automação desativada para ${vehicle.name}.")
            Log.d(TAG, "AUTO_START_EVALUATION: vehicleId=${vehicle.id}, eligible=false, reason=DISABLED")
            return
        }

        if (projection != CarProjectionState.PROJECTION) {
            movementDetector.stopDetection()
            _autoTripState.value = AutoTripState.VEHICLE_IDENTIFIED
            logAutoStartCheck(true, true, vehicle.id, false, isMoving, activeTrip, "WAITING_PROJECTION", "Veículo identificado, aguardando Android Auto PROJECTION.")
            Log.d(TAG, "AUTO_START_EVALUATION: vehicleId=${vehicle.id}, eligible=false, reason=WAITING_PROJECTION")
            return
        }

        // Veículo identificado + Automação ON + PROJECTION ativa
        // Verificar se é uma sessão residual que já concluiu viagem anterior
        val currentSessionHash = "${session.identifiedVehicleId}_${session.bluetoothHandshakeAt ?: 0}"
        if (lastAutoEndedSessionHash != null && lastAutoEndedSessionHash == currentSessionHash) {
            movementDetector.stopDetection()
            _autoTripState.value = AutoTripState.IDLE
            logAutoStartCheck(true, true, vehicle.id, true, isMoving, activeTrip, "BLOCKED_RESIDUAL_SESSION", "Viagem já concluída nesta sessão. Aguardando novo ciclo.")
            Log.d(TAG, "AUTO_START_EVALUATION: vehicleId=${vehicle.id}, eligible=false, reason=BLOCKED_RESIDUAL_SESSION")
            return
        }

        // Armar detecção de movimento
        _autoTripState.value = AutoTripState.ARMED
        movementDetector.startDetection()

        if (!isMoving) {
            _autoTripState.value = AutoTripState.WAITING_FOR_MOVEMENT
            logAutoStartCheck(true, true, vehicle.id, true, false, activeTrip, "WAITING_MOVEMENT", "Aguardando confirmação de movimento GPS (>=8km/h por 10s ou >=50m em 30s).")
            Log.d(TAG, "AUTO_START_EVALUATION: vehicleId=${vehicle.id}, eligible=false, reason=WAITING_MOVEMENT")
            return
        }

        // Movimento confirmado! Executar AUTO START
        _autoTripState.value = AutoTripState.STARTING
        logAutoStartCheck(true, true, vehicle.id, true, true, activeTrip, "STARTING", "Condições satisfeitas! Iniciando viagem automática.")
        Log.d(TAG, "AUTO_START_EVALUATION: vehicleId=${vehicle.id}, eligible=true, reason=STARTING")
        executeAutoStart(freshVehicle)
    }

    private fun executeAutoStart(vehicle: Vehicle) {
        Log.d(TAG, "TRIP_START_REQUEST: vehicleId=${vehicle.id}, startMode=AUTOMATIC, source=AUTO_CONTROLLER")
        scope.launch {
            startMutex.withLock {
                val active = tripRepository.getActiveTrip()
                if (active != null) {
                    Log.w(TAG, "Tentativa de auto-start cancelada: já existe viagem ativa (${active.localId}).")
                    _autoTripState.value = AutoTripState.TRIP_ACTIVE
                    return@withLock
                }

                try {
                    val userId = AuthRepository.getInstance().getUserId()
                    val trip = tripRepository.startTripAutomatic(
                        vehicleId = vehicle.id,
                        vehicleName = vehicle.name,
                        userId = userId,
                        startOdometerKm = vehicle.currentOdometerKm
                    )

                    // Parar o detector temporário para que apenas o LocationTrackingService registre GPS
                    movementDetector.stopDetection()
                    LocationTrackingService.start(context, trip.localId)
                    SyncScheduler.enqueueManualSync(context)

                    _autoTripState.value = AutoTripState.TRIP_ACTIVE
                    showAutoNotification("PH Drive iniciou a viagem automaticamente — ${vehicle.name}")
                    Log.i(TAG, "AUTO START EXECUTADO COM SUCESSO: Viagem ${trip.localId} (${vehicle.name})")
                } catch (e: Exception) {
                    Log.e(TAG, "Falha ao executar auto-start: ${e.message}", e)
                    _autoTripState.value = AutoTripState.ERROR
                }
            }
        }
    }

    private fun startStopEvaluationLoop(activeTrip: LocalTrip) {
        if (stopEvaluationJob != null) return

        stopEvaluationJob = scope.launch {
            while (isActive) {
                delay(2000L)

                val currentActiveTrip = tripRepository.getActiveTrip()
                if (currentActiveTrip == null || currentActiveTrip.localId != activeTrip.localId) {
                    break
                }

                val projection = bluetoothMonitor.carProjectionState.value
                if (projection == CarProjectionState.PROJECTION) {
                    // Reconexão do Android Auto! Cancelar avaliação de parada
                    _autoTripState.value = AutoTripState.TRIP_ACTIVE
                    projectionDisconnectedAt = null
                    _disconnectDurationSec.value = 0L
                    stationarySince = null
                    _stationaryDurationSec.value = 0L
                    break
                }

                val now = System.currentTimeMillis()
                val disconnectTime = projectionDisconnectedAt ?: now
                val disconnectDurationSec = (now - disconnectTime) / 1000
                _disconnectDurationSec.value = disconnectDurationSec

                // Obter velocidade recente do LocationTrackingService
                val speedKmh = movementDetector.currentSpeedKmh.value

                // Se houver movimento (> 3 km/h), o veículo ainda está se deslocando
                if (speedKmh > 3.0) {
                    stationarySince = null
                    _stationaryDurationSec.value = 0L
                    logAutoEndCheck(currentActiveTrip, projection, true, "KEEP_MOVING", "Android Auto desconectado — viagem mantida por movimento ($speedKmh km/h).")
                    continue
                }

                // Veículo parado (speed <= 3 km/h)
                if (stationarySince == null) {
                    stationarySince = now
                }
                val stationaryDurationSec = (now - (stationarySince ?: now)) / 1000
                _stationaryDurationSec.value = stationaryDurationSec

                logAutoEndCheck(currentActiveTrip, projection, false, "EVALUATING_STOP", "Desconectado há ${disconnectDurationSec}s (min 30s), Parado há ${stationaryDurationSec}s (min 60s).")

                // Critérios de Auto-End:
                // 1. Viagem criada automaticamente (MANUAL não auto-encerra)
                // 2. Projeção desconectada há pelo menos 30 segundos
                // 3. Veículo parado há pelo menos 60 segundos
                if (disconnectDurationSec >= PROJECTION_DISCONNECT_DEBOUNCE_SEC &&
                    stationaryDurationSec >= STATIONARY_DEBOUNCE_SEC
                ) {
                    if (currentActiveTrip.startMode == TripStartMode.AUTOMATIC) {
                        _autoTripState.value = AutoTripState.ENDING
                        executeAutoEnd(currentActiveTrip)
                        break
                    } else {
                        logAutoEndCheck(currentActiveTrip, projection, false, "MANUAL_TRIP_IGNORED", "Viagem MANUAL não é elegível para auto-encerramento.")
                    }
                }
            }
        }
    }

    private fun executeAutoEnd(trip: LocalTrip) {
        scope.launch {
            endMutex.withLock {
                try {
                    LocationTrackingService.stop(context)
                    tripRepository.completeTripAutomatic(trip.localId)
                    SyncScheduler.enqueueManualSync(context)

                    // Registrar hash da sessão para exigir novo ciclo de conexão antes de novo auto-start
                    val currentSession = bluetoothMonitor.connectionSession.value
                    lastAutoEndedSessionHash = "${currentSession.identifiedVehicleId}_${currentSession.bluetoothHandshakeAt ?: 0}"

                    _autoTripState.value = AutoTripState.IDLE
                    showAutoNotification("Viagem encerrada automaticamente")
                    Log.i(TAG, "AUTO END EXECUTADO COM SUCESSO: Viagem ${trip.localId} concluída.")
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao executar auto-end: ${e.message}", e)
                    _autoTripState.value = AutoTripState.ERROR
                }
            }
        }
    }

    /**
     * Acionado quando o usuário cancela manualmente uma viagem para iniciar cooldown de 10 min.
     */
    fun onUserCancelledTrip() {
        startCooldown(MANUAL_CANCEL_COOLDOWN_SEC)
    }

    private fun startCooldown(seconds: Long) {
        cooldownJob?.cancel()
        _cooldownActive.value = true
        _cooldownRemainingSec.value = seconds

        cooldownJob = scope.launch {
            var remaining = seconds
            while (remaining > 0 && isActive) {
                delay(1000L)
                remaining--
                _cooldownRemainingSec.value = remaining

                // Se houver nova sessão de projeção, o cooldown pode ser resetado
                if (bluetoothMonitor.carProjectionState.value == CarProjectionState.NOT_CONNECTED) {
                    // Aguarda novo ciclo
                }
            }
            _cooldownActive.value = false
            _cooldownRemainingSec.value = 0L
        }
    }

    fun resetSessionCooldown() {
        cooldownJob?.cancel()
        _cooldownActive.value = false
        _cooldownRemainingSec.value = 0L
        lastAutoEndedSessionHash = null
    }

    // Métodos para teste
    fun setAutoTripStateForTesting(state: AutoTripState) {
        _autoTripState.value = state
    }

    fun triggerAutoStartForTesting(vehicle: Vehicle) {
        executeAutoStart(vehicle)
    }

    fun triggerAutoEndForTesting(trip: LocalTrip) {
        executeAutoEnd(trip)
    }

    private fun logAutoStartCheck(
        autoEnabled: Boolean,
        vehicleIdentified: Boolean,
        vehicleId: String?,
        projectionConnected: Boolean,
        isMoving: Boolean,
        activeTrip: LocalTrip?,
        decision: String,
        reason: String
    ) {
        val bluetoothConnected = bluetoothMonitor.connectionState.value == BluetoothConnectionState.CONNECTED
        val gpsFresh = movementDetector.isGpsFresh.value
        val speed = movementDetector.currentSpeedKmh.value
        val sampleCount = movementDetector.sampleCount.value
        val duration = movementDetector.movementDurationSeconds.value

        Log.d(
            "AUTO_START_CHECK",
            "autoEnabled=$autoEnabled vehicleIdentified=$vehicleIdentified vehicleId=$vehicleId " +
                    "bluetoothConnected=$bluetoothConnected projectionConnected=$projectionConnected " +
                    "gpsFresh=$gpsFresh speed=$speed movementSampleCount=$sampleCount movementDuration=$duration " +
                    "activeTrip=${activeTrip?.localId} decision=$decision reason=\"$reason\""
        )
    }

    private fun logAutoEndCheck(
        activeTrip: LocalTrip,
        projection: CarProjectionState,
        isMoving: Boolean,
        decision: String,
        reason: String
    ) {
        val vehicleConnected = bluetoothMonitor.connectionState.value == BluetoothConnectionState.CONNECTED
        val projectionConnected = projection == CarProjectionState.PROJECTION
        val gpsFresh = movementDetector.isGpsFresh.value
        val speed = movementDetector.currentSpeedKmh.value
        val stationarySec = _stationaryDurationSec.value
        val disconnectSec = _disconnectDurationSec.value

        Log.d(
            "AUTO_END_CHECK",
            "tripId=${activeTrip.localId} tripOrigin=${activeTrip.startMode} vehicleConnected=$vehicleConnected " +
                    "projectionConnected=$projectionConnected gpsFresh=$gpsFresh speed=$speed " +
                    "stationaryDuration=$stationarySec disconnectDuration=$disconnectSec " +
                    "decision=$decision reason=\"$reason\""
        )
    }

    private fun showAutoNotification(message: String) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("PH Drive Companion")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(AUTO_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao exibir notificação automática: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "PH Drive Automação",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificações de início e término automático de viagens"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "AutoTripOrchestrator"
        const val PROJECTION_DISCONNECT_DEBOUNCE_SEC = 30L // 30 segundos
        const val STATIONARY_DEBOUNCE_SEC = 60L // 60 segundos
        const val MANUAL_CANCEL_COOLDOWN_SEC = 600L // 10 minutos (600s)

        private const val NOTIFICATION_CHANNEL_ID = "phdrive_auto_trip_channel"
        private const val AUTO_NOTIFICATION_ID = 9115

        @Volatile
        private var instance: AutoTripOrchestrator? = null

        fun getInstance(context: Context): AutoTripOrchestrator {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    val db = com.phdrive.companion.data.local.PhDriveDatabase.getInstance(appContext)
                    val btMonitor = BluetoothConnectionMonitor.getInstance(appContext)
                    val vehRepo = VehicleRepository(db)
                    val trRepo = TripRepository(db)
                    val movDet = VehicleMovementDetector(appContext)
                    AutoTripOrchestrator(appContext, btMonitor, vehRepo, trRepo, movDet).also {
                        instance = it
                    }
                }
            }
        }

        fun setInstanceForTesting(orchestrator: AutoTripOrchestrator?) {
            instance = orchestrator
        }
    }
}
