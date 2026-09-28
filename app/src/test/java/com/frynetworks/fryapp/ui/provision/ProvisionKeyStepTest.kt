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
import com.frynetworks.fryapp.domain.MinerKeyFormat
import com.frynetworks.fryapp.provisioning.DeviceCapabilities
import com.frynetworks.fryapp.provisioning.HandoffOutcome
import com.frynetworks.fryapp.provisioning.HandoffWatcher
import com.frynetworks.fryapp.provisioning.ProvStatus
import com.frynetworks.fryapp.wifi.SoftApEvent
import com.frynetworks.fryapp.wifi.SoftApInfo
import com.frynetworks.fryapp.wifi.SoftApStatus
import com.frynetworks.fryapp.wifi.WifiProvisioner
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProvisionKeyStepTest {

    private val wallet = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"
    private val ownerKey = "FEM-TESTKEY0000000000000000000000001"
    private val boardKey = "FEM-TESTKEY0000000000000000000000002"
    private val ble = "AA:BB:CC:DD:EE:FF"
    private val v11 = DeviceStatusJson.parse("""{"v":1,"proto":2,"caps":["key_write","error_reset","errs_v2"]}""")
    private val repository = mockk<DeviceRepository>(relaxed = true)
    private val bleProvisioner = mockk<BleProvisioner>()
    private val wifiProvisioner = mockk<WifiProvisioner>()
    private var keyAnswer: KeyCheckResult = KeyCheckResult.Unverifiable(null, "Sign in to check who owns this key before you set it up.")

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMain() = Dispatchers.resetMain()

    private fun viewModel(): ProvisionViewModel = ProvisionViewModel(
        bleProvisioner, wifiProvisioner, repository, mockk<SettingsRepository>(relaxed = true),
        ProvisionServices(HandoffWatcher { HandoffOutcome.ONLINE }, KeyChecker { keyAnswer }),
    )

    private fun bleWithKey(vararg events: ProvisionEvent) {
        every { bleProvisioner.provision(any(), any(), any(), any(), ownerKey) } returns flowOf(*events)
    }

    private fun info(key: String) = ProvisionEvent.DeviceInfo(BleDeviceInfo("FRY-ESP32-ABC123", "ESP32", "0.4.0", key))
    private val connected = ProvisionEvent.StatusUpdate(ProvStatus(ProvState.CONNECTED))

    @Test
    fun `a v1-1 board takes the owner key, which is read back before success`() = runTest {
        bleWithKey(info(""), ProvisionEvent.Capabilities(v11), connected, ProvisionEvent.KeyReadBack(ownerKey))
        val vm = viewModel()
        vm.submit(ble, Transport.BLE, "lab", "pass", wallet, KeyStep(" $ownerKey ", null))

        assertEquals(ProvisionUiState.Success(ownerKey), vm.state.value)
        assertNull(vm.keyNotice.value)
        coVerify { repository.upsert(match { it.minerKey == ownerKey && it.status == 3 }) }
    }

    @Test
    fun `a read-back that differs is an error, not a success`() = runTest {
        bleWithKey(info(""), ProvisionEvent.Capabilities(v11), connected, ProvisionEvent.KeyReadBack(boardKey))
        val vm = viewModel()
        vm.submit(ble, Transport.BLE, "lab", "pass", wallet, KeyStep(ownerKey, null))
        assertEquals(ProvisionUiState.Error(ProvisionViewModel.keyMismatch(boardKey)), vm.state.value)
    }

    @Test
    fun `a protocol-1 board keeps its own key, and the app shows it with a copy`() = runTest {
        bleWithKey(info(boardKey), ProvisionEvent.Capabilities(DeviceCapabilities.PROTO_1), connected)
        val vm = viewModel()
        vm.submit(ble, Transport.BLE, "lab", "pass", wallet, KeyStep(ownerKey, null))

        assertEquals(ProvisionUiState.Success(boardKey), vm.state.value)
        assertEquals(KeyNotice(boardKey, ProvisionViewModel.DEVICE_KEEPS_NOTICE), vm.keyNotice.value)
    }

    @Test
    fun `a key active elsewhere needs the owner's acknowledgement`() = runTest {
        keyAnswer = KeyCheckResult.Checked("ok_active_elsewhere", "you", "Runs on your PC.", null, emptyList(), "stops_other", true)
        bleWithKey(info(""), ProvisionEvent.Capabilities(v11), connected, ProvisionEvent.KeyReadBack(ownerKey))
        val vm = viewModel()
        vm.checkKey(ownerKey)

        vm.submit(ble, Transport.BLE, "lab", "pass", wallet, KeyStep(ownerKey, null, acknowledgedActiveElsewhere = false))
        assertEquals(ProvisionUiState.Error(ProvisionViewModel.KEY_ACK_REQUIRED), vm.state.value)
        verify(exactly = 0) { bleProvisioner.provision(any(), any(), any(), any(), any()) }

        vm.submit(ble, Transport.BLE, "lab", "pass", wallet, KeyStep(ownerKey, null, acknowledgedActiveElsewhere = true))
        assertEquals(ProvisionUiState.Success(ownerKey), vm.state.value)
    }

    @Test
    fun `someone else's key and IOT- keys never reach the board`() = runTest {
        keyAnswer = KeyCheckResult.Checked("registered_other", "another_wallet", "Registered to another wallet.", null, emptyList(), "none", true)
        val vm = viewModel()
        vm.checkKey(ownerKey)
        vm.submit(ble, Transport.BLE, "lab", "pass", wallet, KeyStep(ownerKey, null))
        assertEquals(ProvisionUiState.Error("Registered to another wallet."), vm.state.value)

        vm.submit(ble, Transport.BLE, "lab", "pass", wallet, KeyStep("IOT-" + "0".repeat(32), null))
        assertEquals(ProvisionUiState.Error(MinerKeyFormat.LEGACY_GUIDANCE + "."), vm.state.value)
        verify(exactly = 0) { bleProvisioner.provision(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the key check result is exposed for the UI`() = runTest {
        val vm = viewModel()
        vm.checkKey(ownerKey)
        assertEquals(KeyCheckUi.Done(ownerKey, keyAnswer), vm.keyCheck.value)
        vm.clearKeyCheck()
        assertEquals(KeyCheckUi.Idle, vm.keyCheck.value)
    }

    @Test
    fun `a keyed v1-1 ESP8266 on its open AP never has its masked key stored as a key`() = runTest {
        val masked = "FEM-TE…"
        every { wifiProvisioner.provision(any(), any(), any(), any()) } returns flowOf(
            SoftApEvent.Info(SoftApInfo("FRY-ESP8266-ABC123", masked, "0.4.0", "ESP8266", proto = 2, caps = listOf("key_write"), keySet = true)),
            SoftApEvent.ProvisionAccepted,
            SoftApEvent.StatusUpdate(SoftApStatus(status = 3, err = 0, minerKey = masked, ip = "192.168.1.50")),
        )
        val vm = viewModel()
        vm.submit("FRY-SETUP-ABC123", Transport.SOFTAP, "lab", "pass", wallet)

        assertEquals(ProvisionUiState.Handoff(masked, ProvisionViewModel.CONNECTED_MASKED_KEY), vm.state.value)
        coVerify(exactly = 0) { repository.upsert(any()) }
    }

    @Test
    fun `a keyless v1-1 ESP8266 joined with its setup code takes the owner key`() = runTest {
        every { wifiProvisioner.provision(any(), any(), any(), any(), ownerKey, "ABCD2345") } returns flowOf(
            SoftApEvent.Info(SoftApInfo("FRY-ESP8266-ABC123", "", "0.4.0", "ESP8266", proto = 2, caps = listOf("key_write"), keySet = false)),
            SoftApEvent.ProvisionAccepted,
            SoftApEvent.StatusUpdate(SoftApStatus(status = 3, err = 0, minerKey = MinerKeyFormat.mask(ownerKey), ip = "192.168.1.50")),
        )
        val vm = viewModel()
        vm.submit("FRY-SETUP-ABC123", Transport.SOFTAP, "lab", "pass", wallet, KeyStep(ownerKey, " ABCD2345 "))

        assertEquals(ProvisionUiState.Success(ownerKey), vm.state.value)
        coVerify { repository.upsert(match { it.minerKey == ownerKey }) }
    }
}
