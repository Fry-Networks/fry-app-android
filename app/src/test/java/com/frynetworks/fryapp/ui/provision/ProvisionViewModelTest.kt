package com.frynetworks.fryapp.ui.provision

import com.frynetworks.fryapp.ble.BleDeviceInfo
import com.frynetworks.fryapp.ble.BleProvisioner
import com.frynetworks.fryapp.ble.ProvState
import com.frynetworks.fryapp.ble.ProvisionEvent
import com.frynetworks.fryapp.data.DeviceRepository
import com.frynetworks.fryapp.data.SettingsRepository
import com.frynetworks.fryapp.data.Transport
import com.frynetworks.fryapp.provisioning.ProvStatus
import com.frynetworks.fryapp.wifi.SoftApEvent
import com.frynetworks.fryapp.wifi.WifiProvisioner
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
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
class ProvisionViewModelTest {

    private val validWallet = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"
    private val bleAddress = "AA:BB:CC:DD:EE:FF"
    private val repository = mockk<DeviceRepository>(relaxed = true)

    @Before
    fun setMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        ble: Flow<ProvisionEvent> = emptyFlow(),
        wifi: Flow<SoftApEvent> = emptyFlow(),
    ): ProvisionViewModel {
        val bleProvisioner = mockk<BleProvisioner> {
            every { provision(any(), any(), any(), any()) } returns ble
        }
        val wifiProvisioner = mockk<WifiProvisioner> {
            every { provision(any(), any(), any(), any()) } returns wifi
        }
        val settings = mockk<SettingsRepository>(relaxed = true)
        return ProvisionViewModel(bleProvisioner, wifiProvisioner, repository, settings)
    }

    @Test
    fun `a provisioner that throws surfaces an error state instead of crashing the process`() = runTest {
        val vm = viewModel(ble = flow { throw IllegalStateException("GATT exploded") })

        vm.submit(bleAddress, Transport.BLE, "lab-ssid", "lab-pass", validWallet)

        assertEquals(ProvisionUiState.Error("GATT exploded"), vm.state.value)
    }

    @Test
    fun `reaching CONNECTED without a miner key is an error, never an empty success`() = runTest {
        val vm = viewModel(ble = flowOf(ProvisionEvent.StatusUpdate(ProvStatus(ProvState.CONNECTED))))

        vm.submit(bleAddress, Transport.BLE, "lab-ssid", "lab-pass", validWallet)

        val state = vm.state.value
        assertTrue("expected Error but was $state", state is ProvisionUiState.Error)
    }

    @Test
    fun `a completed BLE session persists the device and succeeds with its miner key`() = runTest {
        val info = BleDeviceInfo("FRY-ESP32-ABC123", "ESP32", "0.2.0", "IOT-" + "A".repeat(32))
        val vm = viewModel(
            ble = flowOf(
                ProvisionEvent.DeviceInfo(info),
                ProvisionEvent.StatusUpdate(ProvStatus(ProvState.CONNECTED)),
            ),
        )

        vm.submit(bleAddress, Transport.BLE, "lab-ssid", "lab-pass", validWallet)

        assertEquals(ProvisionUiState.Success(info.minerKey), vm.state.value)
        coVerify { repository.upsert(match { it.minerKey == info.minerKey && it.wallet == validWallet }) }
    }
}
