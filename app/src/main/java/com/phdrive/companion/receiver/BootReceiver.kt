package com.phdrive.companion.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.phdrive.companion.data.local.RebootRecoveryPreferences
import com.phdrive.companion.repository.TripRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receptor de inicialização do sistema para resiliência e continuidade (MICROETAPA 3A.5).
 * Quando o dispositivo reinicia durante uma viagem ativa (IN_PROGRESS),
 * NÃO retoma o GPS silenciosamente. Em vez disso, sinaliza o estado INTERRUPTED_AFTER_REBOOT
 * para que o usuário decida entre RETOMAR ou ENCERRAR na próxima abertura do app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "BootReceiver acionado com action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val tripRepository = TripRepository(context)
                    val activeTrip = tripRepository.getActiveTrip()
                    val rebootPrefs = RebootRecoveryPreferences(context)

                    if (activeTrip != null) {
                        Log.i(
                            TAG,
                            "Viagem ativa detectada após boot: ${activeTrip.localId} (${activeTrip.vehicleName}). Marcando estado INTERRUPTED_AFTER_REBOOT."
                        )
                        // NÃO iniciar LocationTrackingService silenciosamente!
                        // Persistir o estado para exibição do diálogo/alerta no app
                        rebootPrefs.setInterruptedAfterReboot(true, activeTrip.localId)
                    } else {
                        Log.d(TAG, "Nenhuma viagem ativa encontrada no banco após boot.")
                        rebootPrefs.clear()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao checar viagem ativa no BootReceiver: ${e.message}", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        private const val TAG = "PHDriveBootReceiver"
    }
}
