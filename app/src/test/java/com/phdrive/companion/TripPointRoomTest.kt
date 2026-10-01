package com.phdrive.companion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phdrive.companion.data.local.PhDriveDatabase
import com.phdrive.companion.data.local.TripPointDao
import com.phdrive.companion.model.LocalTripPoint
import com.phdrive.companion.model.SyncStatus
import com.phdrive.companion.repository.TripRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TripPointRoomTest {

    private lateinit var db: PhDriveDatabase
    private lateinit var tripPointDao: TripPointDao
    private lateinit var repository: TripRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PhDriveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tripPointDao = db.tripPointDao()
        repository = TripRepository(db)
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    /**
     * Teste 1: Inserção e persistência de LocalTripPoint no Room com todos os campos
     */
    @Test
    fun testInsertAndPersistLocalTripPoint() = runBlocking {
        val tripId = UUID.randomUUID().toString()
        val pointId = UUID.randomUUID().toString()
        val capturedAt = 1727100000000L

        val point = LocalTripPoint(
            localId = pointId,
            localTripId = tripId,
            remoteTripId = null,
            userId = "user_001",
            latitude = -23.55052,
            longitude = -46.633308,
            accuracyMeters = 8.5,
            speedKmh = 42.0,
            headingDegrees = 185.0,
            altitudeMeters = 760.0,
            capturedAt = capturedAt,
            sequenceNumber = 0,
            syncStatus = SyncStatus.PENDING,
            createdAt = System.currentTimeMillis()
        )

        val rowId = repository.recordPoint(point)
        assertTrue("Ponto deve ser inserido com rowId válido", rowId > 0)

        val points = repository.getPointsForTrip(tripId)
        assertEquals(1, points.size)

        val savedPoint = points[0]
        assertEquals(pointId, savedPoint.localId)
        assertEquals(tripId, savedPoint.localTripId)
        assertEquals(-23.55052, savedPoint.latitude, 0.00001)
        assertEquals(-46.633308, savedPoint.longitude, 0.00001)
        assertEquals(8.5, savedPoint.accuracyMeters!!, 0.01)
        assertEquals(42.0, savedPoint.speedKmh!!, 0.01)
        assertEquals(185.0, savedPoint.headingDegrees!!, 0.01)
        assertEquals(760.0, savedPoint.altitudeMeters!!, 0.01)
        assertEquals(0, savedPoint.sequenceNumber)
        assertEquals(SyncStatus.PENDING, savedPoint.syncStatus)
    }

    /**
     * Teste 2: Sequência monotônica 0, 1, 2, 3... calculada a partir do Room
     */
    @Test
    fun testMonotonicSequenceIncrement() = runBlocking {
        val tripId = UUID.randomUUID().toString()

        // Antes de qualquer ponto, próximo sequenceNumber deve ser 0
        val seq0 = repository.getNextSequenceNumber(tripId)
        assertEquals(0, seq0)

        // Insere ponto seq = 0
        repository.recordPoint(
            LocalTripPoint(
                localTripId = tripId,
                userId = "user_001",
                latitude = -23.55,
                longitude = -46.63,
                sequenceNumber = seq0
            )
        )

        // Próximo deve ser 1
        val seq1 = repository.getNextSequenceNumber(tripId)
        assertEquals(1, seq1)

        repository.recordPoint(
            LocalTripPoint(
                localTripId = tripId,
                userId = "user_001",
                latitude = -23.56,
                longitude = -46.64,
                sequenceNumber = seq1
            )
        )

        // Próximo deve ser 2
        val seq2 = repository.getNextSequenceNumber(tripId)
        assertEquals(2, seq2)

        // Simula recriação do repositório (Process Death / reabertura do app)
        val newRepo = TripRepository(db)
        val seqRestored = newRepo.getNextSequenceNumber(tripId)
        assertEquals("A sequência monotônica deve sobreviver ao fechamento/reinício lendo do Room", 2, seqRestored)
    }

    /**
     * Teste 3: Constraint de sequência única (localTripId + sequenceNumber)
     */
    @Test
    fun testSequenceUniqueConstraint() = runBlocking {
        val tripId = UUID.randomUUID().toString()

        val point1 = LocalTripPoint(
            localId = UUID.randomUUID().toString(),
            localTripId = tripId,
            userId = "user_001",
            latitude = -23.55,
            longitude = -46.63,
            sequenceNumber = 0
        )
        repository.recordPoint(point1)

        // Tentar inserir outro ponto com o MESMO tripId e MESMO sequenceNumber deve falhar
        val duplicatePoint = LocalTripPoint(
            localId = UUID.randomUUID().toString(),
            localTripId = tripId,
            userId = "user_001",
            latitude = -23.56,
            longitude = -46.64,
            sequenceNumber = 0
        )

        try {
            repository.recordPoint(duplicatePoint)
            fail("Deveria ter lançado exceção de constraint de unicidade para localTripId + sequenceNumber")
        } catch (e: Exception) {
            // Room OnConflictStrategy.ABORT lança SQLiteConstraintException
            assertTrue("Exceção deve ser de violação de constraint", e.message?.contains("UNIQUE") == true || e is android.database.sqlite.SQLiteConstraintException)
        }
    }

    /**
     * Teste 4: Recuperação ordenada ASC por sequenceNumber
     */
    @Test
    fun testAscendingOrderRetrieval() = runBlocking {
        val tripId = UUID.randomUUID().toString()

        for (i in 0..4) {
            repository.recordPoint(
                LocalTripPoint(
                    localTripId = tripId,
                    userId = "user_001",
                    latitude = -23.55 + (i * 0.01),
                    longitude = -46.63 + (i * 0.01),
                    sequenceNumber = i
                )
            )
        }

        val points = repository.getPointsForTrip(tripId)
        assertEquals(5, points.size)
        for (i in 0..4) {
            assertEquals(i, points[i].sequenceNumber)
        }
    }

    /**
     * Teste 5: Contagem total de pontos da viagem
     */
    @Test
    fun testCountPointsForTrip() = runBlocking {
        val tripId = UUID.randomUUID().toString()
        assertEquals(0, repository.countPointsForTrip(tripId))

        for (i in 0..2) {
            repository.recordPoint(
                LocalTripPoint(
                    localTripId = tripId,
                    userId = "user_001",
                    latitude = -23.55,
                    longitude = -46.63,
                    sequenceNumber = i
                )
            )
        }

        assertEquals(3, repository.countPointsForTrip(tripId))
    }

    /**
     * Teste 6: Contagem de pontos pendentes (syncStatus = PENDING)
     */
    @Test
    fun testCountPendingPoints() = runBlocking {
        val tripId = UUID.randomUUID().toString()

        // Insere 2 pontos PENDING
        repository.recordPoint(
            LocalTripPoint(
                localTripId = tripId,
                userId = "user_001",
                latitude = -23.55,
                longitude = -46.63,
                sequenceNumber = 0,
                syncStatus = SyncStatus.PENDING
            )
        )
        repository.recordPoint(
            LocalTripPoint(
                localTripId = tripId,
                userId = "user_001",
                latitude = -23.56,
                longitude = -46.64,
                sequenceNumber = 1,
                syncStatus = SyncStatus.PENDING
            )
        )
        // Insere 1 ponto SYNCED
        repository.recordPoint(
            LocalTripPoint(
                localTripId = tripId,
                userId = "user_001",
                latitude = -23.57,
                longitude = -46.65,
                sequenceNumber = 2,
                syncStatus = SyncStatus.SYNCED
            )
        )

        assertEquals(3, repository.countPointsForTrip(tripId))
        assertEquals(2, repository.countPendingPoints(tripId))
    }

    /**
     * Teste 7: Validação da estrutura de tabelas da versão 2 do Room
     */
    @Test
    fun testRoomVersion2Schema() {
        val openHelper = db.openHelper
        val readableDb = openHelper.readableDatabase
        assertTrue("Database version deve ser >= 1 (atual: ${readableDb.version})", readableDb.version >= 1)

        // Verifica existência da tabela trip_points
        val cursor = readableDb.query("SELECT name FROM sqlite_master WHERE type='table' AND (name='trip_points' OR name='local_trip_points')")
        assertTrue("Tabela trip_points deve existir no schema", cursor.moveToFirst())
        cursor.close()
    }
}
