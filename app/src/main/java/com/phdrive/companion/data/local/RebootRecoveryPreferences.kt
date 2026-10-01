package com.phdrive.companion.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * Gerencia o estado de recuperação pós-reinicialização (MICROETAPA 3A.5).
 * Sinaliza quando uma viagem em andamento foi interrompida pelo reboot do sistema,
 * permitindo ao usuário decidir explicitamente entre RETOMAR ou ENCERRAR a mesma viagem.
 */
class RebootRecoveryPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setInterruptedAfterReboot(interrupted: Boolean, tripId: String? = null) {
        prefs.edit().apply {
            putBoolean(KEY_INTERRUPTED_AFTER_REBOOT, interrupted)
            if (tripId != null) {
                putString(KEY_INTERRUPTED_TRIP_ID, tripId)
            } else if (!interrupted) {
                remove(KEY_INTERRUPTED_TRIP_ID)
            }
            apply()
        }
    }

    fun isInterruptedAfterReboot(): Boolean = prefs.getBoolean(KEY_INTERRUPTED_AFTER_REBOOT, false)

    fun getInterruptedTripId(): String? = prefs.getString(KEY_INTERRUPTED_TRIP_ID, null)

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "phdrive_reboot_recovery_prefs"
        private const val KEY_INTERRUPTED_AFTER_REBOOT = "interrupted_after_reboot"
        private const val KEY_INTERRUPTED_TRIP_ID = "interrupted_trip_id"
    }
}
