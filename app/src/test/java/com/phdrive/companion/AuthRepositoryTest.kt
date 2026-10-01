package com.phdrive.companion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phdrive.companion.data.local.SecureSessionStore
import com.phdrive.companion.data.remote.AuthException
import com.phdrive.companion.data.remote.AuthRemoteDataSource
import com.phdrive.companion.data.remote.SessionExpiredException
import com.phdrive.companion.data.remote.model.AuthTokenResponseDto
import com.phdrive.companion.data.remote.model.AuthUserDto
import com.phdrive.companion.repository.AuthRepository
import com.phdrive.companion.repository.AuthState
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthRepositoryTest {

    private lateinit var context: Context
    private lateinit var sessionStore: SecureSessionStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        sessionStore = SecureSessionStore(context)
        sessionStore.clearSession()
    }

    // 13.1 Testar: Login Válido
    @Test
    fun testValidLogin() = runBlocking {
        val fakeRemote = object : AuthRemoteDataSource {
            override suspend fun login(email: String, password: String): Result<AuthTokenResponseDto> {
                return Result.success(
                    AuthTokenResponseDto(
                        accessToken = "valid_access_token_123",
                        refreshToken = "valid_refresh_token_456",
                        expiresIn = 3600,
                        expiresAt = (System.currentTimeMillis() / 1000) + 3600,
                        user = AuthUserDto(id = "user-uuid-1", email = email)
                    )
                )
            }
            override suspend fun refreshToken(refreshToken: String): Result<AuthTokenResponseDto> {
                throw UnsupportedOperationException()
            }
            override suspend fun logout(accessToken: String?): Result<Unit> = Result.success(Unit)
        }

        val authRepo = AuthRepository(sessionStore, fakeRemote)
        val result = authRepo.login("motorista@phdrive.com", "senha123")

        assertTrue("Login deve ser bem-sucedido", result.isSuccess)
        assertEquals(AuthState.AUTHENTICATED, authRepo.authState.value)
        assertEquals("user-uuid-1", authRepo.getUserId())
        assertEquals("motorista@phdrive.com", authRepo.getUserEmail())
        assertEquals("valid_access_token_123", authRepo.getAccessToken())
        assertEquals("valid_refresh_token_456", sessionStore.getRefreshToken())
        assertTrue("Sessão deve ser válida", authRepo.hasValidSession())
    }

    // 13.2 Testar: Login Inválido
    @Test
    fun testInvalidLogin() = runBlocking {
        val fakeRemote = object : AuthRemoteDataSource {
            override suspend fun login(email: String, password: String): Result<AuthTokenResponseDto> {
                return Result.failure(AuthException("E-mail ou senha inválidos.", 400))
            }
            override suspend fun refreshToken(refreshToken: String): Result<AuthTokenResponseDto> {
                throw UnsupportedOperationException()
            }
            override suspend fun logout(accessToken: String?): Result<Unit> = Result.success(Unit)
        }

        val authRepo = AuthRepository(sessionStore, fakeRemote)
        val result = authRepo.login("motorista@phdrive.com", "senha_errada")

        assertTrue("Login deve falhar", result.isFailure)
        assertEquals("E-mail ou senha inválidos.", result.exceptionOrNull()?.message)
        assertEquals(AuthState.UNAUTHENTICATED, authRepo.authState.value)
        assertNull(authRepo.getAccessToken())
        assertFalse(authRepo.hasValidSession())
    }

    // 13.3 Testar: Refresh Token Bem-Sucedido
    @Test
    fun testSuccessfulRefreshToken() = runBlocking {
        val currentSeconds = System.currentTimeMillis() / 1000
        // Salvar token expirado propositalmente (expirou há 1 hora)
        sessionStore.saveSession(
            userId = "user-uuid-2",
            email = "user@test.com",
            accessToken = "expired_old_token",
            refreshToken = "initial_refresh_token",
            expiresAtSeconds = currentSeconds - 3600
        )

        val fakeRemote = object : AuthRemoteDataSource {
            override suspend fun login(email: String, password: String): Result<AuthTokenResponseDto> {
                throw UnsupportedOperationException()
            }
            override suspend fun refreshToken(refreshToken: String): Result<AuthTokenResponseDto> {
                assertEquals("initial_refresh_token", refreshToken)
                return Result.success(
                    AuthTokenResponseDto(
                        accessToken = "new_rotated_access_token",
                        refreshToken = "new_rotated_refresh_token",
                        expiresIn = 3600,
                        expiresAt = currentSeconds + 3600,
                        user = AuthUserDto(id = "user-uuid-2", email = "user@test.com")
                    )
                )
            }
            override suspend fun logout(accessToken: String?): Result<Unit> = Result.success(Unit)
        }

        val authRepo = AuthRepository(sessionStore, fakeRemote)
        val refreshedToken = authRepo.getOrRefreshAccessToken()

        assertNotNull(refreshedToken)
        assertEquals("new_rotated_access_token", refreshedToken)
        assertEquals("new_rotated_access_token", sessionStore.getAccessToken())
        assertEquals("new_rotated_refresh_token", sessionStore.getRefreshToken())
        assertEquals(AuthState.AUTHENTICATED, authRepo.authState.value)
    }

    // 13.4 Testar: Refresh Token Expirado/Inválido
    @Test
    fun testExpiredOrInvalidRefreshToken() = runBlocking {
        val currentSeconds = System.currentTimeMillis() / 1000
        sessionStore.saveSession(
            userId = "user-uuid-3",
            email = "user3@test.com",
            accessToken = "expired_token",
            refreshToken = "dead_refresh_token",
            expiresAtSeconds = currentSeconds - 3600
        )

        val fakeRemote = object : AuthRemoteDataSource {
            override suspend fun login(email: String, password: String): Result<AuthTokenResponseDto> {
                throw UnsupportedOperationException()
            }
            override suspend fun refreshToken(refreshToken: String): Result<AuthTokenResponseDto> {
                return Result.failure(SessionExpiredException("Sessão expirada. Faça login novamente."))
            }
            override suspend fun logout(accessToken: String?): Result<Unit> = Result.success(Unit)
        }

        val authRepo = AuthRepository(sessionStore, fakeRemote)
        val refreshedToken = authRepo.getOrRefreshAccessToken()

        assertNull("Token deve ser nulo após falha no refresh", refreshedToken)
        assertNull("Access token local deve ser limpo", sessionStore.getAccessToken())
        assertNull("Refresh token local deve ser limpo", sessionStore.getRefreshToken())
        assertEquals(AuthState.UNAUTHENTICATED, authRepo.authState.value)
        assertFalse(authRepo.hasValidSession())
    }

    // 13.5 Testar: Inicialização do App com Sessão Válida
    @Test
    fun testAppInitWithValidSession() = runBlocking {
        val currentSeconds = System.currentTimeMillis() / 1000
        sessionStore.saveSession(
            userId = "valid-user-1",
            email = "valid@test.com",
            accessToken = "currently_valid_token",
            refreshToken = "valid_refresh",
            expiresAtSeconds = currentSeconds + 3600 // Válido por mais 1 hora
        )

        var remoteRefreshCalled = false
        val fakeRemote = object : AuthRemoteDataSource {
            override suspend fun login(email: String, password: String): Result<AuthTokenResponseDto> = throw UnsupportedOperationException()
            override suspend fun refreshToken(refreshToken: String): Result<AuthTokenResponseDto> {
                remoteRefreshCalled = true
                throw UnsupportedOperationException()
            }
            override suspend fun logout(accessToken: String?): Result<Unit> = Result.success(Unit)
        }

        val authRepo = AuthRepository(sessionStore, fakeRemote)
        val state = authRepo.checkInitialSession()

        assertEquals(AuthState.AUTHENTICATED, state)
        assertFalse("Não deve chamar endpoint de refresh se o access token for válido", remoteRefreshCalled)
        assertEquals("currently_valid_token", authRepo.getAccessToken())
        assertEquals("valid-user-1", authRepo.getUserId())
    }

    // 13.6 Testar: Inicialização do App com Token Expirado + Refresh Válido
    @Test
    fun testAppInitWithExpiredTokenAndValidRefresh() = runBlocking {
        val currentSeconds = System.currentTimeMillis() / 1000
        sessionStore.saveSession(
            userId = "user-expire-now",
            email = "expire@test.com",
            accessToken = "expired_token_init",
            refreshToken = "good_refresh_init",
            expiresAtSeconds = currentSeconds - 100 // Expirado
        )

        val fakeRemote = object : AuthRemoteDataSource {
            override suspend fun login(email: String, password: String): Result<AuthTokenResponseDto> = throw UnsupportedOperationException()
            override suspend fun refreshToken(refreshToken: String): Result<AuthTokenResponseDto> {
                return Result.success(
                    AuthTokenResponseDto(
                        accessToken = "brand_new_token_at_init",
                        refreshToken = "brand_new_refresh_at_init",
                        expiresIn = 3600,
                        expiresAt = currentSeconds + 3600,
                        user = AuthUserDto(id = "user-expire-now", email = "expire@test.com")
                    )
                )
            }
            override suspend fun logout(accessToken: String?): Result<Unit> = Result.success(Unit)
        }

        val authRepo = AuthRepository(sessionStore, fakeRemote)
        val state = authRepo.checkInitialSession()

        assertEquals(AuthState.AUTHENTICATED, state)
        assertEquals("brand_new_token_at_init", authRepo.getAccessToken())
        assertEquals("brand_new_refresh_at_init", sessionStore.getRefreshToken())
    }

    // 13.7 Testar: Inicialização do App sem Sessão
    @Test
    fun testAppInitWithoutSession() = runBlocking {
        sessionStore.clearSession()

        val fakeRemote = object : AuthRemoteDataSource {
            override suspend fun login(email: String, password: String): Result<AuthTokenResponseDto> = throw UnsupportedOperationException()
            override suspend fun refreshToken(refreshToken: String): Result<AuthTokenResponseDto> = throw UnsupportedOperationException()
            override suspend fun logout(accessToken: String?): Result<Unit> = Result.success(Unit)
        }

        val authRepo = AuthRepository(sessionStore, fakeRemote)
        val state = authRepo.checkInitialSession()

        assertEquals(AuthState.UNAUTHENTICATED, state)
        assertNull(authRepo.getAccessToken())
        assertFalse(authRepo.hasValidSession())
    }

    // 13.8 Testar: Logout
    @Test
    fun testLogout() = runBlocking {
        val currentSeconds = System.currentTimeMillis() / 1000
        sessionStore.saveSession(
            userId = "logout-user",
            email = "logout@test.com",
            accessToken = "token_to_logout",
            refreshToken = "refresh_to_logout",
            expiresAtSeconds = currentSeconds + 3600
        )

        var logoutCalledOnRemote = false
        val fakeRemote = object : AuthRemoteDataSource {
            override suspend fun login(email: String, password: String): Result<AuthTokenResponseDto> = throw UnsupportedOperationException()
            override suspend fun refreshToken(refreshToken: String): Result<AuthTokenResponseDto> = throw UnsupportedOperationException()
            override suspend fun logout(accessToken: String?): Result<Unit> {
                assertEquals("token_to_logout", accessToken)
                logoutCalledOnRemote = true
                return Result.success(Unit)
            }
        }

        val authRepo = AuthRepository(sessionStore, fakeRemote)
        authRepo.checkInitialSession()
        assertEquals(AuthState.AUTHENTICATED, authRepo.authState.value)

        val logoutResult = authRepo.logout()
        assertTrue(logoutResult.isSuccess)
        assertTrue(logoutCalledOnRemote)
        assertEquals(AuthState.UNAUTHENTICATED, authRepo.authState.value)
        assertNull(sessionStore.getAccessToken())
        assertNull(sessionStore.getRefreshToken())
        assertEquals("", authRepo.getUserId())
        assertNull(authRepo.getUserEmail())
    }
}
