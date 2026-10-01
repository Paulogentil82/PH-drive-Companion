package com.phdrive.companion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phdrive.companion.data.local.PhDriveDatabase
import com.phdrive.companion.data.remote.VehicleRemoteDataSource
import com.phdrive.companion.data.remote.model.VehicleRemoteDto
import com.phdrive.companion.model.Vehicle
import com.phdrive.companion.repository.AuthRepository
import com.phdrive.companion.repository.VehicleRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VehicleRepositoryTest {

    private lateinit var db: PhDriveDatabase
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PhDriveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AuthRepository.init(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Teste 1: Nenhum veículo demo (Fiat Strada, CG 160, Yamaha Factor) é retornado
     */
    @Test
    fun testNoDemoVehiclesReturned() = runBlocking {
        val vehicleDao = db.vehicleDao()

        // Simular se houvesse resíduo de veículos demo no banco
        vehicleDao.insertVehicle(
            Vehicle(
                id = "v1-honda-cg",
                name = "Honda CG 160 Titan",
                plate = "ABC1D23",
                model = "Motocicleta 160cc",
                currentOdometerKm = 12450.0,
                userId = "test-user"
            )
        )
        vehicleDao.insertVehicle(
            Vehicle(
                id = "v2-yamaha-factor",
                name = "Yamaha Factor 150",
                plate = "XYZ9K87",
                model = "Motocicleta 150cc",
                currentOdometerKm = 8320.0,
                userId = "test-user"
            )
        )
        vehicleDao.insertVehicle(
            Vehicle(
                id = "v3-fiat-strada",
                name = "Fiat Strada Endurance",
                plate = "PHD4E56",
                model = "Pick-up",
                currentOdometerKm = 45200.0,
                userId = "test-user"
            )
        )

        val emptyRemote = object : VehicleRemoteDataSource {
            override suspend fun getVehicles(): Result<List<VehicleRemoteDto>> = Result.success(emptyList())
        }

        val repository = VehicleRepository(
            database = db,
            remoteDataSource = emptyRemote,
            authRepository = AuthRepository.getInstance()
        )

        // Limpar demos
        repository.cleanDemoVehicles()

        val list = repository.getVehiclesForUser("test-user")
        assertTrue("Lista deve estar vazia de demos", list.isEmpty())

        val allInDb = vehicleDao.getAllVehicles()
        for (v in allInDb) {
            assertFalse(v.id == "v1-honda-cg")
            assertFalse(v.id == "v2-yamaha-factor")
            assertFalse(v.id == "v3-fiat-strada")
            assertFalse(v.name.contains("Fiat Strada"))
            assertFalse(v.name.contains("CG 160"))
            assertFalse(v.name.contains("Yamaha Factor"))
        }
    }

    /**
     * Teste 2: Repository retorna veículos reais do Supabase (Titan 125, Honda FAN 125 KS, 2010, 12260 km)
     */
    @Test
    fun testRepositoryReturnsRealVehiclesFromSupabase() = runBlocking {
        val realSupabaseVehicles = listOf(
            VehicleRemoteDto(
                id = "real-titan-125-uuid",
                userId = "user_real_paulolera",
                name = "Titan 125",
                brand = "Honda",
                model = "FAN 125 KS",
                version = "KS",
                year = 2010,
                licensePlate = null,
                fuelType = "Gasolina",
                type = "motorcycle",
                odometerKm = 12260.0,
                active = true
            )
        )

        val fakeRemote = object : VehicleRemoteDataSource {
            override suspend fun getVehicles(): Result<List<VehicleRemoteDto>> = Result.success(realSupabaseVehicles)
        }

        val repository = VehicleRepository(
            database = db,
            remoteDataSource = fakeRemote,
            authRepository = AuthRepository.getInstance()
        )

        val result = repository.refreshVehicles("user_real_paulolera")
        assertTrue("Sincronização deve ser bem sucedida", result.isSuccess)

        val vehicles = repository.getVehiclesForUser("user_real_paulolera")
        assertEquals(1, vehicles.size)

        val titan = vehicles.first()
        assertEquals("real-titan-125-uuid", titan.id)
        assertEquals("Titan 125", titan.name)
        assertEquals("Honda", titan.brand)
        assertEquals("FAN 125 KS", titan.model)
        assertEquals(2010, titan.year)
        assertEquals(12260.0, titan.currentOdometerKm, 0.001)
        assertTrue(titan.active)
        assertEquals("user_real_paulolera", titan.userId)
    }

    /**
     * Teste 3: Erro remoto NÃO injeta mocks / fallbacks fictícios
     */
    @Test
    fun testRemoteErrorDoesNotInjectMocks() = runBlocking {
        val failingRemote = object : VehicleRemoteDataSource {
            override suspend fun getVehicles(): Result<List<VehicleRemoteDto>> =
                Result.failure(IOException("Falha de conexão com o Supabase"))
        }

        val repository = VehicleRepository(
            database = db,
            remoteDataSource = failingRemote,
            authRepository = AuthRepository.getInstance()
        )

        val result = repository.refreshVehicles("some-user")
        assertTrue("Resultado deve ser falha quando o Supabase falha", result.isFailure)

        val vehicles = repository.getVehiclesForUser("some-user")
        assertTrue("Nenhum veículo falso/mock deve ser inserido em caso de falha de rede", vehicles.isEmpty())

        val allInDb = db.vehicleDao().getAllVehicles()
        assertTrue("Banco deve permanecer sem veículos demo", allInDb.isEmpty())
    }

    /**
     * Teste 4: Usuário só vê veículos do próprio userId
     */
    @Test
    fun testUserOnlySeesOwnVehicles() = runBlocking {
        val vehicleDao = db.vehicleDao()

        // Inserir veículos para User A e User B
        vehicleDao.insertVehicle(
            Vehicle(
                id = "veh-user-a-1",
                name = "Titan 125",
                brand = "Honda",
                model = "FAN 125 KS",
                year = 2010,
                currentOdometerKm = 12260.0,
                userId = "user_A",
                active = true
            )
        )
        vehicleDao.insertVehicle(
            Vehicle(
                id = "veh-user-b-1",
                name = "Outro Veículo User B",
                brand = "Marca B",
                model = "Modelo B",
                year = 2022,
                currentOdometerKm = 5000.0,
                userId = "user_B",
                active = true
            )
        )

        val repository = VehicleRepository(
            database = db,
            remoteDataSource = object : VehicleRemoteDataSource {
                override suspend fun getVehicles(): Result<List<VehicleRemoteDto>> = Result.success(emptyList())
            },
            authRepository = AuthRepository.getInstance()
        )

        val userAVehicles = repository.getVehiclesForUser("user_A")
        assertEquals(1, userAVehicles.size)
        assertEquals("veh-user-a-1", userAVehicles[0].id)
        assertEquals("user_A", userAVehicles[0].userId)

        val userBVehicles = repository.getVehiclesForUser("user_B")
        assertEquals(1, userBVehicles.size)
        assertEquals("veh-user-b-1", userBVehicles[0].id)
        assertEquals("user_B", userBVehicles[0].userId)

        // Fluxo reativo por StateFlow / Flow
        val flowVehiclesA = repository.observeVehicles("user_A").first()
        assertEquals(1, flowVehiclesA.size)
        assertEquals("Titan 125", flowVehiclesA[0].name)
    }

    /**
     * Teste 5: Veículos inativos não são exibidos na lista ativa
     */
    @Test
    fun testInactiveVehiclesFilteredOut() = runBlocking {
        val vehicleDao = db.vehicleDao()

        vehicleDao.insertVehicle(
            Vehicle(
                id = "veh-active",
                name = "Titan 125 Ativa",
                userId = "user_test",
                active = true
            )
        )
        vehicleDao.insertVehicle(
            Vehicle(
                id = "veh-inactive",
                name = "Moto Inativa Antiga",
                userId = "user_test",
                active = false
            )
        )

        val repository = VehicleRepository(
            database = db,
            remoteDataSource = object : VehicleRemoteDataSource {
                override suspend fun getVehicles(): Result<List<VehicleRemoteDto>> = Result.success(emptyList())
            },
            authRepository = AuthRepository.getInstance()
        )

        val list = repository.getVehiclesForUser("user_test")
        assertEquals(1, list.size)
        assertEquals("veh-active", list[0].id)
    }
}
