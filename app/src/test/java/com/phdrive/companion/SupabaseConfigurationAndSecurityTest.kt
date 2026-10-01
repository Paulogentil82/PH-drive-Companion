package com.phdrive.companion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phdrive.companion.data.local.PhDriveDatabase
import com.phdrive.companion.data.remote.SessionExpiredException
import com.phdrive.companion.data.remote.SupabaseClientProvider
import com.phdrive.companion.data.remote.VehicleRemoteDataSource
import com.phdrive.companion.data.remote.model.VehicleRemoteDto
import com.phdrive.companion.model.Vehicle
import com.phdrive.companion.repository.AuthRepository
import com.phdrive.companion.repository.VehicleRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SupabaseConfigurationAndSecurityTest {

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

    @Test
    fun testApikeyHeaderUsesPublishableKey() {
        val headers = SupabaseClientProvider.buildHeaders("sample_user_access_token")
        assertEquals(
            "sb_publishable_8YDhT9GsNvgxk-wcEOY1LA_ecTh7XRx",
            headers["apikey"]
        )
        assertEquals(BuildConfig.SUPABASE_PUBLISHABLE_KEY, headers["apikey"])
    }

    @Test
    fun testAuthorizationHeaderUsesAccessToken() {
        val userToken = "eyJhGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.user_token_sample"
        val headers = SupabaseClientProvider.buildHeaders(userToken)
        assertEquals("Bearer $userToken", headers["Authorization"])
        assertEquals("application/json", headers["Accept"])
    }

    @Test
    fun testPublishableKeyNeverUsedAsBearerToken() {
        // Mesmo sem access token de sessão, publishable key NUNCA deve ser enviada como Bearer
        val headersWithNull = SupabaseClientProvider.buildHeaders(null)
        assertNull(headersWithNull["Authorization"])
        assertFalse(headersWithNull["Authorization"] == "Bearer ${SupabaseClientProvider.publishableKey}")

        val headersWithBlank = SupabaseClientProvider.buildHeaders("   ")
        assertNull(headersWithBlank["Authorization"])
        assertFalse(headersWithBlank["Authorization"] == "Bearer ${SupabaseClientProvider.publishableKey}")
    }

    @Test
    fun testAccessTokenNeverUsedAsApiKey() {
        val userToken = "my_user_jwt_access_token_12345"
        val headers = SupabaseClientProvider.buildHeaders(userToken)
        assertFalse("Access token nunca deve ser usado como apikey", headers["apikey"] == userToken)
        assertEquals(BuildConfig.SUPABASE_PUBLISHABLE_KEY, headers["apikey"])
    }

    @Test
    fun testNoServiceRoleExistsInApk() {
        assertFalse(
            "Nenhum service_role deve existir na chave do BuildConfig",
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.contains("service_role", ignoreCase = true)
        )
        assertFalse(
            "Nenhum service_role deve existir na URL do Supabase",
            BuildConfig.SUPABASE_URL.contains("service_role", ignoreCase = true)
        )
    }

    @Test
    fun testNoSbSecretExistsInProject() {
        assertFalse(
            "Nenhum sb_secret deve existir no projeto Android",
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.startsWith("sb_secret")
        )
        assertTrue(
            "A chave deve iniciar com sb_publishable_",
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.startsWith("sb_publishable_")
        )
    }

    @Test
    fun testSupabaseUrlIsCorrect() {
        assertEquals("https://rzuuxbrkzuphcnwctutg.supabase.co", SupabaseClientProvider.baseUrl)
        assertEquals("https://rzuuxbrkzuphcnwctutg.supabase.co", BuildConfig.SUPABASE_URL)
    }

    @Test
    fun testBuildConfigValuesAreRealAndNotFallbacksOrPlaceholders() {
        assertEquals("https://rzuuxbrkzuphcnwctutg.supabase.co", BuildConfig.SUPABASE_URL)
        assertTrue(
            "Chave deve começar com sb_publishable_",
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.startsWith("sb_publishable_")
        )
        assertFalse(
            "Chave não deve conter 'dummy'",
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.contains("dummy", ignoreCase = true)
        )
        assertFalse(
            "Chave não deve conter 'eyJhbGci'",
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.contains("eyJhbGci", ignoreCase = true)
        )
        assertFalse(
            "Chave não deve conter 'SUPABASE_'",
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.contains("SUPABASE_", ignoreCase = true)
        )
        assertFalse(
            "Chave não deve conter 'placeholder'",
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.contains("placeholder", ignoreCase = true)
        )
        assertEquals("sb_publishable_8YDhT9GsNvgxk-wcEOY1LA_ecTh7XRx", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
    }

    @Test
    fun testVehicleQueryMapsTitan125Correctly() {
        val jsonPayload = """
            [
              {
                "id": "real-titan-uuid-987",
                "user_id": "auth-user-uuid-111",
                "name": "Titan 125",
                "type": "motorcycle",
                "brand": "Honda",
                "model": "FAN 125 KS",
                "version": "KS",
                "year": 2010,
                "license_plate": null,
                "fuel_type": "Gasolina",
                "odometer_km": 12260.0,
                "active": true
              }
            ]
        """.trimIndent()

        val dtoList = SupabaseClientProvider.json.decodeFromString<List<VehicleRemoteDto>>(jsonPayload)
        assertEquals(1, dtoList.size)

        val dto = dtoList.first()
        assertEquals("Titan 125", dto.name)
        assertEquals("Honda", dto.brand)
        assertEquals("FAN 125 KS", dto.model)
        assertEquals("KS", dto.version)
        assertEquals(2010, dto.year)
        assertEquals(12260.0, dto.odometerKm ?: 0.0, 0.001)
        assertTrue(dto.active ?: false)

        val vehicle = Vehicle(
            id = dto.id,
            name = dto.name,
            plate = dto.licensePlate,
            model = dto.model,
            brand = dto.brand,
            version = dto.version,
            year = dto.year,
            fuelType = dto.fuelType,
            type = dto.type,
            currentOdometerKm = dto.odometerKm ?: 0.0,
            userId = dto.userId,
            active = dto.active ?: true
        )

        assertEquals("Titan 125", vehicle.name)
        assertEquals("Honda", vehicle.brand)
        assertEquals("FAN 125 KS", vehicle.model)
        assertEquals(2010, vehicle.year)
        assertEquals(12260.0, vehicle.currentOdometerKm, 0.001)
        assertTrue(vehicle.active)
    }

    @Test
    fun testHttp401DoesNotInjectMocks() = runBlocking {
        val failingRemote = object : VehicleRemoteDataSource {
            override suspend fun getVehicles(): Result<List<VehicleRemoteDto>> {
                return Result.failure(SessionExpiredException("Falha de configuração do Supabase."))
            }
        }

        val repository = VehicleRepository(
            database = db,
            remoteDataSource = failingRemote,
            authRepository = AuthRepository.getInstance()
        )

        val result = repository.refreshVehicles("test-user")
        assertTrue("Resultado deve ser falha em caso de 401", result.isFailure)

        val vehicles = repository.getVehiclesForUser("test-user")
        assertTrue("Nenhum mock deve ser inserido", vehicles.isEmpty())

        val allInDb = db.vehicleDao().getAllVehicles()
        assertTrue("Banco local não deve conter nenhum mock após 401", allInDb.isEmpty())
        for (v in allInDb) {
            assertFalse(v.name.contains("Fiat Strada"))
            assertFalse(v.name.contains("CG 160"))
            assertFalse(v.name.contains("Yamaha Factor"))
        }
    }
}
