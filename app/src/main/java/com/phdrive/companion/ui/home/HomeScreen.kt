package com.phdrive.companion.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.phdrive.companion.bluetooth.BluetoothConnectionState
import com.phdrive.companion.bluetooth.ConnectedDeviceIdentity
import com.phdrive.companion.bluetooth.PairedBluetoothDevice
import com.phdrive.companion.bluetooth.SessionState
import com.phdrive.companion.bluetooth.VehicleConnectionSession
import com.phdrive.companion.bluetooth.VehicleIdentificationSource
import com.phdrive.companion.car.AutoTripState
import com.phdrive.companion.car.CarProjectionState
import com.phdrive.companion.data.backup.BackupEnvelope
import com.phdrive.companion.model.LocalTrip
import com.phdrive.companion.model.LocalTripPoint
import com.phdrive.companion.model.TripStartMode
import com.phdrive.companion.model.Vehicle
import com.phdrive.companion.model.VehicleBluetoothBinding
import com.phdrive.companion.repository.BackupValidationResult
import com.phdrive.companion.repository.SyncState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activeTrip by viewModel.activeTrip.collectAsState()
    val vehicles by viewModel.vehicles.collectAsState()
    val pointCount by viewModel.pointCount.collectAsState()
    val latestPoint by viewModel.latestPoint.collectAsState()
    val hasLocationPermission by viewModel.hasLocationPermission.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val isOnline by viewModel.isOnline.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val activeTripSyncedPoints by viewModel.activeTripSyncedPoints.collectAsState()
    val activeTripPendingPoints by viewModel.activeTripPendingPoints.collectAsState()
    val totalPendingPoints by viewModel.totalPendingPoints.collectAsState()
    val totalFailedPoints by viewModel.totalFailedPoints.collectAsState()
    val totalPendingTrips by viewModel.totalPendingTrips.collectAsState()
    val totalLocalTrips by viewModel.totalLocalTrips.collectAsState()
    val totalLocalPoints by viewModel.totalLocalPoints.collectAsState()
    val isRefreshingVehicles by viewModel.isRefreshingVehicles.collectAsState()
    val vehicleErrorMessage by viewModel.vehicleErrorMessage.collectAsState()
    val lastSyncAttemptTime by viewModel.lastSyncAttemptTime.collectAsState()
    val lastSuccessfulSyncTime by viewModel.lastSuccessfulSyncTime.collectAsState()
    val lastSyncErrorCode by viewModel.lastSyncErrorCode.collectAsState()
    val workerState by viewModel.workerState.collectAsState()
    val tripDiagnostics by viewModel.tripDiagnostics.collectAsState()

    val isInterruptedAfterReboot by viewModel.isInterruptedAfterReboot.collectAsState()
    val trackingState by viewModel.trackingState.collectAsState()
    val secondsSinceLastPoint by viewModel.secondsSinceLastPoint.collectAsState()
    val discardedLowAccuracyCount by viewModel.discardedLowAccuracyCount.collectAsState()

    // MICROETAPA 3B.1 e 3B.2: Detecção Bluetooth + Android Auto + AutoTripOrchestrator
    val bluetoothConnectionState by viewModel.bluetoothConnectionState.collectAsState()
    val connectedDeviceIdentity by viewModel.connectedDeviceIdentity.collectAsState()
    val identifiedVehicle by viewModel.identifiedVehicle.collectAsState()
    val identificationSource by viewModel.identificationSource.collectAsState()
    val isBluetoothEnabled by viewModel.isBluetoothEnabled.collectAsState()
    val bluetoothBindings by viewModel.bluetoothBindings.collectAsState()
    val carProjectionState by viewModel.carProjectionState.collectAsState()
    val connectionSession by viewModel.connectionSession.collectAsState()

    val autoTripState by viewModel.autoTripState.collectAsState()
    val movementSpeedKmh by viewModel.movementSpeedKmh.collectAsState()
    val movementDurationSec by viewModel.movementDurationSec.collectAsState()
    val movementDistanceM by viewModel.movementDistanceM.collectAsState()
    val cooldownActive by viewModel.cooldownActive.collectAsState()
    val cooldownRemainingSec by viewModel.cooldownRemainingSec.collectAsState()
    val disconnectDurationSec by viewModel.disconnectDurationSec.collectAsState()
    val stationaryDurationSec by viewModel.stationaryDurationSec.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    var showEndTripDialog by remember { mutableStateOf(false) }
    var showCancelTripDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var vehicleToStartTrip by remember { mutableStateOf<Vehicle?>(null) }
    var vehicleToBindBluetooth by remember { mutableStateOf<Vehicle?>(null) }
    var pendingImportEnvelope by remember { mutableStateOf<BackupEnvelope?>(null) }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshBluetoothState()
        if (!granted) {
            viewModel.showMessage("Permissão Bluetooth necessária para identificar o veículo.")
        }
    }

    // SAF: Exportar backup local em JSON
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val jsonContent = viewModel.generateBackupJson()
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(jsonContent.toByteArray(Charsets.UTF_8))
                    }
                    viewModel.showMessage("Backup local exportado com sucesso!")
                } catch (e: Exception) {
                    viewModel.showMessage("Erro ao salvar backup: ${e.message}")
                }
            }
        }
    }

    // SAF: Importar backup local em JSON
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val jsonContent = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    when (val validation = viewModel.validateBackupJson(jsonContent)) {
                        is BackupValidationResult.Valid -> {
                            pendingImportEnvelope = validation.envelope
                        }
                        is BackupValidationResult.Invalid -> {
                            viewModel.showMessage("Arquivo de backup inválido: ${validation.reason}")
                        }
                    }
                } catch (e: Exception) {
                    viewModel.showMessage("Erro ao carregar arquivo de backup: ${e.message}")
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.updateLocationPermission(fineGranted || coarseGranted)
    }

    LaunchedEffect(Unit) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        viewModel.updateLocationPermission(fine || coarse)
        if (!fine && !coarse) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // MICROETAPA 3A.5: Resiliência e Continuidade do GPS - Garantir que o serviço de localização
    // esteja ativo caso haja uma viagem em andamento ao abrir ou retomar a HomeScreen,
    // DESDE QUE não tenha sido pausada por interrupção de reboot.
    LaunchedEffect(activeTrip?.localId, hasLocationPermission, isInterruptedAfterReboot) {
        val trip = activeTrip
        if (trip != null && hasLocationPermission && !isInterruptedAfterReboot) {
            com.phdrive.companion.service.LocationTrackingService.start(context, trip.localId)
        }
    }

    LaunchedEffect(userMessage) {
        userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUserMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PH Drive Companion", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        OnlineStatusBadge(isOnline = isOnline)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(
                        onClick = { viewModel.syncNow(context) },
                        modifier = Modifier.testTag("appbar_sync_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Sincronizar",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = { showLogoutDialog = true },
                        modifier = Modifier.testTag("logout_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Sair",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Alerta de Viagem Interrompida após Reboot
            if (activeTrip != null && isInterruptedAfterReboot) {
                item(key = "interrupted_trip_banner_card") {
                    InterruptedTripBannerCard(
                        trip = activeTrip!!,
                        pointCount = pointCount,
                        onResume = {
                            viewModel.resumeInterruptedTrip(context, activeTrip!!.localId)
                        },
                        onEnd = {
                            showEndTripDialog = true
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Painel de Viagem Ativa
            if (activeTrip != null) {
                item(key = "active_trip_card") {
                    ActiveTripCard(
                        trip = activeTrip!!,
                        pointCount = pointCount,
                        syncedPoints = activeTripSyncedPoints,
                        pendingPoints = activeTripPendingPoints,
                        latestPoint = latestPoint,
                        trackingState = trackingState,
                        secondsSinceLastPoint = secondsSinceLastPoint,
                        discardedLowAccuracyCount = discardedLowAccuracyCount,
                        onEndTrip = { showEndTripDialog = true },
                        onCancelTrip = { showCancelTripDialog = true }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Painel de Sincronização
            item(key = "sync_section_card") {
                SyncSectionCard(
                    isOnline = isOnline,
                    syncState = syncState,
                    totalPendingPoints = totalPendingPoints,
                    totalFailedPoints = totalFailedPoints,
                    totalPendingTrips = totalPendingTrips,
                    lastSyncAttemptTime = lastSyncAttemptTime,
                    lastSuccessfulSyncTime = lastSuccessfulSyncTime,
                    lastSyncErrorCode = lastSyncErrorCode,
                    workerState = workerState,
                    onSyncNow = { viewModel.syncNow(context) }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Card: Automação do Veículo (MICROETAPA 3B.1 e 3B.2 - Bluetooth Handshake + Android Auto + Automação)
            item(key = "bluetooth_automation_card") {
                BluetoothAutomationCard(
                    connectionState = bluetoothConnectionState,
                    connectedDeviceIdentity = connectedDeviceIdentity,
                    identifiedVehicle = identifiedVehicle,
                    identificationSource = identificationSource,
                    isBluetoothEnabled = isBluetoothEnabled,
                    carProjectionState = carProjectionState,
                    connectionSession = connectionSession,
                    autoTripState = autoTripState,
                    activeTrip = activeTrip,
                    movementSpeedKmh = movementSpeedKmh,
                    movementDurationSec = movementDurationSec,
                    movementDistanceM = movementDistanceM,
                    cooldownActive = cooldownActive,
                    cooldownRemainingSec = cooldownRemainingSec,
                    disconnectDurationSec = disconnectDurationSec,
                    stationaryDurationSec = stationaryDurationSec,
                    onRequestPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    },
                    onOpenBluetoothSettings = {
                        try {
                            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            viewModel.showMessage("Não foi possível abrir as configurações de Bluetooth.")
                        }
                    },
                    bluetoothBindings = bluetoothBindings
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Seção: Proteção de dados
            item(key = "data_protection_card") {
                DataProtectionCard(
                    totalTrips = totalLocalTrips,
                    totalPoints = totalLocalPoints,
                    pendingPoints = totalPendingPoints,
                    onExportBackup = {
                        val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        exportLauncher.launch("ph-drive-backup-$timestamp.json")
                    },
                    onImportBackup = {
                        importLauncher.launch(arrayOf("application/json"))
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            item(key = "trip_sync_diagnostic_card_item") {
                TripSyncDiagnosticCard(
                    diagnostics = tripDiagnostics
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Seção de Veículos
            item(key = "vehicles_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Veículos Cadastrados",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(
                        onClick = { viewModel.refreshVehicles() },
                        modifier = Modifier.testTag("refresh_vehicles_button")
                    ) {
                        if (isRefreshingVehicles) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Atualizar veículos",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                if (!vehicleErrorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = vehicleErrorMessage ?: "",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("vehicle_error_text")
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (vehicles.isEmpty()) {
                item(key = "empty_vehicles") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("empty_vehicles_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Nenhum veículo encontrado no Supabase.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            items(vehicles, key = { it.id }) { vehicle ->
                val binding = bluetoothBindings.find { it.vehicleId == vehicle.id }
                val isDeviceConnected = binding != null &&
                        connectedDeviceIdentity?.addressHash == binding.bluetoothDeviceAddressHash

                VehicleItemCard(
                    vehicle = vehicle,
                    isActiveTripVehicle = activeTrip?.vehicleId == vehicle.id,
                    hasActiveTrip = activeTrip != null,
                    binding = binding,
                    isDeviceConnected = isDeviceConnected,
                    onStartTrip = { vehicleToStartTrip = vehicle },
                    onBindBluetooth = { vehicleToBindBluetooth = vehicle },
                    onRemoveBluetooth = { viewModel.removeBluetoothBinding(vehicle.id) },
                    onToggleAutoAutomation = { enabled -> viewModel.setVehicleAutoAutomation(vehicle.id, enabled) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    // Diálogo de Vínculo Bluetooth (MICROETAPA 3B.1)
    if (vehicleToBindBluetooth != null) {
        val targetVehicle = vehicleToBindBluetooth!!
        val pairedDevices = remember { viewModel.getPairedBluetoothDevices() }
        BindBluetoothDeviceDialog(
            vehicle = targetVehicle,
            pairedDevices = pairedDevices,
            isBluetoothEnabled = isBluetoothEnabled,
            hasPermission = viewModel.hasBluetoothPermission(),
            onDismiss = { vehicleToBindBluetooth = null },
            onRequestPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            },
            onConfirm = { device ->
                viewModel.bindBluetoothDevice(targetVehicle.id, device)
                vehicleToBindBluetooth = null
            }
        )
    }

    // Diálogo de Iniciar Viagem
    if (vehicleToStartTrip != null) {
        StartTripDialog(
            vehicle = vehicleToStartTrip!!,
            onDismiss = { vehicleToStartTrip = null },
            onConfirm = { odometer ->
                viewModel.startTrip(context, vehicleToStartTrip!!, odometer)
                vehicleToStartTrip = null
            }
        )
    }

    // Diálogo de Encerrar Viagem
    if (showEndTripDialog && activeTrip != null) {
        EndTripDialog(
            trip = activeTrip!!,
            onDismiss = { showEndTripDialog = false },
            onConfirm = { odometer ->
                viewModel.endTrip(context, odometer)
                showEndTripDialog = false
            }
        )
    }

    // Diálogo de Cancelar Viagem
    if (showCancelTripDialog && activeTrip != null) {
        AlertDialog(
            onDismissRequest = { showCancelTripDialog = false },
            title = { Text("Cancelar Viagem?") },
            text = { Text("A viagem atual será cancelada e os pontos salvos não serão mais continuados.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.cancelTrip(context)
                        showCancelTripDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_cancel_trip_button")
                ) {
                    Text("Sim, Cancelar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelTripDialog = false }) {
                    Text("Voltar")
                }
            }
        )
    }

    // 11. Diálogo de Confirmação de Logout
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Sair da Conta") },
            text = { Text("Deseja realmente sair? Suas viagens e pontos salvos no dispositivo permanecerão salvos com segurança.") },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout(onLogout)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_logout_button")
                ) {
                    Text("Sair")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLogoutDialog = false },
                    modifier = Modifier.testTag("cancel_logout_button")
                ) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Diálogo de Confirmação de Importação de Backup
    if (pendingImportEnvelope != null) {
        val envelope = pendingImportEnvelope!!
        AlertDialog(
            onDismissRequest = { pendingImportEnvelope = null },
            title = {
                Text(
                    text = "Restaurar Backup Local",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column {
                    Text("Resumo dos dados encontrados no arquivo:")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("• ${envelope.trips.size} viagens", fontWeight = FontWeight.SemiBold)
                    Text("• ${envelope.tripPoints.size} pontos", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Este backup contém seu histórico local de viagens e localização. Guarde o arquivo em local seguro.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toImport = pendingImportEnvelope
                        pendingImportEnvelope = null
                        if (toImport != null) {
                            viewModel.importBackup(
                                envelope = toImport,
                                onSuccess = { },
                                onError = { }
                            )
                        }
                    },
                    modifier = Modifier.testTag("confirm_import_backup_button")
                ) {
                    Text("IMPORTAR")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingImportEnvelope = null },
                    modifier = Modifier.testTag("cancel_import_backup_button")
                ) {
                    Text("CANCELAR")
                }
            }
        )
    }
}

@Composable
fun OnlineStatusBadge(isOnline: Boolean) {
    val color = if (isOnline) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    val text = if (isOnline) "Online" else "Offline"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("online_status_badge")
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
fun InterruptedTripBannerCard(
    trip: LocalTrip,
    pointCount: Int,
    onResume: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("interrupted_trip_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Existe uma viagem interrompida.",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Veículo: ${trip.vehicleName.ifBlank { "Veículo #${trip.vehicleId}" }} • Pontos salvos: $pointCount",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
            )

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onResume,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("resume_interrupted_trip_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("RETOMAR")
                }
                OutlinedButton(
                    onClick = onEnd,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("end_interrupted_trip_button"),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("ENCERRAR")
                }
            }
        }
    }
}

@Composable
fun ActiveTripCard(
    trip: LocalTrip,
    pointCount: Int,
    syncedPoints: Int,
    pendingPoints: Int,
    latestPoint: LocalTripPoint?,
    trackingState: com.phdrive.companion.service.TrackingState = com.phdrive.companion.service.TrackingState.ACTIVE,
    secondsSinceLastPoint: Long = 0L,
    discardedLowAccuracyCount: Int = 0,
    onEndTrip: () -> Unit,
    onCancelTrip: () -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("active_trip_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "VIAGEM EM ANDAMENTO",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp
                    )
                }
                Text(
                    text = timeFormatter.format(Date(trip.startedAt)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Status do Serviço de GPS e Telemetria
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = trip.vehicleName.ifBlank { "Veículo #${trip.vehicleId}" },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val badgeContainerColor = when (trackingState) {
                    com.phdrive.companion.service.TrackingState.ACTIVE -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    com.phdrive.companion.service.TrackingState.RECOVERING,
                    com.phdrive.companion.service.TrackingState.GPS_STALE -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    else -> MaterialTheme.colorScheme.surface
                }
                val badgeContentColor = when (trackingState) {
                    com.phdrive.companion.service.TrackingState.ACTIVE -> MaterialTheme.colorScheme.primary
                    com.phdrive.companion.service.TrackingState.RECOVERING,
                    com.phdrive.companion.service.TrackingState.GPS_STALE -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeContainerColor
                ) {
                    Text(
                        text = "GPS Service: ${trackingState.name}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeContentColor,
                        modifier = Modifier
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("tracking_state_badge")
                    )
                }
            }

            if (trackingState == com.phdrive.companion.service.TrackingState.GPS_STALE ||
                trackingState == com.phdrive.companion.service.TrackingState.RECOVERING ||
                secondsSinceLastPoint > 30
            ) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "GPS sem atualização há ${secondsSinceLastPoint} segundos",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (discardedLowAccuracyCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Pontos baixa precisão descartados (>100m): $discardedLowAccuracyCount",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Distância GPS", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = String.format(Locale.getDefault(), "%.2f km", trip.gpsDistanceKm),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Column {
                    Text("Pontos Coletados", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "$pointCount",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column {
                    Text("Status Sync", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "$syncedPoints / $pointCount",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            if (latestPoint != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Velocidade: ${latestPoint.speedKmh?.let { String.format(Locale.getDefault(), "%.1f km/h", it) } ?: "--"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onEndTrip,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("end_trip_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Encerrar")
                }
                OutlinedButton(
                    onClick = onCancelTrip,
                    modifier = Modifier.testTag("cancel_trip_button"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Cancelar")
                }
            }
        }
    }
}

@Composable
fun SyncSectionCard(
    isOnline: Boolean,
    syncState: SyncState,
    totalPendingPoints: Int,
    totalFailedPoints: Int,
    totalPendingTrips: Int,
    lastSyncAttemptTime: Long?,
    lastSuccessfulSyncTime: Long?,
    lastSyncErrorCode: String? = null,
    workerState: String = "IDLE",
    onSyncNow: () -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sync_section_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SINCRONIZAÇÃO SUPABASE",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 13.sp
                    )
                }

                if (syncState is SyncState.Syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Pontos Pendentes", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "$totalPendingPoints",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (totalPendingPoints > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column {
                    Text("Falhas de Envio", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "$totalFailedPoints",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (totalFailedPoints > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column {
                    Text("Viagens Pendentes", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "$totalPendingTrips",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (totalPendingTrips > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Worker:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = workerState,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (workerState) {
                            "RUNNING" -> MaterialTheme.colorScheme.primary
                            "ENQUEUED" -> MaterialTheme.colorScheme.secondary
                            "SUCCEEDED" -> MaterialTheme.colorScheme.tertiary
                            "FAILED" -> MaterialTheme.colorScheme.error
                            "BLOCKED" -> MaterialTheme.colorScheme.error
                            "CANCELLED" -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.testTag("worker_status_text")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Última tentativa de sync:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = lastSyncAttemptTime?.let { timeFormatter.format(Date(it)) } ?: "--",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("last_sync_attempt_text")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Última sincronização concluída:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = lastSuccessfulSyncTime?.let { timeFormatter.format(Date(it)) } ?: "Nunca",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("last_successful_sync_text")
                    )
                }

                if (!lastSyncErrorCode.isNullOrBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Último erro de sync:", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        Text(
                            text = lastSyncErrorCode,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("last_sync_error_code")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSyncNow,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("sync_now_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sincronizar Agora")
                }
                OutlinedButton(
                    onClick = onSyncNow,
                    modifier = Modifier.testTag("test_worker_button")
                ) {
                    Text("Testar Worker")
                }
            }
        }
    }
}

@Composable
fun VehicleItemCard(
    vehicle: Vehicle,
    isActiveTripVehicle: Boolean,
    hasActiveTrip: Boolean,
    binding: VehicleBluetoothBinding?,
    isDeviceConnected: Boolean,
    onStartTrip: () -> Unit,
    onBindBluetooth: () -> Unit,
    onRemoveBluetooth: () -> Unit,
    onToggleAutoAutomation: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("vehicle_card_${vehicle.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = vehicle.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("vehicle_name_${vehicle.id}")
                    )

                    val details = buildList {
                        if (!vehicle.brand.isNullOrBlank()) add(vehicle.brand.trim())
                        if (!vehicle.model.isNullOrBlank()) add(vehicle.model.trim())
                        if (!vehicle.version.isNullOrBlank()) add(vehicle.version.trim())
                    }.distinct().joinToString(" ")

                    if (details.isNotBlank()) {
                        Text(
                            text = details,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("vehicle_details_${vehicle.id}")
                        )
                    }

                    if (vehicle.year != null && vehicle.year > 0) {
                        Text(
                            text = "Ano ${vehicle.year}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("vehicle_year_${vehicle.id}")
                        )
                    }

                    if (!vehicle.plate.isNullOrBlank()) {
                        Text(
                            text = "Placa: ${vehicle.plate}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("vehicle_plate_${vehicle.id}")
                        )
                    }

                    val odoFormatted = if (vehicle.currentOdometerKm % 1.0 == 0.0) {
                        String.format(Locale.getDefault(), "%.0f", vehicle.currentOdometerKm)
                    } else {
                        String.format(Locale.getDefault(), "%.1f", vehicle.currentOdometerKm)
                    }
                    Text(
                        text = "Odômetro $odoFormatted km",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("vehicle_odometer_${vehicle.id}")
                    )
                }

                if (isActiveTripVehicle) {
                    Text(
                        text = "EM VIAGEM",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontSize = 12.sp
                    )
                } else {
                    Button(
                        onClick = onStartTrip,
                        enabled = !hasActiveTrip,
                        modifier = Modifier.testTag("start_trip_button_${vehicle.id}"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Iniciar")
                    }
                }
            }

            // Sub-bloco: Automação Bluetooth (MICROETAPA 3B.1)
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            // Toggle de Automação
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Automação de viagem:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.Switch(
                    checked = vehicle.isAutoAutomationEnabled,
                    onCheckedChange = onToggleAutoAutomation,
                    modifier = Modifier.testTag("auto_automation_switch_${vehicle.id}")
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "AUTOMAÇÃO BLUETOOTH",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))

            if (binding != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Bluetooth vinculado: ${binding.bluetoothDeviceName}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("bound_device_name_${vehicle.id}")
                        )
                        Text(
                            text = "Identificador: ${binding.bluetoothDeviceAddressMasked}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("bound_device_masked_${vehicle.id}")
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Status: ",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (isDeviceConnected) "Conectado" else "Desconectado",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDeviceConnected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("bound_device_status_${vehicle.id}")
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = onBindBluetooth,
                            modifier = Modifier.testTag("alter_bluetooth_button_${vehicle.id}")
                        ) {
                            Text("ALTERAR", fontSize = 12.sp)
                        }
                        TextButton(
                            onClick = onRemoveBluetooth,
                            modifier = Modifier.testTag("remove_bluetooth_button_${vehicle.id}")
                        ) {
                            Text("REMOVER", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Nenhum dispositivo associado",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = onBindBluetooth,
                        modifier = Modifier.testTag("bind_bluetooth_button_${vehicle.id}")
                    ) {
                        Text("VINCULAR DISPOSITIVO", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun BluetoothAutomationCard(
    connectionState: BluetoothConnectionState,
    connectedDeviceIdentity: ConnectedDeviceIdentity?,
    identifiedVehicle: Vehicle?,
    identificationSource: VehicleIdentificationSource,
    isBluetoothEnabled: Boolean,
    carProjectionState: CarProjectionState,
    connectionSession: VehicleConnectionSession,
    autoTripState: AutoTripState,
    activeTrip: LocalTrip?,
    movementSpeedKmh: Double,
    movementDurationSec: Long,
    movementDistanceM: Double,
    cooldownActive: Boolean,
    cooldownRemainingSec: Long,
    disconnectDurationSec: Long,
    stationaryDurationSec: Long,
    onRequestPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    modifier: Modifier = Modifier,
    bluetoothBindings: List<VehicleBluetoothBinding> = emptyList()
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("bluetooth_automation_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Automação do Veículo",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AUTOMAÇÃO DO VEÍCULO",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                val isAutoEnabled = identifiedVehicle?.isAutoAutomationEnabled == true
                val (statusText, statusColor) = when {
                    !isBluetoothEnabled -> "DESLIGADO" to MaterialTheme.colorScheme.error
                    connectionState == BluetoothConnectionState.PERMISSION_REQUIRED -> "SEM PERMISSÃO" to MaterialTheme.colorScheme.error
                    autoTripState == AutoTripState.TRIP_ACTIVE -> "VIAGEM ATIVA" to MaterialTheme.colorScheme.tertiary
                    autoTripState == AutoTripState.WAITING_FOR_MOVEMENT -> "AGUARDANDO MOVIMENTO" to MaterialTheme.colorScheme.primary
                    autoTripState == AutoTripState.WAITING_FOR_STOP -> "AGUARDANDO PARADA" to MaterialTheme.colorScheme.error
                    autoTripState == AutoTripState.STARTING -> "INICIANDO VIAGEM" to MaterialTheme.colorScheme.tertiary
                    autoTripState == AutoTripState.ENDING -> "ENCERRANDO" to MaterialTheme.colorScheme.error
                    autoTripState == AutoTripState.ARMED -> "ARMADO" to MaterialTheme.colorScheme.primary
                    !isAutoEnabled && identifiedVehicle != null -> "DESATIVADA" to MaterialTheme.colorScheme.onSurfaceVariant
                    carProjectionState == CarProjectionState.PROJECTION -> "PROJEÇÃO ATIVA" to MaterialTheme.colorScheme.tertiary
                    connectionState == BluetoothConnectionState.CONNECTED -> "CONECTADO" to MaterialTheme.colorScheme.tertiary
                    else -> "DESCONECTADO" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("bluetooth_status_badge")
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Modo Automático
            val isAutoModeActive = identifiedVehicle?.isAutoAutomationEnabled == true
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Modo automático:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = if (isAutoModeActive) "ATIVO" else "DESATIVADO",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isAutoModeActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("auto_mode_status_text")
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Veículo
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Veículo:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = identifiedVehicle?.name ?: "Nenhum veículo identificado",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (identifiedVehicle != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("bluetooth_identified_vehicle")
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Fonte de Identificação
            val sourceLabel = when (identificationSource) {
                VehicleIdentificationSource.BLUETOOTH -> "BLUETOOTH"
                VehicleIdentificationSource.ANDROID_AUTO -> "ANDROID_AUTO"
                VehicleIdentificationSource.SESSAO_PERSISTIDA -> "SESSÃO_PERSISTIDA"
                VehicleIdentificationSource.NENHUMA -> "NENHUMA"
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Fonte de identificação:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = sourceLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (identificationSource != VehicleIdentificationSource.NENHUMA) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("identification_source_text")
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Estado da Automação
            val humanStateLabel = when {
                identifiedVehicle == null -> "AGUARDANDO VEÍCULO"
                autoTripState == AutoTripState.WAITING_FOR_MOVEMENT || autoTripState == AutoTripState.ARMED -> "AGUARDANDO MOVIMENTO"
                autoTripState == AutoTripState.STARTING -> "INICIANDO VIAGEM"
                autoTripState == AutoTripState.TRIP_ACTIVE && activeTrip?.startMode == TripStartMode.AUTOMATIC -> "VIAGEM AUTOMÁTICA ATIVA"
                autoTripState == AutoTripState.TRIP_ACTIVE && activeTrip?.startMode == TripStartMode.MANUAL -> "VIAGEM MANUAL ATIVA"
                autoTripState == AutoTripState.WAITING_FOR_STOP -> "AGUARDANDO PARADA"
                autoTripState == AutoTripState.ENDING -> "ENCERRANDO"
                autoTripState == AutoTripState.DISABLED || !isAutoModeActive -> "DESATIVADA"
                carProjectionState == CarProjectionState.NOT_CONNECTED -> "DESCONECTADO"
                else -> "${autoTripState}"
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Estado:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = humanStateLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        humanStateLabel.contains("ATIVA", ignoreCase = true) -> MaterialTheme.colorScheme.tertiary
                        humanStateLabel.contains("AGUARDANDO", ignoreCase = true) -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag("automation_human_state_text")
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Bluetooth
            val btStatusLabel = when {
                !isBluetoothEnabled -> "Desligado"
                connectionSession.sessionState == SessionState.PROJECTED && connectionState != BluetoothConnectionState.CONNECTED -> "Handshake concluído"
                connectionState == BluetoothConnectionState.CONNECTED -> "CONECTADO"
                connectionState == BluetoothConnectionState.PERMISSION_REQUIRED -> "Sem permissão"
                else -> "DESCONECTADO"
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Bluetooth:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = btStatusLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (btStatusLabel.contains("concluído", ignoreCase = true) || btStatusLabel == "CONECTADO") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("bluetooth_status_label")
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Android Auto
            val aaStatusLabel = when (carProjectionState) {
                CarProjectionState.PROJECTION -> "CONECTADO"
                CarProjectionState.NATIVE -> "NATIVE"
                CarProjectionState.NOT_CONNECTED -> "DESCONECTADO"
                CarProjectionState.UNKNOWN -> "DESCONHECIDO"
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Android Auto:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = aaStatusLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (carProjectionState == CarProjectionState.PROJECTION) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("android_auto_status_label")
                )
            }

            // Seção: Status Técnico Debug (Homologação 3B.2)
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "STATUS TÉCNICO HOMOLOGAÇÃO",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))

            val currentDeviceName = connectedDeviceIdentity?.name ?: connectionSession.bluetoothDeviceName ?: "none"
            val currentDeviceHash = connectedDeviceIdentity?.addressHash ?: connectionSession.bluetoothDeviceAddressHash ?: "none"
            val resolvedBinding = bluetoothBindings.find { it.bluetoothDeviceAddressHash == currentDeviceHash }?.vehicleId ?: "none"

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Bluetooth connected device:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(currentDeviceName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Bluetooth address hash:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(currentDeviceHash, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Bindings encontrados:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${bluetoothBindings.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Binding resolvido:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(resolvedBinding, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Projection:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (carProjectionState == CarProjectionState.PROJECTION) "CONNECTED" else "DISCONNECTED", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Identified vehicle:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(identifiedVehicle?.let { "${it.id}/${it.name}" } ?: "none", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Automation enabled for identified vehicle:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${identifiedVehicle?.isAutoAutomationEnabled ?: false}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Resolved auto mode:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (identifiedVehicle?.isAutoAutomationEnabled == true) "ENABLED" else "DISABLED", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Last identification reason:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${identificationSource.name}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }

            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(6.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("AutoTripState:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${autoTripState}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Movement speed:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(String.format(Locale.US, "%.1f km/h", movementSpeedKmh), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Movement duration:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${movementDurationSec}s", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Movement distance:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(String.format(Locale.US, "%.1f m", movementDistanceM), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Active trip:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(activeTrip?.localId?.take(8) ?: "none", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Trip start mode:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(activeTrip?.startMode?.name ?: "NONE", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Cooldown:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (cooldownActive) "${cooldownRemainingSec}s (ATIVO)" else "Inativo", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            if (autoTripState == AutoTripState.WAITING_FOR_STOP) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Disconnect duration:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${disconnectDurationSec}s", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Stationary duration:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${stationaryDurationSec}s", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }

            if (!isBluetoothEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onOpenBluetoothSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_bluetooth_settings_button")
                ) {
                    Text("ABRIR CONFIGURAÇÕES BLUETOOTH")
                }
            } else if (connectionState == BluetoothConnectionState.PERMISSION_REQUIRED) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("request_bluetooth_permission_button")
                ) {
                    Text("CONCEDER PERMISSÃO BLUETOOTH")
                }
            }
        }
    }
}

@Composable
fun BindBluetoothDeviceDialog(
    vehicle: Vehicle,
    pairedDevices: List<PairedBluetoothDevice>,
    isBluetoothEnabled: Boolean,
    hasPermission: Boolean,
    onDismiss: () -> Unit,
    onRequestPermission: () -> Unit,
    onConfirm: (PairedBluetoothDevice) -> Unit
) {
    var selectedDevice by remember { mutableStateOf<PairedBluetoothDevice?>(pairedDevices.firstOrNull()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vincular Bluetooth - ${vehicle.name}") },
        text = {
            Column {
                if (!isBluetoothEnabled) {
                    Text(
                        text = "Bluetooth desligado. Ative o Bluetooth no dispositivo para selecionar dispositivos pareados.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp
                    )
                } else if (!hasPermission) {
                    Text(
                        text = "Permissão Bluetooth necessária para identificar dispositivos pareados.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onRequestPermission) {
                        Text("Conceder Permissão")
                    }
                } else if (pairedDevices.isEmpty()) {
                    Text(
                        text = "Nenhum dispositivo Bluetooth pareado encontrado no sistema.\n\nPareie seu dispositivo no menu Bluetooth do Android e tente novamente.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Selecione o dispositivo Bluetooth que será associado a este veículo:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pairedDevices.forEach { device ->
                            val isSelected = selectedDevice?.addressHash == device.addressHash
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedDevice = device }
                                    .testTag("device_item_${device.addressHash}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = device.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = device.maskedAddress,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selecionado",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isBluetoothEnabled && hasPermission && pairedDevices.isNotEmpty()) {
                Button(
                    onClick = {
                        selectedDevice?.let { onConfirm(it) }
                    },
                    enabled = selectedDevice != null,
                    modifier = Modifier.testTag("confirm_bind_bluetooth_button")
                ) {
                    Text("Confirmar Vínculo")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun StartTripDialog(
    vehicle: Vehicle,
    onDismiss: () -> Unit,
    onConfirm: (Double?) -> Unit
) {
    var odometerText by remember { mutableStateOf(vehicle.currentOdometerKm.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Iniciar Viagem - ${vehicle.name}") },
        text = {
            Column {
                Text("Informe o odômetro inicial (km):")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = odometerText,
                    onValueChange = { odometerText = it },
                    label = { Text("Odômetro Inicial (km)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("start_odometer_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val odo = odometerText.toDoubleOrNull()
                    onConfirm(odo)
                },
                modifier = Modifier.testTag("confirm_start_trip_button")
            ) {
                Text("Iniciar Viagem")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun EndTripDialog(
    trip: LocalTrip,
    onDismiss: () -> Unit,
    onConfirm: (Double?) -> Unit
) {
    var odometerText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Encerrar Viagem") },
        text = {
            Column {
                Text("Veículo: ${trip.vehicleName}")
                Text("Distância percorrida (GPS): ${String.format(Locale.getDefault(), "%.2f km", trip.gpsDistanceKm)}")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = odometerText,
                    onValueChange = { odometerText = it },
                    label = { Text("Odômetro Final (km) - Opcional") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("end_odometer_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val odo = odometerText.toDoubleOrNull()
                    onConfirm(odo)
                },
                modifier = Modifier.testTag("confirm_end_trip_button")
            ) {
                Text("Concluir Viagem")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Voltar")
            }
        }
    )
}

@Composable
fun DataProtectionCard(
    totalTrips: Int,
    totalPoints: Int,
    pendingPoints: Int,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("data_protection_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Proteção de dados",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Resumo
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Viagens locais:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "$totalTrips",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("total_local_trips_count")
                    )
                }

                Column {
                    Text(
                        text = "Pontos locais:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "$totalPoints",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("total_local_points_count")
                    )
                }

                Column {
                    Text(
                        text = "Pendentes:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "$pendingPoints",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("total_pending_data_count")
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Este backup contém seu histórico local de viagens e localização. Guarde o arquivo em local seguro.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onExportBackup,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("export_backup_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "EXPORTAR BACKUP LOCAL",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onImportBackup,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("import_backup_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "IMPORTAR BACKUP LOCAL",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun TripSyncDiagnosticCard(
    diagnostics: List<TripDiagnosticInfo>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("trip_sync_diagnostic_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Diagnóstico",
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Trip Sync Diagnostics (3B.3)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }

                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Colapsar" else "Expandir",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                if (diagnostics.isEmpty()) {
                    Text(
                        text = "Nenhuma viagem registrada no Room.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    )
                } else {
                    diagnostics.forEach { diag ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "Trip: ${diag.vehicleName} (${diag.vehicleId})",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            SelectionContainer {
                                Column {
                                    Text("localTripId: ${diag.localTripId}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    Text("remoteTripId: ${diag.remoteTripId ?: "NULL (Não criado/sincronizado)"}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = if (diag.remoteTripId == null) FontWeight.Bold else FontWeight.Normal, color = if (diag.remoteTripId == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                    Text("status: ${diag.status}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text("startedAt: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(diag.startedAt))}", fontSize = 12.sp)
                                    Text("endedAt: ${diag.endedAt?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it)) } ?: "ACTIVE"}", fontSize = 12.sp)
                                    Text("syncStatus: ${diag.syncStatus}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (diag.syncStatus == "SYNCED") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Total Points: ${diag.totalPoints}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("PENDING: ${diag.pendingPoints}", fontSize = 12.sp, color = if (diag.pendingPoints > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                Text("SYNCED: ${diag.syncedPoints}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                Text("FAILED: ${diag.failedPoints}", fontSize = 12.sp, color = if (diag.failedPoints > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

