package com.phdrive.companion.car

import android.content.Context
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Monitor central de Projeção Android Auto (AJUSTE MICROETAPA 3B.1).
 *
 * Utiliza androidx.car.app.connection.CarConnection para detectar:
 * - CONNECTION_TYPE_NOT_CONNECTED (desconectado)
 * - CONNECTION_TYPE_PROJECTION (Android Auto ativo - com fio ou wireless)
 * - CONNECTION_TYPE_NATIVE (Android Automotive OS)
 */
class CarProjectionMonitor(private val context: Context) {
    private val appContext = context.applicationContext

    private val _projectionState: MutableStateFlow<CarProjectionState> = MutableStateFlow(CarProjectionState.NOT_CONNECTED)
    val projectionState: StateFlow<CarProjectionState> = _projectionState.asStateFlow()

    private var carConnection: CarConnection? = null
    private var observer: Observer<Int>? = null
    private var isMonitoring = false

    fun startMonitoring() {
        if (isMonitoring) return
        try {
            val connection = CarConnection(appContext)
            carConnection = connection
            val obs = Observer<Int> { connectionType ->
                val state = when (connectionType) {
                    CarConnection.CONNECTION_TYPE_PROJECTION -> CarProjectionState.PROJECTION
                    CarConnection.CONNECTION_TYPE_NATIVE -> CarProjectionState.NATIVE
                    CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> CarProjectionState.NOT_CONNECTED
                    else -> CarProjectionState.NOT_CONNECTED
                }
                Log.d(TAG, "CarConnection tipo alterado: $connectionType -> $state")
                _projectionState.value = state
            }
            observer = obs
            isMonitoring = true
            try {
                connection.type.observeForever(obs)
            } catch (_: Exception) {
                // Em testes ou ambientes onde MainLooper não está rodando diretamente
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao inicializar CarConnection: ${e.message}")
            _projectionState.value = CarProjectionState.NOT_CONNECTED
        }
    }

    fun stopMonitoring() {
        if (!isMonitoring) return
        val obs = observer
        val conn = carConnection
        if (obs != null && conn != null) {
            try {
                conn.type.removeObserver(obs)
            } catch (_: Exception) {}
        }
        observer = null
        carConnection = null
        isMonitoring = false
    }

    fun setProjectionStateForTesting(state: CarProjectionState) {
        _projectionState.value = state
    }

    companion object {
        private const val TAG = "CarProjectionMonitor"

        @Volatile
        private var instance: CarProjectionMonitor? = null

        fun getInstance(context: Context): CarProjectionMonitor {
            return instance ?: synchronized(this) {
                instance ?: CarProjectionMonitor(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun setInstanceForTesting(monitor: CarProjectionMonitor?) {
            instance = monitor
        }
    }
}
