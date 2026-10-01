package com.phdrive.companion.repository

import android.content.Context
import android.util.Log
import com.phdrive.companion.data.local.SecureSessionStore
import com.phdrive.companion.data.remote.AuthRemoteDataSource
import com.phdrive.companion.data.remote.SessionExpiredException
import com.phdrive.companion.data.remote.SupabaseAuthRemoteDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AuthState {
    LOADING,
    AUTHENTICATED,
    UNAUTHENTICATED
}

class AuthRepository(
    private val sessionStore: SecureSessionStore,
    private val remoteDataSource: AuthRemoteDataSource = SupabaseAuthRemoteDataSource()
) {

    private val refreshMutex = Mutex()

    private val _authState = MutableStateFlow<AuthState>(AuthState.LOADING)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _currentUserId = MutableStateFlow<String>(
        sessionStore.getUserId() ?: ""
    )
    val currentUserId: StateFlow<String> = _currentUserId.asStateFlow()

    private val _currentUserEmail = MutableStateFlow<String?>(
        sessionStore.getUserEmail()
    )
    val currentUserEmail: StateFlow<String?> = _currentUserEmail.asStateFlow()

    fun getAccessToken(): String? = sessionStore.getAccessToken()

    fun getUserId(): String = _currentUserId.value.ifBlank {
        sessionStore.getUserId() ?: ""
    }

    fun getUserEmail(): String? = _currentUserEmail.value ?: sessionStore.getUserEmail()

    fun hasValidSession(): Boolean {
        return _authState.value == AuthState.AUTHENTICATED && sessionStore.hasAnySession()
    }

    /**
     * Obtém o token de acesso atual ou renova automaticamente caso tenha expirado.
     * Thread-safe com Mutex para evitar múltiplos refreshes concorrentes.
     */
    suspend fun getOrRefreshAccessToken(): String? {
        val currentToken = sessionStore.getAccessToken()
        if (sessionStore.isAccessTokenValid() && !currentToken.isNullOrBlank()) {
            return currentToken
        }

        val refreshToken = sessionStore.getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            Log.d(TAG, "Nenhum refresh token disponível para renovação.")
            return null
        }

        return refreshMutex.withLock {
            // Dupla checagem após obter o lock
            if (sessionStore.isAccessTokenValid() && !sessionStore.getAccessToken().isNullOrBlank()) {
                return@withLock sessionStore.getAccessToken()
            }

            Log.d(TAG, "Access token expirado. Tentando renovação via refresh_token...")
            val result = remoteDataSource.refreshToken(refreshToken)
            if (result.isSuccess) {
                val tokenDto = result.getOrThrow()
                val currentSeconds = System.currentTimeMillis() / 1000
                val calculatedExpiresAt = tokenDto.expiresAt
                    ?: (tokenDto.expiresIn?.let { currentSeconds + it } ?: (currentSeconds + 3600))

                sessionStore.updateTokens(
                    accessToken = tokenDto.accessToken,
                    refreshToken = tokenDto.refreshToken ?: refreshToken,
                    expiresAtSeconds = calculatedExpiresAt
                )
                tokenDto.user?.let { user ->
                    if (user.id.isNotBlank()) {
                        _currentUserId.value = user.id
                        user.email?.let { _currentUserEmail.value = it }
                    }
                }
                _authState.value = AuthState.AUTHENTICATED
                Log.d(TAG, "Sessão renovada com sucesso.")
                tokenDto.accessToken
            } else {
                Log.w(TAG, "Falha na renovação da sessão: ${result.exceptionOrNull()?.message}")
                clearAuth()
                null
            }
        }
    }

    /**
     * Restauração na inicialização do aplicativo (Splash / AuthGate):
     * A) sessão inexistente -> Login
     * B) access token válido -> Home
     * C) access token expirado + refresh token válido -> renovar sessão -> Home
     * D) refresh inválido/expirado -> limpar sessão -> Login
     */
    suspend fun checkInitialSession(): AuthState {
        _authState.value = AuthState.LOADING

        if (!sessionStore.hasAnySession()) {
            Log.d(TAG, "Inicialização: Nenhuma sessão encontrada.")
            _authState.value = AuthState.UNAUTHENTICATED
            return AuthState.UNAUTHENTICATED
        }

        if (sessionStore.isAccessTokenValid()) {
            Log.d(TAG, "Inicialização: Access token válido encontrado.")
            sessionStore.getUserId()?.let { _currentUserId.value = it }
            sessionStore.getUserEmail()?.let { _currentUserEmail.value = it }
            _authState.value = AuthState.AUTHENTICATED
            return AuthState.AUTHENTICATED
        }

        val refreshToken = sessionStore.getRefreshToken()
        if (!refreshToken.isNullOrBlank()) {
            Log.d(TAG, "Inicialização: Access token expirado, tentando renovar com refresh token...")
            val refreshedToken = getOrRefreshAccessToken()
            if (!refreshedToken.isNullOrBlank()) {
                _authState.value = AuthState.AUTHENTICATED
                return AuthState.AUTHENTICATED
            }
        }

        Log.d(TAG, "Inicialização: Sessão expirada e não renovada. Redirecionando para Login.")
        clearAuth()
        _authState.value = AuthState.UNAUTHENTICATED
        return AuthState.UNAUTHENTICATED
    }

    /**
     * Realiza login no Supabase por e-mail e senha.
     */
    suspend fun login(email: String, password: String): Result<Unit> {
        val result = remoteDataSource.login(email, password)
        return if (result.isSuccess) {
            val response = result.getOrThrow()
            val userId = response.user?.id ?: ""
            val userEmail = response.user?.email ?: email
            val currentSeconds = System.currentTimeMillis() / 1000
            val calculatedExpiresAt = response.expiresAt
                ?: (response.expiresIn?.let { currentSeconds + it } ?: (currentSeconds + 3600))

            sessionStore.saveSession(
                userId = userId,
                email = userEmail,
                accessToken = response.accessToken,
                refreshToken = response.refreshToken,
                expiresAtSeconds = calculatedExpiresAt
            )

            _currentUserId.value = userId
            _currentUserEmail.value = userEmail
            _authState.value = AuthState.AUTHENTICATED
            Result.success(Unit)
        } else {
            val error = result.exceptionOrNull() ?: Exception("Falha no login")
            _authState.value = AuthState.UNAUTHENTICATED
            Result.failure(error)
        }
    }

    /**
     * Força renovação manual do token (ex: após 401).
     */
    suspend fun refreshToken(): Result<String> {
        val token = getOrRefreshAccessToken()
        return if (!token.isNullOrBlank()) {
            Result.success(token)
        } else {
            Result.failure(SessionExpiredException("Sessão expirada. Faça login novamente."))
        }
    }

    /**
     * Encerra a sessão:
     * - Faz chamada de logout no Supabase;
     * - Apaga tokens locais;
     * - NÃO apaga o Room automaticamente;
     * - Atualiza AuthState para UNAUTHENTICATED.
     */
    suspend fun logout(): Result<Unit> {
        val currentToken = sessionStore.getAccessToken()
        try {
            remoteDataSource.logout(currentToken)
        } catch (e: Exception) {
            Log.w(TAG, "Falha na chamada remota de logout: ${e.message}")
        } finally {
            clearAuth()
        }
        return Result.success(Unit)
    }

    fun clearAuth() {
        sessionStore.clearSession()
        _currentUserId.value = ""
        _currentUserEmail.value = null
        _authState.value = AuthState.UNAUTHENTICATED
    }

    fun setAuth(userId: String, email: String, token: String?, refreshToken: String? = null, expiresAt: Long = 0L) {
        sessionStore.saveSession(
            userId = userId,
            email = email,
            accessToken = token ?: "",
            refreshToken = refreshToken,
            expiresAtSeconds = expiresAt
        )
        _currentUserId.value = userId
        _currentUserEmail.value = email
        _authState.value = if (!token.isNullOrBlank()) AuthState.AUTHENTICATED else AuthState.UNAUTHENTICATED
    }

    companion object {
        private const val TAG = "AuthRepository"

        @Volatile
        private var instance: AuthRepository? = null

        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        val sessionStore = SecureSessionStore(context.applicationContext)
                        instance = AuthRepository(sessionStore)
                    }
                }
            }
        }

        fun getInstance(): AuthRepository {
            return instance ?: synchronized(this) {
                instance ?: throw IllegalStateException("AuthRepository não foi inicializado com contexto.")
            }
        }

        fun resetForTesting(repository: AuthRepository) {
            instance = repository
        }
    }
}
