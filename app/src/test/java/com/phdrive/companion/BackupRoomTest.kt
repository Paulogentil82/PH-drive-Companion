package com.phdrive.companion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phdrive.companion.data.backup.BackupEnvelope
import com.phdrive.companion.data.backup.BackupTrip
import com.phdrive.companion.data.backup.BackupTripPoint
import com.phdrive.companion.data.local.PhDriveDatabase
import com.phdrive.companion.data.local.TripDao
import com.phdrive.companion.data.local.TripPointDao
import com.phdrive.companion.model.LocalTrip
import com.phdrive.companion.model.LocalTripPoint
import com.phdrive.companion.model.SyncStatus
import com.phdrive.companion.model.TripStatus
import com.phdrive.companion.repository.BackupRepository
import com.phdrive.companion.repository.BackupValidationResult
import kotlinx.coroutines.runBlocking
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupRoomTest {

    private lateinit var db: PhDriveDatabase
    private lateinit var tripDao: TripDao
    private lateinit var pointDao: TripPointDao
    private lateinit var backupRepository: BackupRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PhDriveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tripDao = db.tripDao()
        pointDao = db.tripPointDao()
        backupRepository = BackupRepository(db)
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    /**
     * 1. Exportar envelope válido contendo trips e pontos, sem dados sensíveis
     */
    @Test
    fun testExportValidEnvelope() = runBlocking {
        val tripId = UUID.randomUUID().toString()
        val trip = LocalTrip(
            localId = tripId,
            vehicleId = "veh_titan",
            vehicleName = "Titan 150",
            userId = "user_abc",
            status = TripStatus.IN_PROGRESS,
            startedAt = 1000L,
            syncStatus = SyncStatus.PENDING
        )
        tripDao.insertTrip(trip)

        val pt1 = LocalTripPoint(
            localId = UUID.randomUUID().toString(),
            localTripId = tripId,
            userId = "user_abc",
            latitude = -23.5505,
            longitude = -46.6333,
            sequenceNumber = 0,
            syncStatus = SyncStatus.PENDING
        )
        val pt2 = LocalTripPoint(
            localId = UUID.randomUUID().toString(),
            localTripId = tripId,
            userId = "user_abc",
            latitude = -23.5510,
            longitude = -46.6340,
            sequenceNumber = 1,
            syncStatus = SyncStatus.PENDING
        )
        pointDao.insertPoint(pt1)
        pointDao.insertPoint(pt2)

        val jsonString = backupRepository.exportBackupJson()
        assertTrue(jsonString.isNotBlank())

        val validation = backupRepository.validateBackupJson(jsonString)
        assertTrue(validation is BackupValidationResult.Valid)

        val envelope = (validation as BackupValidationResult.Valid).envelope
        assertEquals("ph-drive-companion", envelope.app)
        assertEquals(1, envelope.schemaVersion)
        assertEquals(1, envelope.trips.size)
        assertEquals(2, envelope.tripPoints.size)
        assertEquals("veh_titan", envelope.trips[0].vehicleId)

        // Valida que não contém secrets ou tokens
        val lower = jsonString.lowercase()
        assertTrue(!lower.contains("supabase_key"))
        assertTrue(!lower.contains("refresh_token"))
        assertTrue(!lower.contains("access_token"))
        assertTrue(!lower.contains("password"))
    }

    /**
     * 2. Importar envelope válido restaura viagens e pontos no Room
     */
    @Test
    fun testImportValidEnvelope() = runBlocking {
        val tripId = "trip_imported_1"
        val envelope = BackupEnvelope(
            app = "ph-drive-companion",
            schemaVersion = 1,
            exportedAt = "2026-09-24T13:00:00Z",
            trips = listOf(
                BackupTrip(
                    localId = tripId,
                    vehicleId = "veh_01",
                    vehicleName = "Biz 125",
                    userId = "user_123",
                    status = "COMPLETED",
                    startedAt = 1000L,
                    endedAt = 2000L,
                    startOdometerKm = 100.0,
                    endOdometerKm = 120.0,
                    gpsDistanceKm = 20.0,
                    syncStatus = "PENDING",
                    createdAt = 1000L,
                    updatedAt = 2000L
                )
            ),
            tripPoints = listOf(
                BackupTripPoint(
                    localId = "pt_1",
                    localTripId = tripId,
                    userId = "user_123",
                    latitude = -23.55,
                    longitude = -46.63,
                    capturedAt = 1100L,
                    sequenceNumber = 0,
                    syncStatus = "PENDING",
                    createdAt = 1100L
                ),
                BackupTripPoint(
                    localId = "pt_2",
                    localTripId = tripId,
                    userId = "user_123",
                    latitude = -23.56,
                    longitude = -46.64,
                    capturedAt = 1200L,
                    sequenceNumber = 1,
                    syncStatus = "PENDING",
                    createdAt = 1200L
                )
            )
        )

        val summary = backupRepository.importBackup(envelope)
        assertEquals(1, summary.tripsImported)
        assertEquals(2, summary.pointsImported)

        val restoredTrip = tripDao.getTripByLocalId(tripId)
        assertNotNull(restoredTrip)
        assertEquals("Biz 125", restoredTrip?.vehicleName)
        assertEquals(TripStatus.COMPLETED, restoredTrip?.status)

        val restoredPoints = pointDao.getPointsForTrip(tripId)
        assertEquals(2, restoredPoints.size)
        assertEquals(0, restoredPoints[0].sequenceNumber)
        assertEquals(1, restoredPoints[1].sequenceNumber)
    }

    /**
     * 3. Rejeitar app incorreto no backup
     */
    @Test
    fun testRejectIncorrectApp() {
        val json = """
            {
              "app": "wrong-app",
              "schemaVersion": 1,
              "exportedAt": "2026-09-24T13:00:00Z",
              "trips": [],
              "tripPoints": []
            }
        """.trimIndent()

        val validation = backupRepository.validateBackupJson(json)
        assertTrue("Deve rejeitar app incompatível", validation is BackupValidationResult.Invalid)
        val reason = (validation as BackupValidationResult.Invalid).reason
        assertTrue(reason.contains("Aplicativo incompatível") || reason.contains("wrong-app"))
    }

    /**
     * 4. Rejeitar schemaVersion incompatível
     */
    @Test
    fun testRejectIncompatibleSchemaVersion() {
        val json = """
            {
              "app": "ph-drive-companion",
              "schemaVersion": 99,
              "exportedAt": "2026-09-24T13:00:00Z",
              "trips": [],
              "tripPoints": []
            }
        """.trimIndent()

        val validation = backupRepository.validateBackupJson(json)
        assertTrue("Deve rejeitar schema version não suportada", validation is BackupValidationResult.Invalid)
        val reason = (validation as BackupValidationResult.Invalid).reason
        assertTrue(reason.contains("Versão de schema não suportada"))
    }

    /**
     * 5. Importação idempotente: importar o mesmo backup duas vezes não duplica dados
     */
    @Test
    fun testImportTwiceWithoutDuplication() = runBlocking {
        val tripId = "trip_idempotent_1"
        val envelope = BackupEnvelope(
            app = "ph-drive-companion",
            schemaVersion = 1,
            exportedAt = "2026-09-24T13:00:00Z",
            trips = listOf(
                BackupTrip(
                    localId = tripId,
                    vehicleId = "veh_01",
                    userId = "user_123",
                    status = "COMPLETED",
                    startedAt = 1000L,
                    syncStatus = "PENDING",
                    createdAt = 1000L,
                    updatedAt = 1000L
                )
            ),
            tripPoints = listOf(
                BackupTripPoint(
                    localId = "pt_idem_1",
                    localTripId = tripId,
                    userId = "user_123",
                    latitude = -23.55,
                    longitude = -46.63,
                    capturedAt = 1000L,
                    sequenceNumber = 0,
                    syncStatus = "PENDING",
                    createdAt = 1000L
                )
            )
        )

        val summary1 = backupRepository.importBackup(envelope)
        assertEquals(1, summary1.tripsImported)
        assertEquals(1, summary1.pointsImported)

        // Segunda importação com os mesmos dados
        val summary2 = backupRepository.importBackup(envelope)
        assertEquals(0, summary2.tripsImported)
        assertEquals(0, summary2.pointsImported)
        assertEquals(1, summary2.pointsSkippedOrMerged)

        assertEquals(1, tripDao.countAllTrips())
        assertEquals(1, pointDao.countTotalPoints())
    }

    /**
     * 6. Preservar remoteTripId
     */
    @Test
    fun testPreserveRemoteTripId() = runBlocking {
        val tripId = "trip_with_remote"
        val existingTrip = LocalTrip(
            localId = tripId,
            remoteTripId = "rem_uuid_999",
            vehicleId = "veh_01",
            userId = "user_123",
            status = TripStatus.COMPLETED,
            startedAt = 1000L,
            syncStatus = SyncStatus.SYNCED,
            updatedAt = 3000L
        )
        tripDao.insertTrip(existingTrip)

        // Backup que possui a mesma viagem porém sem remoteTripId (ou com remoteTripId antigo)
        val envelope = BackupEnvelope(
            app = "ph-drive-companion",
            schemaVersion = 1,
            exportedAt = "2026-09-24T13:00:00Z",
            trips = listOf(
                BackupTrip(
                    localId = tripId,
                    remoteTripId = null,
                    vehicleId = "veh_01",
                    userId = "user_123",
                    status = "COMPLETED",
                    startedAt = 1000L,
                    syncStatus = "PENDING",
                    createdAt = 1000L,
                    updatedAt = 2000L
                )
            )
        )

        backupRepository.importBackup(envelope)

        val currentTrip = tripDao.getTripByLocalId(tripId)
        assertEquals("remoteTripId deve ser preservado para evitar viagens remotas duplicadas", "rem_uuid_999", currentTrip?.remoteTripId)
        assertEquals("syncStatus SYNCED existente deve ser preservado", SyncStatus.SYNCED, currentTrip?.syncStatus)
    }

    /**
     * 7. Preservar sequenceNumber dos pontos
     */
    @Test
    fun testPreserveSequenceNumber() = runBlocking {
        val tripId = "trip_seq"
        val envelope = BackupEnvelope(
            app = "ph-drive-companion",
            schemaVersion = 1,
            exportedAt = "2026-09-24T13:00:00Z",
            trips = listOf(
                BackupTrip(
                    localId = tripId,
                    vehicleId = "veh_01",
                    userId = "user_123",
                    status = "IN_PROGRESS",
                    startedAt = 1000L,
                    syncStatus = "PENDING",
                    createdAt = 1000L,
                    updatedAt = 1000L
                )
            ),
            tripPoints = listOf(
                BackupTripPoint(
                    localId = "pt_seq_5",
                    localTripId = tripId,
                    userId = "user_123",
                    latitude = -23.55,
                    longitude = -46.63,
                    capturedAt = 1005L,
                    sequenceNumber = 5,
                    syncStatus = "PENDING",
                    createdAt = 1005L
                ),
                BackupTripPoint(
                    localId = "pt_seq_6",
                    localTripId = tripId,
                    userId = "user_123",
                    latitude = -23.56,
                    longitude = -46.64,
                    capturedAt = 1006L,
                    sequenceNumber = 6,
                    syncStatus = "PENDING",
                    createdAt = 1006L
                )
            )
        )

        backupRepository.importBackup(envelope)

        val points = pointDao.getPointsForTrip(tripId)
        assertEquals(2, points.size)
        assertEquals(5, points[0].sequenceNumber)
        assertEquals(6, points[1].sequenceNumber)
    }

    /**
     * 8. Preservar syncStatus PENDING dos registros restaurados
     */
    @Test
    fun testPreservePendingSyncStatus() = runBlocking {
        val tripId = "trip_pending_test"
        val envelope = BackupEnvelope(
            app = "ph-drive-companion",
            schemaVersion = 1,
            exportedAt = "2026-09-24T13:00:00Z",
            trips = listOf(
                BackupTrip(
                    localId = tripId,
                    vehicleId = "veh_01",
                    userId = "user_123",
                    status = "COMPLETED",
                    startedAt = 1000L,
                    syncStatus = "PENDING",
                    createdAt = 1000L,
                    updatedAt = 1000L
                )
            ),
            tripPoints = listOf(
                BackupTripPoint(
                    localId = "pt_pending_1",
                    localTripId = tripId,
                    userId = "user_123",
                    latitude = -23.55,
                    longitude = -46.63,
                    capturedAt = 1000L,
                    sequenceNumber = 0,
                    syncStatus = "PENDING",
                    createdAt = 1000L
                )
            )
        )

        backupRepository.importBackup(envelope)

        val trip = tripDao.getTripByLocalId(tripId)
        assertEquals(SyncStatus.PENDING, trip?.syncStatus)

        val pt = pointDao.getPointsForTrip(tripId)[0]
        assertEquals(SyncStatus.PENDING, pt.syncStatus)
    }

    /**
     * 9. Transação e Rollback em falha na importação
     */
    @Test
    fun testRollbackOnInvalidImportTransaction() = runBlocking {
        val tripId = "trip_valid_before_fail"
        val envelopeWithFailure = BackupEnvelope(
            app = "ph-drive-companion",
            schemaVersion = 1,
            exportedAt = "2026-09-24T13:00:00Z",
            trips = listOf(
                BackupTrip(
                    localId = tripId,
                    vehicleId = "veh_01",
                    userId = "user_123",
                    status = "IN_PROGRESS",
                    startedAt = 1000L,
                    syncStatus = "PENDING",
                    createdAt = 1000L,
                    updatedAt = 1000L
                )
            ),
            tripPoints = listOf(
                // Ponto válido
                BackupTripPoint(
                    localId = "pt_valid",
                    localTripId = tripId,
                    userId = "user_123",
                    latitude = -23.55,
                    longitude = -46.63,
                    capturedAt = 1000L,
                    sequenceNumber = 0,
                    syncStatus = "INVALID_STATUS_TRIGGER_EXCEPTION",
                    createdAt = 1000L
                )
            )
        )

        try {
            backupRepository.importBackup(envelopeWithFailure)
            org.junit.Assert.fail("Deveria lançar IllegalArgumentException para status inválido na transação")
        } catch (e: Exception) {
            // Esperado falhar e realizar rollback
        }

        // Verifica que a viagem NÃO persistiu por causa do rollback da transação Room
        val persistedTrip = tripDao.getTripByLocalId(tripId)
        org.junit.Assert.assertNull("Transação deve realizar rollback de todas as alterações caso ocorra falha", persistedTrip)
        assertEquals(0, tripDao.countAllTrips())
        assertEquals(0, pointDao.countTotalPoints())
    }

    /**
     * 10. Não sobrescrever dado mais novo local com backup antigo
     */
    @Test
    fun testDoNotOverwriteNewerDataWithOlderBackup() = runBlocking {
        val tripId = "trip_newer_test"
        val existingNewerTrip = LocalTrip(
            localId = tripId,
            vehicleId = "veh_01",
            vehicleName = "Titan Recente",
            userId = "user_123",
            status = TripStatus.COMPLETED,
            startedAt = 1000L,
            endedAt = 5000L,
            endOdometerKm = 12000.0,
            syncStatus = SyncStatus.SYNCED,
            createdAt = 1000L,
            updatedAt = 5000L // Atualizado no tempo 5000
        )
        tripDao.insertTrip(existingNewerTrip)

        // Backup antigo (tempo 2000)
        val olderBackupEnvelope = BackupEnvelope(
            app = "ph-drive-companion",
            schemaVersion = 1,
            exportedAt = "2026-09-24T12:00:00Z",
            trips = listOf(
                BackupTrip(
                    localId = tripId,
                    vehicleId = "veh_01",
                    vehicleName = "Titan Antiga",
                    userId = "user_123",
                    status = "IN_PROGRESS",
                    startedAt = 1000L,
                    endedAt = null,
                    endOdometerKm = null,
                    syncStatus = "PENDING",
                    createdAt = 1000L,
                    updatedAt = 2000L // Versão antiga
                )
            )
        )

        backupRepository.importBackup(olderBackupEnvelope)

        val tripAfter = tripDao.getTripByLocalId(tripId)
        assertEquals("Deve manter a versão mais recente", "Titan Recente", tripAfter?.vehicleName)
        assertEquals("Deve manter status COMPLETED mais recente", TripStatus.COMPLETED, tripAfter?.status)
        assertEquals(5000L, tripAfter?.updatedAt)
    }
}
