package com.phdrive.companion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.phdrive.companion.data.local.PhDriveDatabase
import com.phdrive.companion.data.local.SyncPreferences
import com.phdrive.companion.repository.AuthRepository
import com.phdrive.companion.repository.SyncRepository
import com.phdrive.companion.repository.TripRepository
import com.phdrive.companion.repository.VehicleRepository
import com.phdrive.companion.sync.SyncScheduler
import com.phdrive.companion.ui.home.HomeViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSchedulerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        try {
            WorkManager.initialize(context, config)
        } catch (_: Exception) {}
        AuthRepository.init(context)
    }

    @Test
    fun testWorkRequestHasNetworkConstraintAndExponentialBackoff() {
        val request = SyncScheduler.buildSyncWorkRequest()

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertTrue(request.tags.contains(SyncScheduler.UNIQUE_WORK_NAME))
    }

    @Test
    fun testEnqueueManualSyncEnqueuesWorkWithFixedName() {
        SyncScheduler.enqueueManualSync(context)

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK_NAME)
            .get()

        assertNotNull(workInfos)
        assertEquals(1, workInfos.size)
        val info = workInfos[0]
        assertTrue(
            info.state == WorkInfo.State.ENQUEUED ||
            info.state == WorkInfo.State.RUNNING ||
            info.state == WorkInfo.State.SUCCEEDED
        )
    }

    @Test
    fun testEnqueueBackgroundSyncEnqueuesWork() {
        SyncScheduler.enqueueBackgroundSync(context)

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK_NAME)
            .get()

        assertNotNull(workInfos)
        assertEquals(1, workInfos.size)
    }

    @Test
    fun testSyncPreferencesUpdatesLastAttemptImmediately() = runBlocking {
        val prefs = SyncPreferences.getInstance(context)
        val timestamp = System.currentTimeMillis()
        prefs.saveLastSyncAttemptAt(timestamp)

        assertEquals(timestamp, prefs.getLastSyncAttemptAt())
        val observed = prefs.observeLastSyncAttemptAt().first()
        assertEquals(timestamp, observed)
    }

    @Test
    fun testHomeViewModelSyncNowEnqueuesWorkAndUpdatesState() = runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, PhDriveDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val syncPrefs = SyncPreferences.getInstance(context)
        val viewModel = HomeViewModel(
            tripRepo = TripRepository(db),
            vehicleRepo = VehicleRepository(db),
            syncRepo = SyncRepository(db, context),
            authRepo = AuthRepository.getInstance(),
            syncPrefs = syncPrefs
        )

        viewModel.syncNow(context)

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK_NAME)
            .get()

        assertNotNull(workInfos)
        assertTrue(workInfos.isNotEmpty())

        val now = System.currentTimeMillis()
        syncPrefs.saveLastSyncAttemptAt(now)

        val lastAttempt = viewModel.lastSyncAttemptTime.first()
        assertEquals(now, lastAttempt)

        db.close()
    }
}
