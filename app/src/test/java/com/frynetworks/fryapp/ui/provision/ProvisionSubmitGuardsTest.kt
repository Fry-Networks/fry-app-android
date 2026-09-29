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
import com.frynetworks.fryapp.provisioning.HandoffOutcome
import com.frynetworks.fryapp.provisioning.HandoffWatcher
import com.frynetworks.fryapp.provisioning.ProvStatus
import com.frynetworks.fryapp.wifi.WifiProvisioner
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Two gaps around the key step (r3 behaviour #10, #11), asserted through the state text alone so
 * the same file runs against the code before the fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProvisionSubmitGuardsTest {

    private val wallet = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"
    private val ownerKey = "FEM-TESTKEY0000000000000000000000001"
    private val ble = "AA:BB:CC:DD:EE:FF"
    private val v11 = DeviceStatusJson.parse("""{"v":1,"proto":2,"caps":["key_write","error_reset","errs_v2"]}""")
    private val repository = mockk<DeviceRepository>(relaxed = true)
    private val bleProvisioner = mockk<BleProvisioner>()

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMain() = Dispatchers.resetMain()

    private fun viewModel(checker: KeyChecker): ProvisionViewModel = ProvisionViewModel(
        bleProvisioner, mockk<WifiProvisioner>(relaxed = true), repository, mockk<SettingsRepository>(relaxed = true),
        ProvisionServices(HandoffWatcher { HandoffOutcome.ONLINE }, checker),
    )

    @Test
    fun `submit while the key check is still running is refused and the board is not touched`() = runTest {
        val vm = viewModel(KeyChecker { awaitCancellation() })
        vm.checkKey(ownerKey)
        assertTrue("${vm.keyCheck.value}", vm.keyCheck.value is KeyCheckUi.Checking)

        vm.submit(ble, Transport.BLE, "lab", "pass", wallet, KeyStep(ownerKey, null))

        val state = vm.state.value
        assertTrue("$state", state is ProvisionUiState.Error && state.reason.contains("checking", ignoreCase = true))
        verify(exactly = 0) { bleProvisioner.provision(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `with no owner key a read-back never overwrites the success a keyed board already earned`() = runTest {
        val boardKey = "FEM-TESTKEY0000000000000000000000002"
        every { bleProvisioner.provision(any(), any(), any(), any()) } returns flowOf(
            ProvisionEvent.DeviceInfo(BleDeviceInfo("FRY-ESP32-ABC123", "ESP32", "0.4.0", boardKey)),
            ProvisionEvent.Capabilities(v11),
            ProvisionEvent.StatusUpdate(ProvStatus(ProvState.CONNECTED)),
            ProvisionEvent.KeyReadBack(boardKey),
        )
        val vm = viewModel(KeyChecker { KeyCheckResult.Unverifiable(null, "Sign in to check who owns this key before you set it up.") })

        vm.submit(ble, Transport.BLE, "lab", "pass", wallet, KeyStep(null, null))

        assertEquals(ProvisionUiState.Success(boardKey), vm.state.value)
    }

    @Test
    fun `an unreadable key after Connected is not a success`() = runTest {
        every { bleProvisioner.provision(any(), any(), any(), any(), ownerKey) } returns flowOf(
            ProvisionEvent.DeviceInfo(BleDeviceInfo("FRY-ESP32-ABC123", "ESP32", "0.4.0", "")),
            ProvisionEvent.Capabilities(v11),
            ProvisionEvent.StatusUpdate(ProvStatus(ProvState.CONNECTED)),
            ProvisionEvent.KeyReadBack(null),
        )
        val vm = viewModel(KeyChecker { KeyCheckResult.Unverifiable(null, "Sign in to check who owns this key before you set it up.") })

        vm.submit(ble, Transport.BLE, "lab", "pass", wallet, KeyStep(ownerKey, null))

        val state = vm.state.value
        assertTrue("$state", state is ProvisionUiState.Error && state.reason.contains("read back", ignoreCase = true))
        coVerify(exactly = 0) { repository.upsert(any()) }
    }
}
