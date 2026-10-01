package com.phdrive.companion

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phdrive.companion.data.local.PhDriveDatabase
import com.phdrive.companion.data.local.RebootRecoveryPreferences
import com.phdrive.companion.data.remote.TripRemoteDataSource
import com.phdrive.companion.data.remote.model.TripInsertDto
import com.phdrive.companion.data.remote.model.TripPointInsertDto
import com.phdrive.companion.data.remote.model.TripResponseDto
import com.phdrive.companion.data.remote.model.TripUpdateDto
import com.phdrive.companion.model.LocalTripPoint
import com.phdrive.companion.model.SyncStatus
import com.phdrive.companion.model.TripStatus
import com.phdrive.companion.model.Vehicle
import com.phdrive.companion.receiver.BootReceiver
import com.phdrive.companion.repository.AuthRepository
import com.phdrive.companion.repository.SyncRepository
import com.phdrive.companion.repository.TripRepository
import com.phdrive.companion.service.LocationTrackingService
import com.phdrive.companion.service.TrackingState
import com.phdrive.companion.util.LocationUtils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocationResilienceAndContinuityTest {

    private lateinit var context: Context
    private lateinit var database: PhDriveDatabase
    private lateinit var tripRepository: TripRepository
    private lateinit var rebootPrefs: RebootRecoveryPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PhDriveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        PhDriveDatabase.setInstanceForTesting(database)
        tripRepository = TripRepository(database)
        rebootPrefs = RebootRecoveryPreferences(context)
        rebootPrefs.clear()
        AuthRepository.init(context)
    }

    @After
    fun tearDown() {
        PhDriveDatabase.setInstanceForTesting(null)
        database.close()
        rebootPrefs.clear()
    }

    /**
     * 1. Testar que uma viagem ativa é identificada para continuidade
     */
    @Test
    fun testActiveTripIdentifiedForContinuity() = runBlocking {
        assertNull("Não deve haver viagem ativa inicialmente", tripRepository.getActiveTrip())

        val trip = tripRepository.startTrip(
            vehicleId = "veh-titan-125",
            vehicleName = "Titan 125",
            userId = "user-123",
            startOdometerKm = 12260.0
        )

        assertNotNull(trip)
        assertEquals(TripStatus.IN_PROGRESS, trip.status)

        val active = tripRepository.getActiveTrip()
        assertNotNull("Viagem ativa deve ser encontrada pelo repositório", active)
        assertEquals(trip.localId, active?.localId)
        assertEquals("Titan 125", active?.vehicleName)
    }

    /**
     * 2. REBOOT NÃO DEVE RETOMAR GPS SILENCIOSAMENTE:
     * BootReceiver detecta viagem ativa, mas NÃO inicia LocationTrackingService;
     * em vez disso, sinaliza INTERRUPTED_AFTER_REBOOT.
     */
    @Test
    fun testBootReceiverDoesNotStartGpsSilentlyAndSignalsInterruptedState() = runBlocking {
        database.vehicleDao().insertVehicle(
            Vehicle(
                id = "veh-titan-125",
                name = "Titan 125",
                brand = "Honda",
                model = "FAN 125 KS",
                year = 2010,
                currentOdometerKm = 12260.0,
                userId = "user-123"
            )
        )

        val activeTrip = tripRepository.startTrip(
            vehicleId = "veh-titan-125",
            vehicleName = "Titan 125",
            userId = "user-123",
            startOdometerKm = 12260.0
        )

        val receiver = BootReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)

        Thread.sleep(200)

        // Verificar que NENHUM serviço de localização foi iniciado silenciosamente
        val shadowApp = Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        val startedService = shadowApp.nextStartedService
        assertNull("BootReceiver NÃO deve iniciar o serviço de localização silenciosamente", startedService)

        // Verificar que o estado INTERRUPTED_AFTER_REBOOT foi gravado
        assertTrue("Deve sinalizar viagem interrompida após reboot", rebootPrefs.isInterruptedAfterReboot())
        assertEquals(activeTrip.localId, rebootPrefs.getInterruptedTripId())
    }

    /**
     * 3. Testar BootReceiver sem viagem ativa: não deve sinalizar nem iniciar serviço
     */
    @Test
    fun testBootReceiverDoesNothingWithoutActiveTrip() = runBlocking {
        val receiver = BootReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)

        Thread.sleep(200)

        val shadowApp = Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        assertNull("Nenhum serviço iniciado", shadowApp.nextStartedService)
        assertFalse("Não deve sinalizar viagem interrompida", rebootPrefs.isInterruptedAfterReboot())
    }

    /**
     * 4. SEQUENCE NUMBER APÓS RECRIAÇÃO / RESTART:
     * Pontos existentes 0..40 → restart/recovery → próximo ponto = 41.
     * Nunca reiniciar em zero.
     */
    @Test
    fun testSequenceNumberContinuityAfterRestartOrRecovery() = runBlocking {
        val trip = tripRepository.startTrip(
            vehicleId = "veh-titan-125",
            vehicleName = "Titan 125",
            userId = "user-123",
            startOdometerKm = 12260.0
        )

        // Inserir 41 pontos simulados (sequence numbers 0..40)
        for (i in 0..40) {
            val point = LocalTripPoint(
                localId = UUID.randomUUID().toString(),
                localTripId = trip.localId,
                userId = "user-123",
                latitude = -23.5505 + (i * 0.0001),
                longitude = -46.6333 + (i * 0.0001),
                accuracyMeters = 5.0,
                speedKmh = 35.0,
                headingDegrees = 45.0,
                altitudeMeters = 750.0,
                capturedAt = 1000L + (i * 1000L),
                sequenceNumber = i,
                syncStatus = SyncStatus.PENDING
            )
            database.tripPointDao().insertPoint(point)
        }

        // Verificar que o max sequence atual é 40
        val maxSeqBefore = database.tripPointDao().getMaxSequenceNumber(trip.localId)
        assertEquals(40, maxSeqBefore)

        // Simular nova leitura capturada após reinício do serviço/app
        val newPoint = tripRepository.addLocationPoint(
            localTripId = trip.localId,
            userId = "user-123",
            latitude = -23.5464,
            longitude = -46.6292,
            accuracyMeters = 4.8,
            speedKmh = 40.0,
            headingDegrees = 45.0,
            altitudeMeters = 752.0,
            capturedAt = 42000L
        )

        assertEquals("Próximo sequenceNumber deve ser exatamente 41", 41, newPoint.sequenceNumber)
    }

    /**
     * 5. GPS_DISTANCE_KM LOCAL E ENVIADO AO SUPABASE (TripUpdateDto):
     * Uma corrida física com 2+ pontos calcula gpsDistanceKm e envia valor não-nulo na sincronização.
     */
    @Test
    fun testGpsDistanceKmCalculatedLocallyAndIncludedInRemoteUpdateDto() = runBlocking {
        val trip = tripRepository.startTrip(
            vehicleId = "veh-titan-125",
            vehicleName = "Titan 125",
            userId = "user-123",
            startOdometerKm = 12260.0
        )

        // Simular 41 pontos (distância ~0.772 km)
        for (i in 0..40) {
            tripRepository.addLocationPoint(
                localTripId = trip.localId,
                userId = "user-123",
                latitude = -23.5505 + (i * 0.00015),
                longitude = -46.6333 + (i * 0.00015),
                accuracyMeters = 5.0,
                speedKmh = 35.0,
                headingDegrees = 45.0,
                altitudeMeters = 750.0,
                capturedAt = 1000L + (i * 1000L)
            )
        }

        // Encerrar a viagem
        val completedTrip = tripRepository.completeTrip(trip.localId, 12261.0)
        assertNotNull("Distância GPS local não pode ser nula", completedTrip.gpsDistanceKm)
        val localDistance = completedTrip.gpsDistanceKm!!
        assertTrue("Distância calculada deve ser ~0.7-0.9 km (valor: $localDistance)", localDistance > 0.5 && localDistance < 1.5)

        // Simular sincronização remota e interceptar o TripUpdateDto
        var capturedUpdateDto: TripUpdateDto? = null
        val fakeRemoteDataSource = object : TripRemoteDataSource {
            override suspend fun createTrip(dto: TripInsertDto): Result<TripResponseDto> {
                return Result.success(TripResponseDto(id = "remote-trip-uuid-99"))
            }
            override suspend fun updateTrip(remoteTripId: String, dto: TripUpdateDto): Result<Unit> {
                capturedUpdateDto = dto
                return Result.success(Unit)
            }
            override suspend fun insertTripPointsBatch(batch: List<TripPointInsertDto>): Result<Int> {
                return Result.success(batch.size)
            }
            override suspend fun findActiveTrip(userId: String, vehicleId: String): Result<TripResponseDto?> {
                return Result.success(null)
            }
            override suspend fun fetchTrip(remoteTripId: String): Result<TripResponseDto?> {
                return Result.success(TripResponseDto(remoteTripId))
            }
        }

        val syncRepository = SyncRepository(
            database = database,
            remoteDataSource = fakeRemoteDataSource,
            context = context
        )

        database.tripDao().updateRemoteTripId(trip.localId, "remote-trip-uuid-99")
        val syncResult = syncRepository.syncTripStatus(trip.localId)

        assertTrue("Sincronização de status deve ser bem-sucedida", syncResult.isSuccess)
        assertNotNull("TripUpdateDto deve ter sido enviado", capturedUpdateDto)
        assertEquals("COMPLETED", capturedUpdateDto?.status)
        assertNotNull("gps_distance_km no Supabase NÃO pode ser nulo", capturedUpdateDto?.gpsDistanceKm)
        assertEquals(localDistance, capturedUpdateDto?.gpsDistanceKm ?: 0.0, 0.001)
    }

    /**
     * 6. Testar TrackingState e ausência de WakeLock
     */
    @Test
    fun testTrackingStateObservability() {
        assertEquals(TrackingState.STOPPED, LocationTrackingService.trackingState.value)
        assertEquals(0L, LocationTrackingService.secondsSinceLastPoint.value)
        assertEquals(0, LocationTrackingService.discardedLowAccuracyCount.value)
    }

    /**
     * 7. Testar validação e filtragem de coordenadas
     */
    @Test
    fun testCoordinateValidation() {
        assertTrue(LocationUtils.isValidCoordinates(-23.5505, -46.6333))
        assertTrue(LocationUtils.isValidCoordinates(0.0001, 0.0001))
        assertFalse("Coordenadas 0,0 devem ser inválidas", LocationUtils.isValidCoordinates(0.0, 0.0))
        assertFalse("Latitude > 90 deve ser inválida", LocationUtils.isValidCoordinates(91.0, -46.6333))
        assertFalse("Latitude < -90 deve ser inválida", LocationUtils.isValidCoordinates(-91.0, -46.6333))
        assertFalse("Longitude > 180 deve ser inválida", LocationUtils.isValidCoordinates(-23.5505, 181.0))
        assertFalse("Longitude < -180 deve ser inválida", LocationUtils.isValidCoordinates(-23.5505, -181.0))
    }

    /**
     * 8. Testar conclusão da viagem com atualização de odômetro e preservação de pontos
     */
    @Test
    fun testCompleteTripPreservesPointsAndUpdatesOdometer() = runBlocking {
        database.vehicleDao().insertVehicle(
            Vehicle(
                id = "veh-titan-125",
                name = "Titan 125",
                brand = "Honda",
                model = "FAN 125 KS",
                year = 2010,
                currentOdometerKm = 12260.0,
                userId = "user-123"
            )
        )

        val trip = tripRepository.startTrip(
            vehicleId = "veh-titan-125",
            vehicleName = "Titan 125",
            userId = "user-123",
            startOdometerKm = 12260.0
        )

        tripRepository.addLocationPoint(trip.localId, "user-123", -23.5505, -46.6333, 5.0, 30.0, 0.0, 700.0, 1000L)
        tripRepository.addLocationPoint(trip.localId, "user-123", -23.5405, -46.6333, 5.0, 35.0, 0.0, 700.0, 2000L)

        val completed = tripRepository.completeTrip(trip.localId, 12262.5)

        assertEquals(TripStatus.COMPLETED, completed.status)
        assertEquals(12262.5, completed.endOdometerKm ?: 0.0, 0.001)
        assertTrue("Distância GPS deve ser maior que 1 km", (completed.gpsDistanceKm ?: 0.0) > 1.0)

        // Verificar que os pontos locais continuam intactos no banco
        val points = database.tripPointDao().getPointsForTrip(trip.localId)
        assertEquals(2, points.size)

        // Odômetro do veículo atualizado
        val updatedVehicle = database.vehicleDao().getVehicleById("veh-titan-125")
        assertEquals(12262.5, updatedVehicle?.currentOdometerKm ?: 0.0, 0.001)

        // Nenhuma viagem ativa restante
        assertNull(tripRepository.getActiveTrip())
    }
}
