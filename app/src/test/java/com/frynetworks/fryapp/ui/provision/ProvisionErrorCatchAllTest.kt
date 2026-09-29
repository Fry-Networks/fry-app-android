package com.frynetworks.fryapp.ui.provision

import com.frynetworks.fryapp.ble.BleProvisioner
import com.frynetworks.fryapp.ble.ProvError
import com.frynetworks.fryapp.ble.ProvState
import com.frynetworks.fryapp.ble.ProvisionEvent
import com.frynetworks.fryapp.data.DeviceRepository
import com.frynetworks.fryapp.data.SettingsRepository
import com.frynetworks.fryapp.data.Transport
import com.frynetworks.fryapp.provisioning.ProvStatus
import com.frynetworks.fryapp.provisioning.ProvisioningReducer
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The bug behind U4/U8, written against the v0.3.1 surface only so it runs on the pre-fix code:
 * a v1.1 registration code (9-13) reached the user as the old catch-all ("Device registration with
 * hardwareapi failed" / "Unknown error"), whatever had actually gone wrong.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProvisionErrorCatchAllTest {

    private val validWallet = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun errorShown(status: ProvStatus): String {
        val ble = mockk<BleProvisioner> {
            every { provision(any(), any(), any(), any()) } returns flowOf(ProvisionEvent.StatusUpdate(status))
        }
        val vm = ProvisionViewModel(ble, mockk<WifiProvisioner>(relaxed = true), mockk<DeviceRepository>(relaxed = true), mockk<SettingsRepository>(relaxed = true))
        vm.submit("AA:BB:CC:DD:EE:FF", Transport.BLE, "lab-ssid", "lab-pass", validWallet)
        val state = vm.state.value
        assertTrue("expected Error, got $state", state is ProvisionUiState.Error)
        return (state as ProvisionUiState.Error).reason
    }

    private fun assertNotCatchAll(reason: String) {
        assertFalse(reason, reason.contains("hardwareapi", ignoreCase = true))
        assertFalse(reason, reason == "Unknown error")
    }

    @Test
    fun `a v1-1 registration code names the problem instead of the old catch-all`() = runTest {
        val inUse = errorShown(ProvStatus(ProvState.ERROR, ProvError.fromCode(11)))
        assertNotCatchAll(inUse)
        assertTrue(inUse, inUse.contains("another install"))

        val unknownKey = errorShown(ProvStatus(ProvState.ERROR, ProvError.fromCode(9)))
        assertNotCatchAll(unknownKey)
        assertTrue(unknownKey, unknownKey.contains("key", ignoreCase = true))
    }

    @Test
    fun `the legacy registration byte no longer reads as a hardwareapi failure`() = runTest {
        assertNotCatchAll(errorShown(ProvisioningReducer.fromStatusBytes(byteArrayOf(4, 4, 9))))
    }
}
