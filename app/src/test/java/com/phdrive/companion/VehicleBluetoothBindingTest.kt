package com.phdrive.companion

import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phdrive.companion.bluetooth.BluetoothConnectionMonitor
import com.phdrive.companion.bluetooth.BluetoothConnectionState
import com.phdrive.companion.bluetooth.SessionState
import com.phdrive.companion.car.CarProjectionMonitor
import com.phdrive.companion.car.CarProjectionState
import com.phdrive.companion.data.local.PhDriveDatabase
import com.phdrive.companion.data.remote.model.TripInsertDto
import com.phdrive.companion.data.remote.model.TripPointInsertDto
import com.phdrive.companion.data.remote.model.TripUpdateDto
import com.phdrive.companion.model.Vehicle
import com.phdrive.companion.model.VehicleBluetoothBinding
import com.phdrive.companion.repository.AuthRepository
import com.phdrive.companion.repository.TripRepository
import com.phdrive.companion.repository.VehicleBluetoothRepository
import com.phdrive.companion.repository.VehicleRepository
import kotlinx.coroutines.flow.first
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothDevice

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VehicleBluetoothBindingTest {

    private lateinit var context: Context
    private lateinit var database: PhDriveDatabase
    private lateinit var bluetoothRepo: VehicleBluetoothRepository
    private lateinit var vehicleRepo: VehicleRepository
    private lateinit var tripRepo: TripRepository
    private lateinit var carProjectionMonitor: CarProjectionMonitor

    private val testUserId = "user-test-123"
    private val titanVehicleId = "veh-titan-125"
    private val factorVehicleId = "veh-factor-150"

    private val testMacAddress = "AA:BB:CC:DD:EE:01"
    private val testDeviceName = "CAR_BT_36A152"

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PhDriveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        PhDriveDatabase.setInstanceForTesting(database)
        AuthRepository.init(context)

        bluetoothRepo = VehicleBluetoothRepository(database)
        vehicleRepo = VehicleRepository(database)
        tripRepo = TripRepository(database)
        carProjectionMonitor = CarProjectionMonitor(context)
        CarProjectionMonitor.setInstanceForTesting(carProjectionMonitor)

        // Cadastrar veículos de teste
        vehicleRepo.saveVehicle(
            Vehicle(
                id = titanVehicleId,
                name = "Titan 125",
                model = "Honda CG 125",
                userId = testUserId,
                currentOdometerKm = 1000.0
            )
        )
        vehicleRepo.saveVehicle(
            Vehicle(
                id = factorVehicleId,
                name = "Yamaha Factor 150",
                model = "Factor 150",
                userId = testUserId,
                currentOdometerKm = 5000.0
            )
        )
    }

    @After
    fun tearDown() {
        PhDriveDatabase.setInstanceForTesting(null)
        CarProjectionMonitor.setInstanceForTesting(null)
        database.close()
    }

    /**
     * 1. Teste: Vínculo é persistido no Room
     */
    @Test
    fun testBindingIsPersistedInRoom() = runBlocking {
        val result = bluetoothRepo.bindDeviceToVehicle(
            userId = testUserId,
            vehicleId = titanVehicleId,
            deviceName = testDeviceName,
            deviceAddress = testMacAddress
        )

        assertTrue("Vínculo deve ser criado com sucesso", result.isSuccess)
        val created = result.getOrThrow()
        assertEquals(titanVehicleId, created.vehicleId)
        assertEquals(testDeviceName, created.bluetoothDeviceName)
        assertEquals(VehicleBluetoothBinding.hashAddress(testMacAddress), created.bluetoothDeviceAddressHash)
        assertEquals("••:••:••:EE:01", created.bluetoothDeviceAddressMasked)
        assertTrue(created.active)

        val activeBinding = bluetoothRepo.getActiveBindingForVehicle(titanVehicleId)
        assertNotNull(activeBinding)
        assertEquals(created.id, activeBinding?.id)
        assertEquals(testDeviceName, activeBinding?.bluetoothDeviceName)
    }

    /**
     * 2. Teste: App restart mantém vínculo (persistência Room)
     */
    @Test
    fun testAppRestartMaintainsBinding() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(
            userId = testUserId,
            vehicleId = titanVehicleId,
            deviceName = testDeviceName,
            deviceAddress = testMacAddress
        )

        // Simular nova instância de repositório acessando o mesmo banco
        val newRepoInstance = VehicleBluetoothRepository(database)
        val bindings = newRepoInstance.observeBindingsForVehicle(titanVehicleId).first()

        assertEquals(1, bindings.size)
        assertEquals("Titan 125", vehicleRepo.getVehicleById(bindings.first().vehicleId)?.name)
        assertEquals(testDeviceName, bindings.first().bluetoothDeviceName)
    }

    /**
     * 3. Teste: Dispositivo conectado identifica o veículo correto (Handshake inicial)
     */
    @Test
    fun testConnectedDeviceIdentifiesCorrectVehicle() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(
            userId = testUserId,
            vehicleId = titanVehicleId,
            deviceName = testDeviceName,
            deviceAddress = testMacAddress
        )

        val monitor = BluetoothConnectionMonitor(context, bluetoothRepo, vehicleRepo, carProjectionMonitor)

        // Simular dispositivo conectado
        val mockDevice = ShadowBluetoothDevice.newInstance(testMacAddress)
        monitor.handleDeviceConnected(mockDevice)

        kotlinx.coroutines.delay(200)

        assertEquals(BluetoothConnectionState.CONNECTED, monitor.connectionState.value)
        assertNotNull(monitor.connectedDeviceIdentity.value)
        assertEquals(VehicleBluetoothBinding.hashAddress(testMacAddress), monitor.connectedDeviceIdentity.value?.addressHash)

        val identified = monitor.identifiedVehicle.value
        assertNotNull("Veículo deve ser identificado", identified)
        assertEquals(titanVehicleId, identified?.id)
        assertEquals("Titan 125", identified?.name)
        assertEquals("Veículo identificado: Titan 125", monitor.userFeedbackMessage.value)

        // Verificar candidato na sessão
        val session = monitor.connectionSession.value
        assertEquals(titanVehicleId, session.candidateVehicleId)
        assertEquals("Titan 125", session.candidateVehicleName)
        assertEquals(SessionState.HANDSHAKE, session.sessionState)
    }

    /**
     * 4. Teste: Dispositivo desconhecido não identifica veículo
     */
    @Test
    fun testUnknownDeviceDoesNotIdentifyVehicle() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(
            userId = testUserId,
            vehicleId = titanVehicleId,
            deviceName = testDeviceName,
            deviceAddress = testMacAddress
        )

        val monitor = BluetoothConnectionMonitor(context, bluetoothRepo, vehicleRepo, carProjectionMonitor)

        val unknownMac = "11:22:33:44:55:66"
        val unknownDevice = ShadowBluetoothDevice.newInstance(unknownMac)
        monitor.handleDeviceConnected(unknownDevice)

        kotlinx.coroutines.delay(200)

        assertEquals(BluetoothConnectionState.CONNECTED, monitor.connectionState.value)
        assertNull("Veículo desconhecido não deve ser identificado", monitor.identifiedVehicle.value)
    }

    /**
     * 5. Teste: Desconexão sem Android Auto atualiza estado para DISCONNECTED
     */
    @Test
    fun testDisconnectionWithoutProjectionUpdatesState() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(
            userId = testUserId,
            vehicleId = titanVehicleId,
            deviceName = testDeviceName,
            deviceAddress = testMacAddress
        )

        val monitor = BluetoothConnectionMonitor(context, bluetoothRepo, vehicleRepo, carProjectionMonitor)
        val mockDevice = ShadowBluetoothDevice.newInstance(testMacAddress)

        monitor.handleDeviceConnected(mockDevice)
        kotlinx.coroutines.delay(200)
        assertEquals("Titan 125", monitor.identifiedVehicle.value?.name)

        monitor.handleDeviceDisconnected(mockDevice)
        kotlinx.coroutines.delay(100)

        assertEquals(BluetoothConnectionState.DISCONNECTED, monitor.connectionState.value)
        assertNull(monitor.connectedDeviceIdentity.value)
        assertNull(monitor.identifiedVehicle.value)
        assertEquals(SessionState.IDLE, monitor.connectionSession.value.sessionState)
        assertEquals("Bluetooth do veículo desconectado", monitor.userFeedbackMessage.value)
    }

    /**
     * 6. Teste: Bluetooth CONNECTED + PROJECTION -> Titan 125 confirmada e estado PROJECTED
     */
    @Test
    fun testBluetoothConnectedPlusProjectionConfirmsSession() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(
            userId = testUserId,
            vehicleId = titanVehicleId,
            deviceName = testDeviceName,
            deviceAddress = testMacAddress
        )

        val monitor = BluetoothConnectionMonitor(context, bluetoothRepo, vehicleRepo, carProjectionMonitor)
        val mockDevice = ShadowBluetoothDevice.newInstance(testMacAddress)

        // 1. Conexão Bluetooth (Handshake)
        monitor.handleDeviceConnected(mockDevice)
        kotlinx.coroutines.delay(100)

        // 2. Android Auto inicia PROJECTION
        carProjectionMonitor.setProjectionStateForTesting(CarProjectionState.PROJECTION)
        monitor.handleProjectionStateChange(CarProjectionState.PROJECTION)
        kotlinx.coroutines.delay(100)

        val session = monitor.connectionSession.value
        assertEquals(SessionState.PROJECTED, session.sessionState)
        assertTrue(session.projectionActive)
        assertEquals(titanVehicleId, session.identifiedVehicleId)
        assertEquals("Titan 125", monitor.identifiedVehicle.value?.name)
    }

    /**
     * 7. Teste: PROJECTION ativa + Bluetooth DISCONNECTED -> Titan 125 CONTINUA IDENTIFICADA (Regra de Ouro 3B.1)
     */
    @Test
    fun testProjectionActivePlusBluetoothDisconnectedMaintainsTitanIdentified() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(
            userId = testUserId,
            vehicleId = titanVehicleId,
            deviceName = testDeviceName,
            deviceAddress = testMacAddress
        )

        val monitor = BluetoothConnectionMonitor(context, bluetoothRepo, vehicleRepo, carProjectionMonitor)
        val mockDevice = ShadowBluetoothDevice.newInstance(testMacAddress)

        // 1. Handshake Bluetooth
        monitor.handleDeviceConnected(mockDevice)
        kotlinx.coroutines.delay(100)

        // 2. Android Auto Wireless conecta
        carProjectionMonitor.setProjectionStateForTesting(CarProjectionState.PROJECTION)
        monitor.handleProjectionStateChange(CarProjectionState.PROJECTION)
        kotlinx.coroutines.delay(100)

        // 3. Central multimídia desconecta Bluetooth para liberar canal Wi-Fi
        monitor.handleDeviceDisconnected(mockDevice)
        kotlinx.coroutines.delay(100)

        // Verificação: Titan 125 NÃO DEVE SER DESCONECTADA!
        assertEquals(BluetoothConnectionState.DISCONNECTED, monitor.connectionState.value)
        assertNotNull("Veículo Titan 125 DEVE continuar identificado!", monitor.identifiedVehicle.value)
        assertEquals("Titan 125", monitor.identifiedVehicle.value?.name)
        assertEquals(SessionState.PROJECTED, monitor.connectionSession.value.sessionState)
        assertTrue(monitor.connectionSession.value.projectionActive)
        assertEquals("Handshake Bluetooth concluído / Projeção ativa", monitor.userFeedbackMessage.value)
    }

    /**
     * 8. Teste: PROJECTION NOT_CONNECTED aguarda debounce de 15 segundos
     */
    @Test
    fun testProjectionNotConnectedDebounceAndTermination() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(
            userId = testUserId,
            vehicleId = titanVehicleId,
            deviceName = testDeviceName,
            deviceAddress = testMacAddress
        )

        val monitor = BluetoothConnectionMonitor(context, bluetoothRepo, vehicleRepo, carProjectionMonitor)
        val mockDevice = ShadowBluetoothDevice.newInstance(testMacAddress)

        monitor.handleDeviceConnected(mockDevice)
        kotlinx.coroutines.delay(150)
        carProjectionMonitor.setProjectionStateForTesting(CarProjectionState.PROJECTION)
        monitor.handleProjectionStateChange(CarProjectionState.PROJECTION)
        kotlinx.coroutines.delay(150)
        monitor.handleDeviceDisconnected(mockDevice)
        kotlinx.coroutines.delay(200)

        assertEquals("Titan 125", monitor.identifiedVehicle.value?.name)

        // 1. Desconectar Android Auto -> Deve entrar em DISCONNECTING imediatamente
        carProjectionMonitor.setProjectionStateForTesting(CarProjectionState.NOT_CONNECTED)
        monitor.handleProjectionStateChange(CarProjectionState.NOT_CONNECTED)
        kotlinx.coroutines.delay(200)

        assertEquals(SessionState.DISCONNECTING, monitor.connectionSession.value.sessionState)
        // Durante o debounce, veículo ainda permanece para evitar perda de dados por oscilação
        assertNotNull(monitor.identifiedVehicle.value)

        // 2. Aguardar o debounce de 15 segundos (simulado com delay)
        kotlinx.coroutines.delay(16_000L)

        // Após 15s de desconexão contínua: sessão encerrada
        assertEquals(SessionState.IDLE, monitor.connectionSession.value.sessionState)
        assertNull("Identificação deve ser limpa após término do debounce", monitor.identifiedVehicle.value)
        assertEquals("Conexão com o veículo encerrada", monitor.userFeedbackMessage.value)
    }

    /**
     * 9. Teste: Bluetooth reconecta durante debounce -> Não encerra sessão
     */
    @Test
    fun testBluetoothReconnectsDuringDebounceKeepsSession() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(
            userId = testUserId,
            vehicleId = titanVehicleId,
            deviceName = testDeviceName,
            deviceAddress = testMacAddress
        )

        val monitor = BluetoothConnectionMonitor(context, bluetoothRepo, vehicleRepo, carProjectionMonitor)
        val mockDevice = ShadowBluetoothDevice.newInstance(testMacAddress)

        monitor.handleDeviceConnected(mockDevice)
        kotlinx.coroutines.delay(150)
        carProjectionMonitor.setProjectionStateForTesting(CarProjectionState.PROJECTION)
        monitor.handleProjectionStateChange(CarProjectionState.PROJECTION)
        kotlinx.coroutines.delay(150)
        monitor.handleDeviceDisconnected(mockDevice)
        kotlinx.coroutines.delay(200)

        // Android Auto oscila para NOT_CONNECTED
        carProjectionMonitor.setProjectionStateForTesting(CarProjectionState.NOT_CONNECTED)
        monitor.handleProjectionStateChange(CarProjectionState.NOT_CONNECTED)
        kotlinx.coroutines.delay(200)
        assertEquals(SessionState.DISCONNECTING, monitor.connectionSession.value.sessionState)

        // Bluetooth reconecta rapidamente (dentro dos 15s)
        kotlinx.coroutines.delay(1000)
        monitor.handleDeviceConnected(mockDevice)
        kotlinx.coroutines.delay(200)

        // Aguardar o tempo que completaria 15s do timer original
        kotlinx.coroutines.delay(15_000L)

        // Veículo deve continuar identificado!
        assertNotNull("Veículo deve permanecer identificado", monitor.identifiedVehicle.value)
        assertEquals("Titan 125", monitor.identifiedVehicle.value?.name)
    }

    /**
     * 10. Teste: Dois veículos vinculados -> Projeção sozinha sem handshake não escolhe arbitrariamente
     */
    @Test
    fun testMultipleVehiclesBoundDoesNotArbitrarilyPickOnProjectionAlone() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(
            userId = testUserId,
            vehicleId = titanVehicleId,
            deviceName = "CAR_BT_TITAN",
            deviceAddress = "AA:BB:CC:DD:EE:01"
        )
        bluetoothRepo.bindDeviceToVehicle(
            userId = testUserId,
            vehicleId = factorVehicleId,
            deviceName = "CAR_BT_FACTOR",
            deviceAddress = "AA:BB:CC:DD:EE:02"
        )

        val monitor = BluetoothConnectionMonitor(context, bluetoothRepo, vehicleRepo, carProjectionMonitor)

        // Android Auto conecta sem nenhum handshake Bluetooth prévio
        carProjectionMonitor.setProjectionStateForTesting(CarProjectionState.PROJECTION)
        monitor.handleProjectionStateChange(CarProjectionState.PROJECTION)
        kotlinx.coroutines.delay(200)

        // Não deve escolher aleatoriamente nenhum veículo
        assertNull("Sem handshake e com múltiplos veículos, não deve assumir nenhum veículo arbitrariamente", monitor.identifiedVehicle.value)
    }

    /**
     * 11. Teste: Recuperação ao reabrir app com Android Auto ativo e 1 único veículo cadastrado
     */
    @Test
    fun testSingleVehicleRecoveryOnAppReopen() = runBlocking {
        // Apenas 1 veículo vinculado
        bluetoothRepo.bindDeviceToVehicle(
            userId = testUserId,
            vehicleId = titanVehicleId,
            deviceName = testDeviceName,
            deviceAddress = testMacAddress
        )

        val monitor = BluetoothConnectionMonitor(context, bluetoothRepo, vehicleRepo, carProjectionMonitor)

        // App reabre já com projeção ativa
        carProjectionMonitor.setProjectionStateForTesting(CarProjectionState.PROJECTION)
        monitor.handleProjectionStateChange(CarProjectionState.PROJECTION)
        kotlinx.coroutines.delay(200)

        // Deve recuperar Titan 125 com segurança
        assertNotNull(monitor.identifiedVehicle.value)
        assertEquals("Titan 125", monitor.identifiedVehicle.value?.name)
        assertEquals(SessionState.PROJECTED, monitor.connectionSession.value.sessionState)
    }

    /**
     * 12. Teste: Prevenção de conflito (mesmo device não pode vincular dois veículos)
     */
    @Test
    fun testSameDeviceCannotBindTwoVehicles() = runBlocking {
        val firstBind = bluetoothRepo.bindDeviceToVehicle(
            userId = testUserId,
            vehicleId = titanVehicleId,
            deviceName = testDeviceName,
            deviceAddress = testMacAddress
        )
        assertTrue(firstBind.isSuccess)

        val secondBind = bluetoothRepo.bindDeviceToVehicle(
            userId = testUserId,
            vehicleId = factorVehicleId,
            deviceName = testDeviceName,
            deviceAddress = testMacAddress
        )

        assertTrue("Segunda vinculação com o mesmo dispositivo deve falhar", secondBind.isFailure)
        val exception = secondBind.exceptionOrNull()
        assertTrue(exception is IllegalStateException)
        assertTrue(
            "Mensagem deve informar que já está vinculado ao veículo Titan 125",
            exception?.message?.contains("já está vinculado ao veículo Titan 125") == true
        )
    }

    /**
     * 13. Teste: Nenhuma viagem automática é iniciada nesta etapa
     */
    @Test
    fun testNoAutomaticTripStartedDuringHandshakeOrProjection() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(
            userId = testUserId,
            vehicleId = titanVehicleId,
            deviceName = testDeviceName,
            deviceAddress = testMacAddress
        )

        val monitor = BluetoothConnectionMonitor(context, bluetoothRepo, vehicleRepo, carProjectionMonitor)
        val mockDevice = ShadowBluetoothDevice.newInstance(testMacAddress)

        monitor.handleDeviceConnected(mockDevice)
        carProjectionMonitor.setProjectionStateForTesting(CarProjectionState.PROJECTION)
        monitor.handleProjectionStateChange(CarProjectionState.PROJECTION)
        kotlinx.coroutines.delay(200)

        // Nenhuma viagem deve ser criada no banco
        val activeTrip = tripRepo.observeActiveTrip().first()
        assertNull("Nenhuma viagem deve iniciar automaticamente na microetapa 3B.1!", activeTrip)
    }

    /**
     * 14. Teste: Binding não envia MAC ao Supabase
     */
    @Test
    fun testBindingDoesNotSendMacToSupabase() {
        val tripInsertFields = TripInsertDto::class.java.declaredFields.map { it.name }
        val tripUpdateFields = TripUpdateDto::class.java.declaredFields.map { it.name }
        val pointInsertFields = TripPointInsertDto::class.java.declaredFields.map { it.name }

        assertFalse(tripInsertFields.any { it.contains("mac", ignoreCase = true) || it.contains("bluetooth", ignoreCase = true) })
        assertFalse(tripUpdateFields.any { it.contains("mac", ignoreCase = true) || it.contains("bluetooth", ignoreCase = true) })
        assertFalse(pointInsertFields.any { it.contains("mac", ignoreCase = true) || it.contains("bluetooth", ignoreCase = true) })
    }

    // ==========================================
    // SEÇÃO 12: TESTES OBRIGATÓRIOS DO BUG DE MULTIPLOS VEÍCULOS
    // ==========================================

    @Test
    fun testA_oneVehicleOneBindingIdentifiesCorrectly() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(userId = testUserId, vehicleId = titanVehicleId, deviceName = testDeviceName, deviceAddress = testMacAddress)
        val monitor = BluetoothConnectionMonitor(context, bluetoothRepo, vehicleRepo, carProjectionMonitor)
        val mockDevice = ShadowBluetoothDevice.newInstance(testMacAddress)
        monitor.handleDeviceConnected(mockDevice)
        kotlinx.coroutines.delay(200)
        assertEquals(titanVehicleId, monitor.identifiedVehicle.value?.id)
    }

    @Test
    fun testB_twoVehiclesTwoBindingsTitanConnects() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(userId = testUserId, vehicleId = titanVehicleId, deviceName = "Titan Device", deviceAddress = "AA:BB:CC:DD:EE:01")
        bluetoothRepo.bindDeviceToVehicle(userId = testUserId, vehicleId = factorVehicleId, deviceName = "Verona Device", deviceAddress = "11:22:33:44:55:66")

        val monitor = BluetoothConnectionMonitor(context, bluetoothRepo, vehicleRepo, carProjectionMonitor)
        val mockDevice = ShadowBluetoothDevice.newInstance("AA:BB:CC:DD:EE:01")
        monitor.handleDeviceConnected(mockDevice)
        kotlinx.coroutines.delay(250)

        val identified = monitor.identifiedVehicle.value
        assertNotNull("Titan deve ser identificada", identified)
        assertEquals(titanVehicleId, identified?.id)
    }

    @Test
    fun testC_twoVehiclesTwoBindingsVeronaConnects() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(userId = testUserId, vehicleId = titanVehicleId, deviceName = "Titan Device", deviceAddress = "AA:BB:CC:DD:EE:01")
        bluetoothRepo.bindDeviceToVehicle(userId = testUserId, vehicleId = factorVehicleId, deviceName = "Verona Device", deviceAddress = "11:22:33:44:55:66")

        val monitor = BluetoothConnectionMonitor(context, bluetoothRepo, vehicleRepo, carProjectionMonitor)
        val mockDevice = ShadowBluetoothDevice.newInstance("11:22:33:44:55:66")
        monitor.handleDeviceConnected(mockDevice)
        kotlinx.coroutines.delay(250)

        val identified = monitor.identifiedVehicle.value
        assertNotNull("Verona deve ser identificada", identified)
        assertEquals(factorVehicleId, identified?.id)
    }

    @Test
    fun testD_unknownBluetoothDevice() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(userId = testUserId, vehicleId = titanVehicleId, deviceName = "Titan Device", deviceAddress = "AA:BB:CC:DD:EE:01")
        val monitor = BluetoothConnectionMonitor(context, bluetoothRepo, vehicleRepo, carProjectionMonitor)
        val mockDevice = ShadowBluetoothDevice.newInstance("99:99:99:99:99:99")
        monitor.handleDeviceConnected(mockDevice)
        kotlinx.coroutines.delay(200)
        assertNull("Bluetooth desconhecido deve retornar none/null", monitor.identifiedVehicle.value)
    }

    @Test
    fun testE_projectionAfterBluetoothKeepsIdentified() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(userId = testUserId, vehicleId = titanVehicleId, deviceName = "Titan Device", deviceAddress = "AA:BB:CC:DD:EE:01")
        val monitor = BluetoothConnectionMonitor(context, bluetoothRepo, vehicleRepo, carProjectionMonitor)
        val mockDevice = ShadowBluetoothDevice.newInstance("AA:BB:CC:DD:EE:01")

        monitor.handleDeviceConnected(mockDevice)
        kotlinx.coroutines.delay(100)
        assertEquals(titanVehicleId, monitor.identifiedVehicle.value?.id)

        carProjectionMonitor.setProjectionStateForTesting(CarProjectionState.PROJECTION)
        monitor.handleProjectionStateChange(CarProjectionState.PROJECTION)
        kotlinx.coroutines.delay(100)

        assertEquals(titanVehicleId, monitor.identifiedVehicle.value?.id)
    }

    @Test
    fun testF_projectionDisconnectPreservesComportamento() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(userId = testUserId, vehicleId = titanVehicleId, deviceName = "Titan Device", deviceAddress = "AA:BB:CC:DD:EE:01")
        val monitor = BluetoothConnectionMonitor(context, bluetoothRepo, vehicleRepo, carProjectionMonitor)
        val mockDevice = ShadowBluetoothDevice.newInstance("AA:BB:CC:DD:EE:01")

        monitor.handleDeviceConnected(mockDevice)
        carProjectionMonitor.setProjectionStateForTesting(CarProjectionState.PROJECTION)
        monitor.handleProjectionStateChange(CarProjectionState.PROJECTION)
        kotlinx.coroutines.delay(100)

        // AA desconecta
        carProjectionMonitor.setProjectionStateForTesting(CarProjectionState.NOT_CONNECTED)
        monitor.handleProjectionStateChange(CarProjectionState.NOT_CONNECTED)
        kotlinx.coroutines.delay(100)

        assertEquals(com.phdrive.companion.bluetooth.SessionState.DISCONNECTING, monitor.connectionSession.value.sessionState)
    }

    @Test
    fun testG_secondBindingDoesNotOverwriteFirst() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(userId = testUserId, vehicleId = titanVehicleId, deviceName = "Titan Device", deviceAddress = "AA:BB:CC:DD:EE:01")
        bluetoothRepo.bindDeviceToVehicle(userId = testUserId, vehicleId = factorVehicleId, deviceName = "Verona Device", deviceAddress = "11:22:33:44:55:66")

        val b1 = bluetoothRepo.getActiveBindingForVehicle(titanVehicleId)
        val b2 = bluetoothRepo.getActiveBindingForVehicle(factorVehicleId)

        assertNotNull(b1)
        assertNotNull(b2)
        assertEquals("AA:BB:CC:DD:EE:01", b1?.bluetoothDeviceAddress)
        assertEquals("11:22:33:44:55:66", b2?.bluetoothDeviceAddress)
    }

    @Test
    fun testH_automationEnabledTitan() = runBlocking {
        val titan = vehicleRepo.getVehicleById(titanVehicleId)
        assertNotNull(titan)
        val titanUpdated = titan!!.copy(isAutoAutomationEnabled = true)
        vehicleRepo.saveVehicle(titanUpdated)

        val retrieved = vehicleRepo.getVehicleById(titanVehicleId)
        assertTrue(retrieved?.isAutoAutomationEnabled == true)
    }

    @Test
    fun testI_automationDisabledTitan() = runBlocking {
        val titan = vehicleRepo.getVehicleById(titanVehicleId)
        assertNotNull(titan)
        val titanUpdated = titan!!.copy(isAutoAutomationEnabled = false)
        vehicleRepo.saveVehicle(titanUpdated)

        val retrieved = vehicleRepo.getVehicleById(titanVehicleId)
        assertFalse(retrieved?.isAutoAutomationEnabled == true)
    }

    @Test
    fun testJ_autoStartTitanAUTOMATIC() = runBlocking {
        val trip = tripRepo.startTripAutomatic(
            vehicleId = titanVehicleId,
            vehicleName = "Titan 125",
            userId = testUserId,
            startOdometerKm = 1000.0
        )
        assertEquals(com.phdrive.companion.model.TripStartMode.AUTOMATIC, trip.startMode)
    }

    @Test
    fun testK_manualStartMANUAL() = runBlocking {
        val trip = tripRepo.startTrip(
            vehicleId = titanVehicleId,
            vehicleName = "Titan 125",
            userId = testUserId,
            startOdometerKm = 1000.0
        )
        assertEquals(com.phdrive.companion.model.TripStartMode.MANUAL, trip.startMode)
    }

    @Test
    fun testL_swapTitanToVeronaUpdatesCorrectly() = runBlocking {
        bluetoothRepo.bindDeviceToVehicle(userId = testUserId, vehicleId = titanVehicleId, deviceName = "Titan Device", deviceAddress = "AA:BB:CC:DD:EE:01")
        bluetoothRepo.bindDeviceToVehicle(userId = testUserId, vehicleId = factorVehicleId, deviceName = "Verona Device", deviceAddress = "11:22:33:44:55:66")

        val monitor = BluetoothConnectionMonitor(context, bluetoothRepo, vehicleRepo, carProjectionMonitor)

        // 1. Titan conecta
        val devTitan = ShadowBluetoothDevice.newInstance("AA:BB:CC:DD:EE:01")
        monitor.handleDeviceConnected(devTitan)
        kotlinx.coroutines.delay(200)
        assertEquals(titanVehicleId, monitor.identifiedVehicle.value?.id)

        // 2. Titan desconecta
        monitor.handleDeviceDisconnected(devTitan)
        kotlinx.coroutines.delay(100)
        assertNull(monitor.identifiedVehicle.value)

        // 3. Verona conecta
        val devVerona = ShadowBluetoothDevice.newInstance("11:22:33:44:55:66")
        monitor.handleDeviceConnected(devVerona)
        kotlinx.coroutines.delay(200)
        assertEquals(factorVehicleId, monitor.identifiedVehicle.value?.id)
    }

    @Test
    fun testM_noSharedGlobalState() = runBlocking {
        val b1 = bluetoothRepo.bindDeviceToVehicle(userId = testUserId, vehicleId = titanVehicleId, deviceName = "Titan Device", deviceAddress = "AA:BB:CC:DD:EE:01")
        val b2 = bluetoothRepo.bindDeviceToVehicle(userId = testUserId, vehicleId = factorVehicleId, deviceName = "Verona Device", deviceAddress = "11:22:33:44:55:66")

        assertTrue(b1.isSuccess)
        assertTrue(b2.isSuccess)

        val bindings = bluetoothRepo.getAllActiveBindings()
        assertEquals(2, bindings.size)
        assertTrue(bindings.any { it.vehicleId == titanVehicleId })
        assertTrue(bindings.any { it.vehicleId == factorVehicleId })
    }
}
