package com.frynetworks.fryapp.ui.provision

import com.frynetworks.fryapp.ble.BleDeviceInfo
import com.frynetworks.fryapp.ble.BleProvisioner
import com.frynetworks.fryapp.ble.ProvError
import com.frynetworks.fryapp.ble.ProvState
import com.frynetworks.fryapp.ble.ProvisionEvent
import com.frynetworks.fryapp.data.DeviceRepository
import com.frynetworks.fryapp.data.SettingsRepository
import com.frynetworks.fryapp.data.Transport
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class ProvisionHandoffTest {

    private val wallet = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"
    private val key = "FEM-TESTKEY0000000000000000000000001"
    private val info = BleDeviceInfo("FRY-ESP32-ABC123", "ESP32", "0.4.0", key)
    private val repository = mockk<DeviceRepository>(relaxed = true)
    private val watched = mutableListOf<String>()

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMain() = Dispatchers.resetMain()

    private fun viewModel(
        ble: Flow<ProvisionEvent> = emptyFlow(),
        wifi: Flow<SoftApEvent> = emptyFlow(),
        outcome: HandoffOutcome? = null,
    ): ProvisionViewModel {
        val bleProvisioner = mockk<BleProvisioner> { every { provision(any(), any(), any(), any()) } returns ble }
        val wifiProvisioner = mockk<WifiProvisioner> { every { provision(any(), any(), any(), any()) } returns wifi }
        val settings = mockk<SettingsRepository>(relaxed = true)
        if (outcome == null) return ProvisionViewModel(bleProvisioner, wifiProvisioner, repository, settings)
        val watcher = HandoffWatcher { k -> watched += k; outcome }
        return ProvisionViewModel(bleProvisioner, wifiProvisioner, repository, settings, ProvisionServices(watcher))
    }

    private fun ble(vararg events: ProvisionEvent) = flowOf(ProvisionEvent.DeviceInfo(info), *events)

    @Test
    fun `a BLE handoff the backend confirms ends in Success`() = runTest {
        val vm = viewModel(ble = ble(ProvisionEvent.Handoff), outcome = HandoffOutcome.ONLINE)
        vm.submit("AA:BB:CC:DD:EE:FF", Transport.BLE, "lab", "pass", wallet)

        assertEquals(ProvisionUiState.Success(key), vm.state.value)
        assertEquals(listOf(key), watched)
        coVerify { repository.upsert(match { it.minerKey == key && it.status == 3 }) }
    }

    @Test
    fun `a handoff the backend has not seen yet is not an error`() = runTest {
        val vm = viewModel(ble = ble(ProvisionEvent.Handoff), outcome = HandoffOutcome.NOT_SEEN)
        vm.submit("AA:BB:CC:DD:EE:FF", Transport.BLE, "lab", "pass", wallet)

        assertEquals(ProvisionUiState.Handoff(key, ProvisionViewModel.HANDOFF_NOT_SEEN), vm.state.value)
        coVerify { repository.upsert(match { it.minerKey == key && it.status == 2 }) }
    }

    @Test
    fun `signed out, the handoff says to sign in`() = runTest {
        val vm = viewModel(ble = ble(ProvisionEvent.Handoff), outcome = HandoffOutcome.SIGNED_OUT)
        vm.submit("AA:BB:CC:DD:EE:FF", Transport.BLE, "lab", "pass", wallet)
        assertEquals(ProvisionUiState.Handoff(key, ProvisionViewModel.HANDOFF_SIGNED_OUT), vm.state.value)
    }

    @Test
    fun `the v0-3-1 wiring still reports the handoff, without a backend check`() = runTest {
        val vm = viewModel(ble = ble(ProvisionEvent.Handoff))
        vm.submit("AA:BB:CC:DD:EE:FF", Transport.BLE, "lab", "pass", wallet)
        assertEquals(ProvisionUiState.Handoff(key, ProvisionViewModel.HANDOFF_UNCONFIRMED), vm.state.value)
    }

    @Test
    fun `a SoftAP board that tears its AP down after accepting is handed off`() = runTest {
        val softAp = flowOf(
            SoftApEvent.Info(SoftApInfo("FRY-8266-ABC123", key, "0.4.0", "ESP8266")),
            SoftApEvent.ProvisionAccepted,
            SoftApEvent.Handoff,
        )
        val vm = viewModel(wifi = softAp, outcome = HandoffOutcome.ONLINE)
        vm.submit("FRY-SETUP-ABC123", Transport.SOFTAP, "lab", "pass", wallet)
        assertEquals(ProvisionUiState.Success(key), vm.state.value)
    }

    @Test
    fun `device errors and refusals show the v1-1 guidance, not the old catch-all`() = runTest {
        val bleError = viewModel(ble = ble(ProvisionEvent.StatusUpdate(ProvStatus(ProvState.ERROR, ProvError.REG_KEY_IN_USE))))
        bleError.submit("AA:BB:CC:DD:EE:FF", Transport.BLE, "lab", "pass", wallet)
        assertEquals(ProvisionUiState.Error(ProvisionErrorCopy.forError(ProvError.REG_KEY_IN_USE)), bleError.state.value)

        val softApDetail = viewModel(wifi = flowOf(SoftApEvent.StatusUpdate(SoftApStatus(status = 4, err = 4, minerKey = key, ip = null, detail = 9))))
        softApDetail.submit("FRY-SETUP-ABC123", Transport.SOFTAP, "lab", "pass", wallet)
        assertEquals(ProvisionUiState.Error(ProvisionErrorCopy.forError(ProvError.REG_UNAUTHORIZED)), softApDetail.state.value)

        val refused = viewModel(wifi = flowOf(SoftApEvent.Refused(422, "key_required")))
        refused.submit("FRY-SETUP-ABC123", Transport.SOFTAP, "lab", "pass", wallet)
        assertEquals(ProvisionUiState.Error(ProvisionErrorCopy.forRefusal(422, "key_required")), refused.state.value)

        val failed = viewModel(ble = flowOf(ProvisionEvent.Failed("GATT connect failed: status=133")))
        failed.submit("AA:BB:CC:DD:EE:FF", Transport.BLE, "lab", "pass", wallet)
        val state = failed.state.value
        assertTrue("$state", state is ProvisionUiState.Error && state.reason.contains("within 2 metres"))
    }
}
