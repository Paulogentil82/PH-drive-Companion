package com.phdrive.companion.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.phdrive.companion.worker.TripSyncWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

object SyncScheduler {
    const val UNIQUE_WORK_NAME = "phdrive_trip_sync"
    private const val TAG = "PHDriveSync"

    fun buildSyncWorkRequest(): androidx.work.OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        return OneTimeWorkRequestBuilder<TripSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15,
                TimeUnit.SECONDS
            )
            .addTag(UNIQUE_WORK_NAME)
            .build()
    }

    fun enqueueManualSync(context: Context) {
        Log.d(TAG, "SYNC_ENQUEUE")
        val workRequest = buildSyncWorkRequest()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun enqueueBackgroundSync(context: Context) {
        Log.d(TAG, "SYNC_ENQUEUE")
        val workRequest = buildSyncWorkRequest()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    fun observeWorkerState(context: Context): Flow<String> {
        return WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
            .map { list ->
                val state = list.firstOrNull()?.state
                state?.name ?: "IDLE"
            }
    }
}
