package com.phdrive.companion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phdrive.companion.data.local.PhDriveDatabase
import com.phdrive.companion.data.remote.SessionExpiredException
import com.phdrive.companion.data.remote.TripRemoteDataSource
import com.phdrive.companion.data.remote.model.TripInsertDto
import com.phdrive.companion.data.remote.model.TripPointInsertDto
import com.phdrive.companion.data.remote.model.TripResponseDto
import com.phdrive.companion.data.remote.model.TripUpdateDto
import com.phdrive.companion.model.LocalTrip
import com.phdrive.companion.model.LocalTripPoint
import com.phdrive.companion.model.SyncStatus
import com.phdrive.companion.model.TripStatus
import com.phdrive.companion.repository.SyncRepository
import com.phdrive.companion.repository.TripRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncRepositoryTest {

    private lateinit var database: PhDriveDatabase
    private lateinit var tripRepository: TripRepository
    private val json = Json { encodeDefaults = true }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PhDriveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tripRepository = TripRepository(database)
        com.phdrive.companion.repository.AuthRepository.init(context)
    }

    @After
    fun teardown() {
        database.close()
    }

    private class FakeRemoteDataSource : TripRemoteDataSource {
        var createTripCalled = false
        var lastCreatedDto: TripInsertDto? = null
        var shouldFailCreate = false

        var updateTripCalled = false
        var lastUpdateDto: TripUpdateDto? = null

        var insertedPointsBatches = mutableListOf<List<TripPointInsertDto>>()
        var pointsFailError: Throwable? = null
        var failFirstBatchOnly = false
        private var batchCount = 0

        override suspend fun createTrip(dto: TripInsertDto): Result<TripResponseDto> {
            createTripCalled = true
            lastCreatedDto = dto
            if (shouldFailCreate) {
                return Result.failure(IOException("Erro de rede ao criar viagem"))
            }
            return Result.success(
                TripResponseDto(
                    id = "remote-trip-uuid-999",
                    userId = dto.userId,
                    vehicleId = dto.vehicleId,
                    status = dto.status,
                    startedAt = dto.startedAt
                )
            )
        }

        override suspend fun findActiveTrip(userId: String, vehicleId: String): Result<TripResponseDto?> {
            return Result.success(null)
        }

        override suspend fun updateTrip(remoteTripId: String, dto: TripUpdateDto): Result<Unit> {
            updateTripCalled = true
            lastUpdateDto = dto
            return Result.success(Unit)
        }

        override suspend fun insertTripPointsBatch(batch: List<TripPointInsertDto>): Result<Int> {
            batchCount++
            if (pointsFailError != null) {
                if (!failFirstBatchOnly || batchCount == 1) {
                    return Result.failure(pointsFailError!!)
                }
            }
            insertedPointsBatches.add(batch)
            return Result.success(batch.size)
        }

        override suspend fun fetchTrip(remoteTripId: String): Result<TripResponseDto?> {
            return Result.success(TripResponseDto(remoteTripId))
        }
    }

    @Test
    fun testDtoGeneratesCorrectSnakeCaseAndIso8601() {
        val dto = TripPointInsertDto(
            tripId = "remote-123",
            userId = "user-abc",
            latitude = -23.5,
            longitude = -46.6,
            accuracyMeters = 5.0,
            speedKmh = 60.0,
            headingDegrees = 180.0,
            altitudeMeters = 800.0,
            capturedAt = "2026-09-23T22:19:53.123Z",
            sequenceNumber = 42,
            source = "MOBILE_GPS"
        )
        val jsonStr = json.encodeToString(TripPointInsertDto.serializer(), dto)
        assertTrue(jsonStr.contains("\"trip_id\":\"remote-123\""))
        assertTrue(jsonStr.contains("\"user_id\":\"user-abc\""))
        assertTrue(jsonStr.contains("\"accuracy_meters\":"))
        assertTrue(jsonStr.contains("\"speed_kmh\":"))
        assertTrue(jsonStr.contains("\"heading_degrees\":"))
        assertTrue(jsonStr.contains("\"altitude_meters\":"))
        assertTrue(jsonStr.contains("\"captured_at\":\"2026-09-23T22:19:53.123Z\""))
        assertTrue(jsonStr.contains("\"sequence_number\":42"))
        assertTrue(jsonStr.contains("\"source\":\"MOBILE_GPS\""))
    }

    @Test
    fun testBatchSizeIsTwentyAndOnlySavesSyncedOnSuccess() = runBlocking {
        val fakeRemote = FakeRemoteDataSource()
        val syncRepo = SyncRepository(database, fakeRemote)

        val trip = tripRepository.startTrip("v1", "Honda", "u1", 100.0)
        for (i in 1..48) {
            tripRepository.addLocationPoint(
                trip.localId, "u1", -23.5, -46.6, 5.0, 50.0, 90.0, 800.0, 1000L + i * 1000
            )
        }

        assertEquals(48, database.tripPointDao().countPendingPoints(trip.localId))

        val result = syncRepo.syncPendingPoints(trip.localId, batchSize = 20)
        assertTrue(result.isSuccess)
        assertEquals(48, result.getOrThrow())

        // Verifica lotes de 20
        assertEquals(3, fakeRemote.insertedPointsBatches.size)
        assertEquals(20, fakeRemote.insertedPointsBatches[0].size)
        assertEquals(20, fakeRemote.insertedPointsBatches[1].size)
        assertEquals(8, fakeRemote.insertedPointsBatches[2].size)

        // Todos ficaram SYNCED
        assertEquals(0, database.tripPointDao().countPendingPoints(trip.localId))
    }

    @Test
    fun testOneByOneIdempotentFallbackOn23505() = runBlocking {
        val customRemote = object : TripRemoteDataSource {
            val insertedBatches = mutableListOf<List<TripPointInsertDto>>()

            override suspend fun createTrip(dto: TripInsertDto) = Result.success(TripResponseDto("remote-trip-1"))
            override suspend fun findActiveTrip(userId: String, vehicleId: String) = Result.success(null)
            override suspend fun updateTrip(remoteTripId: String, dto: TripUpdateDto) = Result.success(Unit)
            override suspend fun insertTripPointsBatch(batch: List<TripPointInsertDto>): Result<Int> {
                if (batch.size > 1) {
                    // Simula erro 23505 no lote inteiro
                    return Result.failure(IOException("code: 23505 duplicate key violates unique constraint"))
                } else {
                    // No fallback 1 a 1, simula que o ponto com sequence_number 1 já existia
                    val pt = batch.first()
                    if (pt.sequenceNumber == 1) {
                        return Result.failure(IOException("code: 23505 duplicate key"))
                    }
                    insertedBatches.add(batch)
                    return Result.success(1)
                }
            }
            override suspend fun fetchTrip(remoteTripId: String): Result<TripResponseDto?> {
                return Result.success(TripResponseDto(remoteTripId))
            }
        }

        val syncRepo = SyncRepository(database, customRemote)
        val trip = tripRepository.startTrip("v1", "Honda", "u1", 100.0)
        tripRepository.addLocationPoint(trip.localId, "u1", -23.5, -46.6, 5.0, 50.0, 90.0, 800.0, 1000L)
        tripRepository.addLocationPoint(trip.localId, "u1", -23.5, -46.6, 5.0, 50.0, 90.0, 800.0, 2000L)

        val result = syncRepo.syncPendingPoints(trip.localId, batchSize = 20)
        assertTrue(result.isSuccess)
        // Ambos os pontos devem ser considerados SYNCED
        assertEquals(0, database.tripPointDao().countPendingPoints(trip.localId))
    }

    @Test
    fun testEnsureRemoteTripDoesNotDuplicateWhenRemoteIdExists() = runBlocking {
        val fakeRemote = FakeRemoteDataSource()
        val syncRepo = SyncRepository(database, fakeRemote)

        val trip = LocalTrip(
            localId = "local-123",
            remoteTripId = "already-remote-456",
            vehicleId = "v1",
            userId = "u1",
            startedAt = 1000L
        )
        database.tripDao().insertTrip(trip)

        val result = syncRepo.ensureRemoteTrip(trip)
        assertTrue(result.isSuccess)
        assertEquals("already-remote-456", result.getOrThrow())
        assertEquals(false, fakeRemote.createTripCalled)
    }

    @Test
    fun testPendingPointsBecomeSyncedAfterSuccess() = runBlocking {
        val fakeRemote = FakeRemoteDataSource()
        val syncRepo = SyncRepository(database, fakeRemote)

        val trip = tripRepository.startTrip("v1", "Honda", "u1", 100.0)
        tripRepository.addLocationPoint(trip.localId, "u1", -23.5, -46.6, 5.0, 50.0, 90.0, 800.0, 1000L)

        val report = syncRepo.syncAllPending().getOrThrow()
        assertEquals(1, report.pointsSynced)
        assertEquals(0, syncRepo.observePendingPointsCount().first())
    }

    @Test
    fun testPendingPointsIncrementRetryAndMarkFailedOnError() = runBlocking {
        val fakeRemote = FakeRemoteDataSource()
        fakeRemote.pointsFailError = IOException("HTTP 500 Internal Error")
        val syncRepo = SyncRepository(database, fakeRemote)

        val trip = tripRepository.startTrip("v1", "Honda", "u1", 100.0)
        tripRepository.addLocationPoint(trip.localId, "u1", -23.5, -46.6, 5.0, 50.0, 90.0, 800.0, 1000L)

        val result = syncRepo.syncPendingPoints(trip.localId)
        assertTrue(result.isFailure)

        val points = database.tripPointDao().getPointsForTrip(trip.localId)
        assertEquals(1, points.first().retryCount)
        assertEquals(SyncStatus.FAILED, points.first().syncStatus)
    }

    @Test
    fun testEndTripOfflinePreservesLocalData() = runBlocking {
        val trip = tripRepository.startTrip("v1", "Honda", "u1", 100.0)
        tripRepository.addLocationPoint(trip.localId, "u1", -23.5505, -46.6333, 5.0, 50.0, 90.0, 800.0, 1000L)
        tripRepository.addLocationPoint(trip.localId, "u1", -22.9068, -43.1729, 5.0, 50.0, 90.0, 800.0, 2000L)

        val completed = tripRepository.completeTrip(trip.localId, 150.0)
        assertEquals(TripStatus.COMPLETED, completed.status)
        assertTrue(completed.gpsDistanceKm > 300.0)
        assertEquals(SyncStatus.PENDING, completed.syncStatus)

        val savedTrip = database.tripDao().getTripByLocalId(trip.localId)
        assertNotNull(savedTrip)
        assertEquals(TripStatus.COMPLETED, savedTrip?.status)
    }

    @Test
    fun testSessionExpiredPreservesLocalPoints() = runBlocking {
        val fakeRemote = FakeRemoteDataSource()
        fakeRemote.pointsFailError = SessionExpiredException("Sessão expirada")
        val syncRepo = SyncRepository(database, fakeRemote)

        val trip = tripRepository.startTrip("v1", "Honda", "u1", 100.0)
        tripRepository.addLocationPoint(trip.localId, "u1", -23.5, -46.6, 5.0, 50.0, 90.0, 800.0, 1000L)

        val result = syncRepo.syncAllPending()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SessionExpiredException)

        assertEquals(1, database.tripPointDao().countPendingPoints(trip.localId))
    }

    @Test
    fun testSyncPendingPointsRespectsSequenceNumberAsc() = runBlocking {
        val fakeRemote = FakeRemoteDataSource()
        val syncRepo = SyncRepository(database, fakeRemote)

        val trip = tripRepository.startTrip("v1", "Honda", "u1", 100.0)
        val p1 = tripRepository.addLocationPoint(trip.localId, "u1", -23.1, -46.1, 5.0, 50.0, 90.0, 800.0, 1000L)
        val p2 = tripRepository.addLocationPoint(trip.localId, "u1", -23.2, -46.2, 5.0, 50.0, 90.0, 800.0, 2000L)
        val p3 = tripRepository.addLocationPoint(trip.localId, "u1", -23.3, -46.3, 5.0, 50.0, 90.0, 800.0, 3000L)

        syncRepo.syncPendingPoints(trip.localId, batchSize = 10)

        val batch = fakeRemote.insertedPointsBatches.first()
        assertEquals(0, batch[0].sequenceNumber)
        assertEquals(1, batch[1].sequenceNumber)
        assertEquals(2, batch[2].sequenceNumber)
    }

    @Test
    fun testIdempotencyRemoteSuccessLocalFail() = runBlocking {
        val customRemote = object : TripRemoteDataSource {
            override suspend fun createTrip(dto: TripInsertDto) = Result.success(TripResponseDto("remote-1"))
            override suspend fun findActiveTrip(userId: String, vehicleId: String) = Result.success(null)
            override suspend fun updateTrip(remoteTripId: String, dto: TripUpdateDto) = Result.success(Unit)
            override suspend fun insertTripPointsBatch(batch: List<TripPointInsertDto>): Result<Int> {
                return Result.failure(IOException("code: 23505 duplicate key"))
            }
            override suspend fun fetchTrip(remoteTripId: String): Result<TripResponseDto?> {
                return Result.success(TripResponseDto(remoteTripId))
            }
        }
        val syncRepo = SyncRepository(database, customRemote)
        val trip = tripRepository.startTrip("v1", "Honda", "u1", 100.0)
        tripRepository.addLocationPoint(trip.localId, "u1", -23.5, -46.6, 5.0, 50.0, 90.0, 800.0, 1000L)
        
        val result = syncRepo.syncPendingPoints(trip.localId)
        assertTrue(result.isSuccess)
        assertEquals(0, database.tripPointDao().countPendingPoints(trip.localId))
    }

    @Test
    fun testMixedSyncStates() = runBlocking {
        val fakeRemote = FakeRemoteDataSource()
        val syncRepo = SyncRepository(database, fakeRemote)
        val trip = tripRepository.startTrip("v1", "Honda", "u1", 100.0)
        
        val p1 = tripRepository.addLocationPoint(trip.localId, "u1", -23.5, -46.6, 5.0, 50.0, 90.0, 800.0, 1000L)
        database.tripPointDao().markPointsSynced(listOf(p1.localId))
        val p2 = tripRepository.addLocationPoint(trip.localId, "u1", -23.5, -46.6, 5.0, 50.0, 90.0, 800.0, 2000L)
        
        syncRepo.syncPendingPoints(trip.localId)
        
        assertEquals(1, fakeRemote.insertedPointsBatches.size)
        assertEquals(1, fakeRemote.insertedPointsBatches[0].size)
        assertEquals(1, fakeRemote.insertedPointsBatches[0][0].sequenceNumber)
    }

    @Test
    fun testMultipleConsecutiveSyncRetries() = runBlocking {
        val fakeRemote = FakeRemoteDataSource()
        val syncRepo = SyncRepository(database, fakeRemote)
        val trip = tripRepository.startTrip("v1", "Honda", "u1", 100.0)
        tripRepository.addLocationPoint(trip.localId, "u1", -23.5, -46.6, 5.0, 50.0, 90.0, 800.0, 1000L)
        
        repeat(5) {
            syncRepo.syncPendingPoints(trip.localId)
        }
        
        assertEquals(1, fakeRemote.insertedPointsBatches.size)
        assertEquals(0, database.tripPointDao().countPendingPoints(trip.localId))
    }

    @Test
    fun testReconciliationOfInconsistentSyncedTrips() = runBlocking {
        val customRemote = object : TripRemoteDataSource {
            override suspend fun createTrip(dto: TripInsertDto) = Result.success(TripResponseDto("new-remote-id"))
            override suspend fun findActiveTrip(userId: String, vehicleId: String) = Result.success(null)
            override suspend fun updateTrip(remoteTripId: String, dto: TripUpdateDto) = Result.success(Unit)
            override suspend fun insertTripPointsBatch(batch: List<TripPointInsertDto>) = Result.success(batch.size)
            override suspend fun fetchTrip(remoteTripId: String): Result<TripResponseDto?> {
                if (remoteTripId == "old-remote-id") {
                    return Result.success(null)
                }
                return Result.success(TripResponseDto(remoteTripId))
            }
        }

        val syncRepo = SyncRepository(database, customRemote)

        val trip = LocalTrip(
            localId = "local-1",
            remoteTripId = "old-remote-id",
            vehicleId = "v1",
            userId = "u1",
            status = TripStatus.COMPLETED,
            syncStatus = SyncStatus.SYNCED
        )
        database.tripDao().insertTrip(trip)

        val point = LocalTripPoint(
            localId = "pt-1",
            localTripId = "local-1",
            userId = "u1",
            latitude = -23.5,
            longitude = -46.6,
            sequenceNumber = 0,
            syncStatus = SyncStatus.SYNCED
        )
        database.tripPointDao().insertPoints(listOf(point))

        val reportResult = syncRepo.syncAllPending()
        assertTrue(reportResult.isSuccess)

        val updatedTrip = database.tripDao().getTripByLocalId("local-1")
        assertNotNull(updatedTrip)
        assertEquals("new-remote-id", updatedTrip?.remoteTripId)
        assertEquals(SyncStatus.SYNCED, updatedTrip?.syncStatus)

        val updatedPoint = database.tripPointDao().getPointByLocalId("pt-1")
        assertNotNull(updatedPoint)
        assertEquals(SyncStatus.SYNCED, updatedPoint?.syncStatus)
    }
}
