package com.phdrive.companion.ui.auth

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phdrive.companion.repository.AuthRepository
import com.phdrive.companion.repository.AuthState
import com.phdrive.companion.ui.home.HomeScreen
import com.phdrive.companion.ui.home.HomeViewModel
import com.phdrive.companion.ui.login.LoginScreen
import com.phdrive.companion.ui.login.LoginViewModel

@Composable
fun AuthGate(
    authRepository: AuthRepository = AuthRepository.getInstance(),
    homeViewModel: HomeViewModel,
    modifier: Modifier = Modifier
) {
    val authState by authRepository.authState.collectAsState()

    // 5. RESTAURAÇÃO NA INICIALIZAÇÃO
    LaunchedEffect(Unit) {
        authRepository.checkInitialSession()
    }

    Crossfade(targetState = authState, label = "AuthGateCrossfade", modifier = modifier) { state ->
        when (state) {
            AuthState.LOADING -> {
                SplashScreen()
            }
            AuthState.UNAUTHENTICATED -> {
                val loginViewModel: LoginViewModel = viewModel(factory = LoginViewModel.Factory())
                LoginScreen(
                    viewModel = loginViewModel,
                    onLoginSuccess = {
                        // Após o login, a Home será recarregada automaticamente
                        homeViewModel.refreshVehicles()
                    }
                )
            }
            AuthState.AUTHENTICATED -> {
                HomeScreen(
                    viewModel = homeViewModel,
                    onLogout = {
                        // O repositório já muda para UNAUTHENTICATED
                    }
                )
            }
        }
    }
}

@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("splash_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "PH Drive Companion",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator(
                modifier = Modifier
                    .size(32.dp)
                    .testTag("splash_loading_indicator"),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Verificando sessão...",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
