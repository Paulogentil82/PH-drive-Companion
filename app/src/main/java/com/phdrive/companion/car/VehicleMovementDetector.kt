package com.phdrive.companion.car

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.phdrive.companion.util.LocationUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Detector conservador de movimento baseado em GPS para AUTO START (MICROETAPA 3B.2).
 *
 * Regras:
 * 1. Filtra coordenadas com accuracy > 50m ou inválidas.
 * 2. Confirma movimento apenas quando:
 *    - Velocidade >= 8 km/h por pelo menos 10 segundos
 *    OU
 *    - Deslocamento acumulado >= 50 metros em janela de ~30 segundos.
 * 3. Não usa leituras isoladas (jump de GPS).
 * 4. Expõe telemetria observável para UI e diagnóstico.
 */
class VehicleMovementDetector(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val fusedLocationProviderClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val _isMoving = MutableStateFlow(false)
    val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()

    private val _currentSpeedKmh = MutableStateFlow(0.0)
    val currentSpeedKmh: StateFlow<Double> = _currentSpeedKmh.asStateFlow()

    private val _movementDurationSeconds = MutableStateFlow(0L)
    val movementDurationSeconds: StateFlow<Long> = _movementDurationSeconds.asStateFlow()

    private val _displacementMeters = MutableStateFlow(0.0)
    val displacementMeters: StateFlow<Double> = _displacementMeters.asStateFlow()

    private val _sampleCount = MutableStateFlow(0)
    val sampleCount: StateFlow<Int> = _sampleCount.asStateFlow()

    private val _isGpsFresh = MutableStateFlow(false)
    val isGpsFresh: StateFlow<Boolean> = _isGpsFresh.asStateFlow()

    private var locationCallback: LocationCallback? = null
    private var lastLocation: Location? = null
    private var speedWindowStartTime: Long = 0L
    private var displacementWindowStartTime: Long = 0L
    private var accumulatedDisplacement: Double = 0.0
    private var validSamples: Int = 0

    @SuppressLint("MissingPermission")
    fun startDetection() {
        if (locationCallback != null) return

        resetState()

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(1.0f)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    processLocation(location)
                }
            }
        }

        try {
            fusedLocationProviderClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            Log.d(TAG, "VehicleMovementDetector iniciado com sucesso.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar FusedLocation updates no detector de movimento: ${e.message}", e)
        }
    }

    fun stopDetection() {
        locationCallback?.let {
            try {
                fusedLocationProviderClient.removeLocationUpdates(it)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao remover location updates do detector: ${e.message}", e)
            }
        }
        locationCallback = null
        resetState()
        Log.d(TAG, "VehicleMovementDetector parado.")
    }

    private fun resetState() {
        lastLocation = null
        speedWindowStartTime = 0L
        displacementWindowStartTime = 0L
        accumulatedDisplacement = 0.0
        validSamples = 0
        _isMoving.value = false
        _currentSpeedKmh.value = 0.0
        _movementDurationSeconds.value = 0L
        _displacementMeters.value = 0.0
        _sampleCount.value = 0
        _isGpsFresh.value = false
    }

    fun processLocation(location: Location) {
        // Validação de qualidade de sinal GPS
        if (!LocationUtils.isValidCoordinates(location.latitude, location.longitude)) {
            Log.w(TAG, "Coordenadas inválidas descartadas pelo detector.")
            return
        }
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_METERS) {
            Log.w(TAG, "Amostra descartada por baixa precisão: ${location.accuracy}m (max ${MAX_ACCURACY_METERS}m)")
            return
        }

        val now = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        _isGpsFresh.value = true
        validSamples++
        _sampleCount.value = validSamples

        val speedKmh = if (location.hasSpeed()) {
            LocationUtils.speedMpsToKmh(location.speed)
        } else {
            0.0
        }
        _currentSpeedKmh.value = speedKmh

        val prev = lastLocation
        if (prev != null) {
            val dist = location.distanceTo(prev).toDouble()
            // Filtro contra saltos isolados absurdos (>150m entre 2s)
            if (dist < 200.0) {
                accumulatedDisplacement += dist
            }
        }
        _displacementMeters.value = accumulatedDisplacement

        if (displacementWindowStartTime == 0L) {
            displacementWindowStartTime = now
        }

        // Critério 1: Velocidade mantida >= MIN_SPEED_KMH por MIN_SPEED_DURATION_SECONDS
        if (speedKmh >= MIN_SPEED_KMH) {
            if (speedWindowStartTime == 0L) {
                speedWindowStartTime = now
            }
            val speedDurationSec = (now - speedWindowStartTime) / 1000
            _movementDurationSeconds.value = speedDurationSec

            if (speedDurationSec >= MIN_SPEED_DURATION_SECONDS && validSamples >= 3) {
                _isMoving.value = true
                Log.i(TAG, "Movimento confirmado por VELOCIDADE SUSTENTADA: $speedKmh km/h por ${speedDurationSec}s")
            }
        } else {
            speedWindowStartTime = 0L
            _movementDurationSeconds.value = 0L
        }

        // Critério 2: Deslocamento acumulado >= MIN_DISPLACEMENT_METERS dentro de MAX_DISPLACEMENT_WINDOW_SECONDS
        val displacementWindowSec = (now - displacementWindowStartTime) / 1000
        if (displacementWindowSec <= MAX_DISPLACEMENT_WINDOW_SECONDS) {
            if (accumulatedDisplacement >= MIN_DISPLACEMENT_METERS && validSamples >= 3) {
                _isMoving.value = true
                Log.i(TAG, "Movimento confirmado por DESLOCAMENTO ACUMULADO: ${accumulatedDisplacement}m em ${displacementWindowSec}s")
            }
        } else {
            // Reiniciar janela de deslocamento para evitar falsos positivos por drift longo parado
            displacementWindowStartTime = now
            accumulatedDisplacement = 0.0
        }

        lastLocation = location
    }

    companion object {
        private const val TAG = "VehicleMovementDetector"
        const val MIN_SPEED_KMH = 8.0
        const val MIN_SPEED_DURATION_SECONDS = 10L
        const val MIN_DISPLACEMENT_METERS = 50.0
        const val MAX_DISPLACEMENT_WINDOW_SECONDS = 30L
        const val MAX_ACCURACY_METERS = 50.0f
    }
}
