package com.phdrive.companion.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.phdrive.companion.data.local.SyncPreferences
import com.phdrive.companion.data.remote.SessionExpiredException
import com.phdrive.companion.repository.AuthRepository
import com.phdrive.companion.repository.SyncRepository
import com.phdrive.companion.sync.SyncScheduler

class TripSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val syncRepository = SyncRepository(appContext)
    private val syncPreferences = SyncPreferences.getInstance(appContext)

    override suspend fun doWork(): Result {
        Log.d(TAG, "SYNC_WORKER_STARTED")
        val now = System.currentTimeMillis()
        syncPreferences.saveLastSyncAttemptAt(now)

        val authRepository = AuthRepository.getInstance()
        val token = authRepository.getOrRefreshAccessToken()

        // 9. WORKMANAGER: Se não houver sessão válida, não apagar Room nem marcar PENDING como SYNCED
        if (token.isNullOrBlank()) {
            Log.w(TAG, "AUTH_REQUIRED: Sessão ausente ou expirada. Preservando dados locais.")
            Log.d(TAG, "AUTH_REQUIRED")
            return Result.retry()
        }

        return try {
            val result = syncRepository.syncAllPending()
            if (result.isSuccess) {
                val report = result.getOrThrow()
                if (report.sessionExpired) {
                    Log.w(TAG, "AUTH_REQUIRED: Sessão expirou durante a sincronização.")
                    Log.d(TAG, "AUTH_REQUIRED")
                    Result.retry()
                } else if (report.hadErrors) {
                    Log.d(TAG, "SYNC_WORKER_RETRY")
                    Result.retry()
                } else {
                    val db = com.phdrive.companion.data.local.PhDriveDatabase.getInstance(applicationContext)
                    val remainingPendingTrips = db.tripDao().countPendingTrips()
                    val remainingPendingPoints = db.tripPointDao().countAllPendingPoints()
                    if (remainingPendingTrips > 0 || remainingPendingPoints > 0) {
                        Log.w(TAG, "SYNC_WORKER_RETRY: Ainda existem pendências locais não confirmadas remotamente ($remainingPendingTrips viagens, $remainingPendingPoints pontos).")
                        Log.d(TAG, "SYNC_WORKER_RETRY")
                        Result.retry()
                    } else {
                        Log.d(TAG, "SYNC_WORKER_SUCCESS")
                        Result.success()
                    }
                }
            } else {
                val ex = result.exceptionOrNull()
                if (ex is SessionExpiredException) {
                    Log.w(TAG, "AUTH_REQUIRED: ${ex.message}")
                    Log.d(TAG, "AUTH_REQUIRED")
                } else {
                    Log.w(TAG, "TripSyncWorker falhou: ${ex?.message}. Agendando retry.")
                }
                Log.d(TAG, "SYNC_WORKER_RETRY")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "SYNC_WORKER_FAILURE", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "PHDriveSync"

        fun enqueue(context: Context) {
            SyncScheduler.enqueueBackgroundSync(context)
        }

        fun enqueueImmediate(context: Context) {
            SyncScheduler.enqueueManualSync(context)
        }
    }
}
