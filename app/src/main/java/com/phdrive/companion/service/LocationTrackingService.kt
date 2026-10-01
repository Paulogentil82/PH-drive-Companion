package com.phdrive.companion.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.phdrive.companion.MainActivity
import com.phdrive.companion.repository.AuthRepository
import com.phdrive.companion.repository.TripRepository
import com.phdrive.companion.sync.SyncScheduler
import com.phdrive.companion.util.LocationUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Estados observáveis do rastreamento de GPS (MICROETAPA 3A.5).
 */
enum class TrackingState {
    STOPPED,
    STARTING,
    ACTIVE,
    GPS_STALE,
    RECOVERING,
    ERROR
}

/**
 * Serviço ÚNICO em primeiro plano responsável pelo rastreamento contínuo e resiliente de GPS
 * durante viagens ativas (MICROETAPA 3A.5: Resiliência e Continuidade do GPS).
 *
 * Características:
 * 1. Sem WakeLock Permanente: Rastreamento suportado exclusivamente por Foreground Service com tipo 'location' e START_STICKY.
 * 2. Watchdog GPS (>30s): Detecta ausência prolongada de telemetria e realiza UMA recuperação segura do callback.
 * 3. Observabilidade de Estado: Expõe TrackingState, tempo desde o último ponto e contador de pontos de baixa precisão descartados.
 * 4. Continuidade de Sequência: Respeita rigorosamente o maxSequenceNumber existente ao recuperar uma viagem.
 */
class LocationTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var tripRepository: TripRepository
    private lateinit var authRepository: AuthRepository

    private var activeTripId: String? = null
    private var pointBatchCounter = 0
    private var isLocationUpdatesActive = false
    private var lastPointTimestamp: Long = 0L
    private var watchdogJob: Job? = null
    private var isRecoveryInProgress = false

    override fun onCreate() {
        super.onCreate()
        tripRepository = TripRepository(this)
        authRepository = AuthRepository.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val locations = locationResult.locations
                if (locations.isEmpty()) return
                val tripId = activeTripId ?: return

                lastPointTimestamp = System.currentTimeMillis()
                _secondsSinceLastPoint.value = 0L
                _trackingState.value = TrackingState.ACTIVE
                isRecoveryInProgress = false

                serviceScope.launch {
                    val userId = authRepository.getUserId()
                    for (loc in locations) {
                        val accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null

                        // Validação e filtragem de qualidade de telemetria GPS
                        if (LocationUtils.isValidCoordinates(loc.latitude, loc.longitude) &&
                            (accuracy == null || accuracy <= 100.0)
                        ) {
                            try {
                                tripRepository.addLocationPoint(
                                    localTripId = tripId,
                                    userId = userId,
                                    latitude = loc.latitude,
                                    longitude = loc.longitude,
                                    accuracyMeters = accuracy,
                                    speedKmh = if (loc.hasSpeed()) LocationUtils.speedMpsToKmh(loc.speed) else null,
                                    headingDegrees = if (loc.hasBearing()) LocationUtils.normalizeBearing(loc.bearing) else null,
                                    altitudeMeters = if (loc.hasAltitude()) loc.altitude else null,
                                    capturedAt = loc.time.takeIf { it > 0 } ?: System.currentTimeMillis()
                                )
                                pointBatchCounter++
                            } catch (e: Exception) {
                                Log.e(TAG, "Falha ao persistir ponto GPS local: ${e.message}", e)
                            }
                        } else {
                            _discardedLowAccuracyCount.value++
                            Log.w(
                                TAG,
                                "Ponto GPS descartado (baixa precisão/inválido: lat=${loc.latitude}, lon=${loc.longitude}, acc=$accuracy). Total descartados: ${_discardedLowAccuracyCount.value}"
                            )
                        }
                    }

                    // Dispara sync em lote a cada ~10 novos pontos coletados
                    if (pointBatchCounter >= 10) {
                        pointBatchCounter = 0
                        SyncScheduler.enqueueBackgroundSync(applicationContext)
                    }
                }
            }

            override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                val isAvailable = locationAvailability.isLocationAvailable
                Log.d(TAG, "GPS Availability: $isAvailable")
                if (!isAvailable) {
                    _trackingState.value = TrackingState.GPS_STALE
                    updateNotificationContent("Aguardando sinal de GPS...")
                } else {
                    if (_trackingState.value != TrackingState.RECOVERING) {
                        _trackingState.value = TrackingState.ACTIVE
                    }
                    updateNotificationContent("Gravando trajeto da viagem...")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                Log.d(TAG, "Encerrando serviço de rastreamento GPS a pedido.")
                activeTripId = null
                stopWatchdog()
                stopLocationUpdates()
                _trackingState.value = TrackingState.STOPPED
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val tripId = intent.getStringExtra(EXTRA_TRIP_ID)
                handleStartTracking(tripId)
            }
            else -> {
                // Caso o serviço seja recriado pelo sistema operacional (START_STICKY)
                Log.d(TAG, "Serviço reiniciado pelo sistema (intent=$intent). Verificando continuidade da viagem ativa...")
                handleStartTracking(null)
            }
        }
        return START_STICKY
    }

    private fun handleStartTracking(tripId: String?) {
        _trackingState.value = TrackingState.STARTING
        startForegroundWithNotification("Rastreamento de viagem ativo")

        serviceScope.launch {
            val resolvedTripId = if (!tripId.isNullOrBlank()) {
                tripId
            } else {
                tripRepository.getActiveTrip()?.localId
            }

            if (!resolvedTripId.isNullOrBlank()) {
                activeTripId = resolvedTripId
                lastPointTimestamp = System.currentTimeMillis()
                val trip = tripRepository.getTripByLocalId(resolvedTripId)
                val vehicleName = trip?.vehicleName ?: "Veículo"
                updateNotificationContent("Gravando trajeto para $vehicleName")
                startLocationUpdates()
                startWatchdog()
                Log.d(TAG, "Rastreamento GPS ativo para viagem $resolvedTripId ($vehicleName)")
            } else {
                Log.w(TAG, "Nenhuma viagem ativa encontrada no banco. Encerrando serviço de localização.")
                stopWatchdog()
                stopLocationUpdates()
                _trackingState.value = TrackingState.STOPPED
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(2000L)
                if (isLocationUpdatesActive && activeTripId != null) {
                    val now = System.currentTimeMillis()
                    val diffMs = now - lastPointTimestamp
                    val elapsedSeconds = diffMs / 1000
                    _secondsSinceLastPoint.value = elapsedSeconds

                    if (elapsedSeconds > 30) {
                        if (_trackingState.value == TrackingState.ACTIVE) {
                            _trackingState.value = TrackingState.GPS_STALE
                            Log.w(TAG, "Watchdog: GPS sem atualização há $elapsedSeconds segundos.")
                            updateNotificationContent("GPS sem atualização há ${elapsedSeconds}s...")
                        }

                        // Tentativa de recuperação segura única do callback
                        if (elapsedSeconds > 35 && !isRecoveryInProgress) {
                            isRecoveryInProgress = true
                            _trackingState.value = TrackingState.RECOVERING
                            Log.w(TAG, "Watchdog: Executando tentativa segura de recuperação do callback de localização...")
                            stopLocationUpdates()
                            delay(500L)
                            startLocationUpdates()
                        }
                    }
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        _secondsSinceLastPoint.value = 0L
    }

    private fun startForegroundWithNotification(contentText: String) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotificationContent(contentText: String) {
        try {
            val notification = buildNotification(contentText)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao atualizar notificação foreground: ${e.message}")
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PH Drive Companion")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (isLocationUpdatesActive) {
            Log.d(TAG, "Atualizações de localização já ativas (evitando callbacks duplicados).")
            return
        }
        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(1500L)
                .setMinUpdateDistanceMeters(2.0f)
                .setWaitForAccurateLocation(false)
                .setMaxUpdateDelayMillis(6000L)
                .build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            isLocationUpdatesActive = true
            _trackingState.value = TrackingState.ACTIVE
            Log.d(TAG, "Atualizações de GPS registradas com sucesso no FusedLocationProviderClient.")
        } catch (e: Exception) {
            _trackingState.value = TrackingState.ERROR
            Log.e(TAG, "Erro ao iniciar location updates: ${e.message}", e)
        }
    }

    private fun stopLocationUpdates() {
        if (!isLocationUpdatesActive) return
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            isLocationUpdatesActive = false
            Log.d(TAG, "Atualizações de localização removidas.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao finalizar location updates: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PH Drive GPS Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação de serviço em primeiro plano para telemetria GPS"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWatchdog()
        stopLocationUpdates()
        _trackingState.value = TrackingState.STOPPED
        serviceScope.cancel()
        Log.d(TAG, "LocationTrackingService destruído.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.phdrive.companion.service.ACTION_START"
        const val ACTION_STOP = "com.phdrive.companion.service.ACTION_STOP"
        const val EXTRA_TRIP_ID = "extra_trip_id"

        private const val CHANNEL_ID = "phdrive_location_channel"
        private const val NOTIFICATION_ID = 9110
        private const val TAG = "LocationTrackingService"

        private val _trackingState = MutableStateFlow(TrackingState.STOPPED)
        val trackingState: StateFlow<TrackingState> = _trackingState.asStateFlow()

        private val _secondsSinceLastPoint = MutableStateFlow(0L)
        val secondsSinceLastPoint: StateFlow<Long> = _secondsSinceLastPoint.asStateFlow()

        private val _discardedLowAccuracyCount = MutableStateFlow(0)
        val discardedLowAccuracyCount: StateFlow<Int> = _discardedLowAccuracyCount.asStateFlow()

        fun start(context: Context, localTripId: String? = null) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_START
                if (!localTripId.isNullOrBlank()) {
                    putExtra(EXTRA_TRIP_ID, localTripId)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
