package com.phdrive.companion

import com.phdrive.companion.data.remote.AuthException
import com.phdrive.companion.data.remote.SupabaseAuthRemoteDataSource
import com.phdrive.companion.data.remote.SupabaseClientProvider
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
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
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SupabaseAuthRemoteDataSourceTest {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Before
    fun setup() {
        SupabaseClientProvider.customClient = null
    }

    @After
    fun tearDown() {
        SupabaseClientProvider.customClient = null
    }

    // 11.1 Testar: endpoint contém grant_type=password
    // 11.2 Testar: header apikey usa publishable key
    // 11.3 Testar: login inicial não exige Authorization bearer
    // 11.4 Testar: body contém email e password corretos
    // 11.5 Testar: password não sofre trim
    @Test
    fun testLoginRequestEndpointHeadersAndUntrimmedPassword() = runBlocking {
        var interceptedUrl = ""
        var interceptedApiKey = ""
        var interceptedAuthorization: String? = "initial_sentinel"
        var interceptedBody = ""

        val fakeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                interceptedUrl = request.url.toString()
                interceptedApiKey = request.header("apikey") ?: ""
                interceptedAuthorization = request.header("Authorization")

                val buffer = Buffer()
                request.body?.writeTo(buffer)
                interceptedBody = buffer.readUtf8()

                val responseJson = """
                    {
                      "access_token": "mock_jwt_access_token_123",
                      "token_type": "bearer",
                      "expires_in": 3600,
                      "expires_at": 1740000000,
                      "refresh_token": "mock_refresh_token_456",
                      "user": {
                        "id": "uuid-motorista-99",
                        "email": "motorista@phdrive.com"
                      }
                    }
                """.trimIndent()

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseJson.toResponseBody(jsonMediaType))
                    .build()
            }
            .build()

        SupabaseClientProvider.customClient = fakeClient
        val dataSource = SupabaseAuthRemoteDataSource(SupabaseClientProvider)

        val untrimmedPassword = "  senhaComEspacos123!  "
        val testEmail = "motorista@phdrive.com"

        val result = dataSource.login(testEmail, untrimmedPassword)

        // 11.1 endpoint contém grant_type=password
        assertTrue("URL deve conter grant_type=password", interceptedUrl.contains("grant_type=password"))
        assertTrue("URL deve apontar para auth/v1/token", interceptedUrl.contains("/auth/v1/token"))

        // 11.2 header apikey usa publishable key
        assertEquals(BuildConfig.SUPABASE_PUBLISHABLE_KEY, interceptedApiKey)

        // 11.3 login inicial não exige Authorization bearer
        assertNull("Login inicial não deve enviar Authorization Bearer", interceptedAuthorization)

        // 11.4 & 11.5 body contém email e password exatos sem sofrer trim
        assertTrue("Body deve conter email correto", interceptedBody.contains("\"email\":\"$testEmail\""))
        assertTrue("Body deve conter a senha com os espaços originais (sem trim)", interceptedBody.contains("\"password\":\"$untrimmedPassword\""))

        // 11.6 & 11.7 resposta mapeia access_token e refresh_token
        assertTrue("Resultado deve ser sucesso", result.isSuccess)
        val tokenDto = result.getOrThrow()
        assertEquals("mock_jwt_access_token_123", tokenDto.accessToken)
        assertEquals("mock_refresh_token_456", tokenDto.refreshToken)
        assertEquals(3600L, tokenDto.expiresIn)
        assertEquals("uuid-motorista-99", tokenDto.user?.id)
        assertEquals("motorista@phdrive.com", tokenDto.user?.email)
    }

    // 11.8 Testar: 401 API key não vira "senha inválida" -> vira "Falha de configuração do Supabase."
    @Test
    fun test401InvalidApiKeyDoesNotTurnIntoInvalidPassword() = runBlocking {
        val fakeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val errorJson = """
                    {
                      "message": "Invalid API key"
                    }
                """.trimIndent()

                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body(errorJson.toResponseBody(jsonMediaType))
                    .build()
            }
            .build()

        SupabaseClientProvider.customClient = fakeClient
        val dataSource = SupabaseAuthRemoteDataSource(SupabaseClientProvider)

        val result = dataSource.login("motorista@phdrive.com", "senha123")

        assertTrue("Resultado deve ser falha", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AuthException)
        assertEquals("Falha de configuração do Supabase.", ex?.message)
        assertFalse("Não deve mascarar como senha inválida", ex?.message?.contains("senha", ignoreCase = true) ?: false)
    }

    // 11.9 Testar: erro de rede não vira "senha inválida" -> vira "Não foi possível conectar ao servidor."
    @Test
    fun testNetworkErrorDoesNotTurnIntoInvalidPassword() = runBlocking {
        val fakeClient = OkHttpClient.Builder()
            .addInterceptor {
                throw IOException("Falha de conexão física/DNS")
            }
            .build()

        SupabaseClientProvider.customClient = fakeClient
        val dataSource = SupabaseAuthRemoteDataSource(SupabaseClientProvider)

        val result = dataSource.login("motorista@phdrive.com", "senha123")

        assertTrue("Resultado deve ser falha", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Deve ser AuthException", ex is AuthException)
        assertEquals("Não foi possível conectar ao servidor.", ex?.message)
        assertFalse("Não deve mascarar como senha inválida", ex?.message?.contains("senha", ignoreCase = true) ?: false)
    }

    // Testar: 400 com credenciais inválidas retorna "E-mail ou senha inválidos."
    @Test
    fun test400InvalidCredentialsReturnsFriendlyMessage() = runBlocking {
        val fakeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val errorJson = """
                    {
                      "error": "invalid_grant",
                      "error_description": "Invalid login credentials"
                    }
                """.trimIndent()

                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(400)
                    .message("Bad Request")
                    .body(errorJson.toResponseBody(jsonMediaType))
                    .build()
            }
            .build()

        SupabaseClientProvider.customClient = fakeClient
        val dataSource = SupabaseAuthRemoteDataSource(SupabaseClientProvider)

        val result = dataSource.login("motorista@phdrive.com", "senha_errada")

        assertTrue("Resultado deve ser falha", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AuthException)
        assertEquals("E-mail ou senha inválidos.", ex?.message)
    }

    // Testar: Erro de serialização retorna "Erro ao processar resposta do servidor."
    @Test
    fun testSerializationErrorReturnsFriendlyMessage() = runBlocking {
        val fakeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("<html>Resposta não-JSON</html>".toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        SupabaseClientProvider.customClient = fakeClient
        val dataSource = SupabaseAuthRemoteDataSource(SupabaseClientProvider)

        val result = dataSource.login("motorista@phdrive.com", "senha123")

        assertTrue("Resultado deve ser falha", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AuthException)
        assertEquals("Erro ao processar resposta do servidor.", ex?.message)
    }
}
