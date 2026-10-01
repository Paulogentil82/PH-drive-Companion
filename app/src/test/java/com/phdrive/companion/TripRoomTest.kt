package com.phdrive.companion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phdrive.companion.data.local.PhDriveDatabase
import com.phdrive.companion.data.local.TripDao
import com.phdrive.companion.model.LocalTrip
import com.phdrive.companion.model.SyncStatus
import com.phdrive.companion.model.TripStatus
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
class TripRoomTest {

    private lateinit var db: PhDriveDatabase
    private lateinit var tripDao: TripDao
    private lateinit var repository: TripRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PhDriveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tripDao = db.tripDao()
        repository = TripRepository(db)
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    /**
     * Teste 1: Inserir LocalTrip
     */
    @Test
    fun testInsertLocalTrip() = runBlocking {
        val tripId = UUID.randomUUID().toString()
        val trip = LocalTrip(
            localId = tripId,
            userId = "user_123",
            vehicleId = "veh_01",
            vehicleName = "Titan 125",
            status = TripStatus.IN_PROGRESS,
            startOdometerKm = 12260.0,
            syncStatus = SyncStatus.LOCAL_ONLY
        )

        tripDao.insertTrip(trip)

        val retrieved = tripDao.getTripByLocalId(tripId)
        assertNotNull("LocalTrip deve ser inserido e recuperado com sucesso", retrieved)
        assertEquals(tripId, retrieved?.localId)
        assertEquals("Titan 125", retrieved?.vehicleName)
        assertEquals(12260.0, retrieved?.startOdometerKm)
        assertEquals(TripStatus.IN_PROGRESS, retrieved?.status)
        assertEquals(SyncStatus.LOCAL_ONLY, retrieved?.syncStatus)
    }

    /**
     * Teste 2: Recuperar viagem ativa
     */
    @Test
    fun testGetActiveTrip() = runBlocking {
        assertNull("Não deve haver viagem ativa inicialmente", repository.getActiveTrip())

        val trip = repository.startTrip(
            vehicleId = "veh_01",
            vehicleName = "Titan 125",
            userId = "user_123",
            startOdometerKm = 12260.0
        )

        val activeTrip = repository.getActiveTrip()
        assertNotNull("Deve recuperar a viagem ativa", activeTrip)
        assertEquals(trip.localId, activeTrip?.localId)
        assertEquals("Titan 125", activeTrip?.vehicleName)
        assertEquals(TripStatus.IN_PROGRESS, activeTrip?.status)
    }

    /**
     * Teste 3: Impedir duas viagens ativas do mesmo veículo
     */
    @Test
    fun testBlockTwoActiveTripsForSameVehicle() = runBlocking {
        repository.startTrip(
            vehicleId = "veh_titan",
            vehicleName = "Titan 125",
            userId = "user_123",
            startOdometerKm = 12260.0
        )

        try {
            repository.startTrip(
                vehicleId = "veh_titan",
                vehicleName = "Titan 125",
                userId = "user_123",
                startOdometerKm = 12260.0
            )
            fail("Deveria ter lançado IllegalStateException ao tentar iniciar segunda viagem ativa para o mesmo veículo")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Mensagem de erro deve indicar viagem em andamento para o veículo",
                e.message?.contains("Titan 125") == true
            )
        }
    }

    /**
     * Teste 4: Concluir viagem
     */
    @Test
    fun testCompleteTrip() = runBlocking {
        val trip = repository.startTrip(
            vehicleId = "veh_01",
            vehicleName = "Titan 125",
            userId = "user_123",
            startOdometerKm = 12260.0
        )

        val completed = repository.completeTrip(trip.localId, endOdometerKm = 12285.0)

        assertEquals(TripStatus.COMPLETED, completed.status)
        assertEquals(12285.0, completed.endOdometerKm)
        assertNotNull("endedAt deve ser registrado", completed.endedAt)

        val activeAfter = repository.getActiveTrip()
        assertNull("Após conclusão, não deve haver viagem ativa", activeAfter)
    }

    /**
     * Teste 5: Cancelar viagem (não deve excluir fisicamente)
     */
    @Test
    fun testCancelTrip() = runBlocking {
        val trip = repository.startTrip(
            vehicleId = "veh_01",
            vehicleName = "Titan 125",
            userId = "user_123",
            startOdometerKm = 12260.0
        )

        val cancelled = repository.cancelTrip(trip.localId)

        assertEquals(TripStatus.CANCELLED, cancelled.status)
        assertNotNull("endedAt deve ser preenchido no cancelamento", cancelled.endedAt)

        // Verifica que o registro ainda persiste fisicamente no Room
        val persisted = repository.getTripByLocalId(trip.localId)
        assertNotNull("Registro cancelado não pode ser excluído fisicamente", persisted)
        assertEquals(TripStatus.CANCELLED, persisted?.status)
    }

    /**
     * Teste 6: Validar odômetro final >= inicial
     */
    @Test
    fun testValidateEndOdometerGreaterOrEqualStart() = runBlocking {
        val trip = repository.startTrip(
            vehicleId = "veh_01",
            vehicleName = "Titan 125",
            userId = "user_123",
            startOdometerKm = 12260.0
        )

        // Odômetro final menor que o inicial deve falhar
        try {
            repository.completeTrip(trip.localId, endOdometerKm = 12250.0)
            fail("Deveria falhar se endOdometerKm < startOdometerKm")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("não pode ser menor") == true)
        }

        // Odômetro final igual ou maior deve ser aceito
        val successTrip = repository.completeTrip(trip.localId, endOdometerKm = 12260.0)
        assertEquals(TripStatus.COMPLETED, successTrip.status)
        assertEquals(12260.0, successTrip.endOdometerKm)
    }

    /**
     * Teste 7: Persistência de status e syncStatus
     */
    @Test
    fun testStatusPersistence() = runBlocking {
        val trip = repository.startTrip(
            vehicleId = "veh_01",
            vehicleName = "Titan 125",
            userId = "user_123",
            startOdometerKm = 12260.0
        )

        assertTrue(trip.syncStatus == SyncStatus.PENDING || trip.syncStatus == SyncStatus.LOCAL_ONLY)

        // Atualiza syncStatus para PENDING
        tripDao.markSyncStatus(trip.localId, SyncStatus.PENDING)
        val pendingTrip = tripDao.getTripByLocalId(trip.localId)
        assertEquals(SyncStatus.PENDING, pendingTrip?.syncStatus)

        // Atualiza syncStatus para SYNCED
        tripDao.markSyncStatus(trip.localId, SyncStatus.SYNCED)
        val syncedTrip = tripDao.getTripByLocalId(trip.localId)
        assertEquals(SyncStatus.SYNCED, syncedTrip?.syncStatus)
    }

    /**
     * Teste 8: Restaurar viagem ativa após reinicialização do app (Process Death / Restart)
     */
    @Test
    fun testRestoreActiveTripAfterAppRestart() = runBlocking {
        // Simula sessão 1: inicia viagem
        val trip = repository.startTrip(
            vehicleId = "veh_01",
            vehicleName = "Titan 125",
            userId = "user_123",
            startOdometerKm = 12260.0
        )

        // Simula "app restart": cria uma nova instância de repositório apontando para o mesmo banco Room
        val newRepositoryInstance = TripRepository(db)
        val restoredTrip = newRepositoryInstance.restoreActiveTrip()

        assertNotNull("Viagem ativa deve ser restaurada do Room após app reabrir", restoredTrip)
        assertEquals(trip.localId, restoredTrip?.localId)
        assertEquals("Titan 125", restoredTrip?.vehicleName)
        assertEquals(TripStatus.IN_PROGRESS, restoredTrip?.status)
    }
}
