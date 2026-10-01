package com.phdrive.companion.ui.login

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.phdrive.companion.data.remote.AuthException
import com.phdrive.companion.repository.AuthRepository
import com.phdrive.companion.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(
    private val authRepository: AuthRepository = AuthRepository.getInstance()
) : ViewModel() {

    private val _email = MutableStateFlow("")
    val email: StateFlow<String> = _email.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _errorDetails = MutableStateFlow<String?>(null)
    val errorDetails: StateFlow<String?> = _errorDetails.asStateFlow()

    fun onEmailChanged(newEmail: String) {
        _email.value = newEmail
        _errorMessage.value = null
        _errorDetails.value = null
    }

    fun onPasswordChanged(newPassword: String) {
        _password.value = newPassword
        _errorMessage.value = null
        _errorDetails.value = null
    }

    fun login(context: Context, onSuccess: () -> Unit) {
        // E-mail: pode usar trim() e normalizar lowercase
        val emailInput = _email.value.trim().lowercase()
        // Senha: NÃO aplicar trim(), NÃO alterar maiúsculas/minúsculas, enviar exatamente o digitado
        val passwordInput = _password.value

        if (emailInput.isEmpty()) {
            _errorMessage.value = "Por favor, informe seu e-mail."
            return
        }

        if (passwordInput.isEmpty()) {
            _errorMessage.value = "Por favor, informe sua senha."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _errorDetails.value = null

            val result = authRepository.login(emailInput, passwordInput)
            _isLoading.value = false

            result.onSuccess {
                _password.value = ""
                SyncScheduler.enqueueManualSync(context.applicationContext)
                onSuccess()
            }.onFailure { error ->
                _errorMessage.value = error.message ?: "Falha ao realizar login. Tente novamente."
                if (error is AuthException) {
                    val statusStr = error.statusCode?.toString() ?: "N/A"
                    val codeStr = error.errorCode ?: "N/A"
                    _errorDetails.value = "HTTP status: $statusStr | Error code: $codeStr"
                }
            }
        }
    }

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LoginViewModel(AuthRepository.getInstance()) as T
        }
    }
}
