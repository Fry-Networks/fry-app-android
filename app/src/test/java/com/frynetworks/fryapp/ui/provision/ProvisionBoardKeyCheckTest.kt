package com.frynetworks.fryapp.ui.provision

import com.frynetworks.fryapp.ble.BleDeviceInfo
import com.frynetworks.fryapp.ble.BleProvisioner
import com.frynetworks.fryapp.ble.DeviceStatusJson
import com.frynetworks.fryapp.ble.ProvState
import com.frynetworks.fryapp.ble.ProvisionEvent
import com.frynetworks.fryapp.data.DeviceRepository
import com.frynetworks.fryapp.data.SettingsRepository
import com.frynetworks.fryapp.data.Transport
import com.frynetworks.fryapp.data.keys.KeyCheckResult
import com.frynetworks.fryapp.data.keys.KeyChecker
import com.frynetworks.fryapp.provisioning.DeviceCapabilities
import com.frynetworks.fryapp.provisioning.HandoffOutcome
import com.frynetworks.fryapp.provisioning.HandoffWatcher
import com.frynetworks.fryapp.provisioning.ProvStatus
import com.frynetworks.fryapp.wifi.SoftApEvent
import com.frynetworks.fryapp.wifi.SoftApInfo
import com.frynetworks.fryapp.wifi.SoftApStatus
import com.frynetworks.fryapp.wifi.WifiProvisioner
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R3 (IV5): one key per device. A board that keeps the key it minted itself (protocol-1
 * DEVICE_KEEPS, or no owner key entered) is checked against the dashboard too, once the app
 * learns that key, so a key already running on a PC is flagged with the active-elsewhere copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProvisionBoardKeyCheckTest {

    private val wallet = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"
    private val ownerKey = "FEM-TESTKEY0000000000000000000000001"
    private val boardKey = "FEM-TESTKEY0000000000000000000000002"
    private val ble = "AA:BB:CC:DD:EE:FF"
    private val v11 = DeviceStatusJson.parse("""{"v":1,"proto":2,"caps":["key_write","error_reset","errs_v2"]}""")
    private val repository = mockk<DeviceRepository>(relaxed = true)
    private val bleProvisioner = mockk<BleProvisioner>()
    private val wifiProvisioner = mockk<WifiProvisioner>()
    private val activeElsewhere = KeyCheckResult.Checked("ok_active_elsewhere", "you", "Runs on your PC.", null, emptyList(), "stops_other", true)
    private val checked = mutableListOf<String>()

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMain() = Dispatchers.resetMain()

    private fun viewModel(): ProvisionViewModel = ProvisionViewModel(
        bleProvisioner, wifiProvisioner, repository, mockk<SettingsRepository>(relaxed = true),
        ProvisionServices(HandoffWatcher { HandoffOutcome.ONLINE }, KeyChecker { checked += it; activeElsewhere }),
    )

    private fun info(key: String) = ProvisionEvent.DeviceInfo(BleDeviceInfo("FRY-ESP32-ABC123", "ESP32-S3", "0.3.3", key))
    private val connected = ProvisionEvent.StatusUpdate(ProvStatus(ProvState.CONNECTED))

    @Test
    fun `a protocol-1 board's own key is checked once the app learns it`() = runTest {
        every { bleProvisioner.provision(any(), any(), any(), any(), ownerKey) } returns
            flowOf(info(boardKey), ProvisionEvent.Capabilities(DeviceCapabilities.PROTO_1), connected)
        val vm = viewModel()
        vm.submit(ble, Transport.BLE, "lab", "pass", wallet, KeyStep(ownerKey, null))

        assertEquals(ProvisionUiState.Success(boardKey), vm.state.value)
        assertEquals(listOf(boardKey), checked)
        assertEquals(KeyCheckUi.Done(boardKey, activeElsewhere), vm.boardKeyCheck.value)
    }

    @Test
    fun `a board set up without an owner key has its own key checked`() = runTest {
        every { bleProvisioner.provision(any(), any(), any(), any()) } returns
            flowOf(info(boardKey), ProvisionEvent.Capabilities(v11), connected)
        val vm = viewModel()
        vm.submit(ble, Transport.BLE, "lab", "pass", wallet)

        assertEquals(listOf(boardKey), checked)
        assertEquals(KeyCheckUi.Done(boardKey, activeElsewhere), vm.boardKeyCheck.value)
    }

    @Test
    fun `a board that took the owner key gets no second check`() = runTest {
        every { bleProvisioner.provision(any(), any(), any(), any(), ownerKey) } returns
            flowOf(info(""), ProvisionEvent.Capabilities(v11), connected, ProvisionEvent.KeyReadBack(ownerKey))
        val vm = viewModel()
        vm.submit(ble, Transport.BLE, "lab", "pass", wallet, KeyStep(ownerKey, null))

        assertEquals(ProvisionUiState.Success(ownerKey), vm.state.value)
        assertTrue(checked.isEmpty())
        assertEquals(KeyCheckUi.Idle, vm.boardKeyCheck.value)
    }

    @Test
    fun `a protocol-1 board already holding the owner key is not checked again`() = runTest {
        every { bleProvisioner.provision(any(), any(), any(), any(), ownerKey) } returns
            flowOf(info(ownerKey), ProvisionEvent.Capabilities(DeviceCapabilities.PROTO_1), connected)
        val vm = viewModel()
        vm.submit(ble, Transport.BLE, "lab", "pass", wallet, KeyStep(ownerKey, null))

        assertTrue(checked.isEmpty())
        assertEquals(KeyCheckUi.Idle, vm.boardKeyCheck.value)
    }

    @Test
    fun `an ESP8266 that keeps its own key over SoftAP is checked, a masked key never is`() = runTest {
        every { wifiProvisioner.provision(any(), any(), any(), any()) } returns flowOf(
            SoftApEvent.Info(SoftApInfo("FRY-ESP8266-ABC123", boardKey, "0.3.3", "ESP8266")),
            SoftApEvent.ProvisionAccepted,
            SoftApEvent.StatusUpdate(SoftApStatus(status = 3, err = 0, minerKey = boardKey, ip = "192.168.1.50")),
        )
        val vm = viewModel()
        vm.submit("FRY-SETUP-ABC123", Transport.SOFTAP, "lab", "pass", wallet)
        assertEquals(listOf(boardKey), checked)

        val masked = "FEM-TE…"
        every { wifiProvisioner.provision(any(), any(), any(), any()) } returns flowOf(
            SoftApEvent.Info(SoftApInfo("FRY-ESP8266-ABC123", masked, "0.4.0", "ESP8266", proto = 2, caps = listOf("key_write"), keySet = true)),
            SoftApEvent.StatusUpdate(SoftApStatus(status = 3, err = 0, minerKey = masked, ip = "192.168.1.50")),
        )
        val vm2 = viewModel()
        vm2.submit("FRY-SETUP-ABC123", Transport.SOFTAP, "lab", "pass", wallet)
        assertEquals(listOf(boardKey), checked)
        assertEquals(KeyCheckUi.Idle, vm2.boardKeyCheck.value)
    }

    @Test
    fun `navigation waits for the check and for the owner's acknowledgement`() {
        val done = KeyCheckUi.Done(boardKey, activeElsewhere)
        assertTrue(ProvisionViewModel.boardKeyHolds(KeyCheckUi.Checking(boardKey), acknowledged = false))
        assertTrue(ProvisionViewModel.boardKeyHolds(done, acknowledged = false))
        assertFalse(ProvisionViewModel.boardKeyHolds(done, acknowledged = true))
        assertFalse(ProvisionViewModel.boardKeyHolds(KeyCheckUi.Idle, acknowledged = false))
        val ok = KeyCheckResult.Checked("ok", "you", null, null, emptyList(), null, true)
        assertFalse(ProvisionViewModel.boardKeyHolds(KeyCheckUi.Done(boardKey, ok), acknowledged = false))
        val signedOut = KeyCheckResult.Unverifiable(null, "Sign in to check who owns this key before you set it up.")
        assertFalse(ProvisionViewModel.boardKeyHolds(KeyCheckUi.Done(boardKey, signedOut), acknowledged = false))
        val other = KeyCheckResult.Checked("registered_other", "another_wallet", "Registered to another wallet.", null, emptyList(), "none", true)
        assertTrue(ProvisionViewModel.boardKeyHolds(KeyCheckUi.Done(boardKey, other), acknowledged = false))
    }

    @Test
    fun `the provision screen shows the board key check with the existing copy`() {
        val src = java.io.File("src/main/java/com/frynetworks/fryapp/ui/provision/ProvisionScreen.kt")
        assertTrue("run from the app module: ${src.absolutePath}", src.isFile)
        val screen = src.readText()
        assertTrue(screen.contains("viewModel.boardKeyCheck.collectAsStateWithLifecycle()"))
        assertTrue(screen.contains("\"prov_board_key_check\""))
        assertTrue(screen.contains("\"prov_board_key_ack\""))
        assertTrue(screen.contains("KeyCheckCopy.acknowledgement("))
        assertTrue(screen.contains("ProvisionViewModel.boardKeyHolds("))
    }
}
