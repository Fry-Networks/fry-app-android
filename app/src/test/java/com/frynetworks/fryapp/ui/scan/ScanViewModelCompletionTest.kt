package com.frynetworks.fryapp.ui.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.net.wifi.WifiManager
import com.frynetworks.fryapp.ble.BleFoundDevice
import com.frynetworks.fryapp.ble.BleScanner
import com.frynetworks.fryapp.wifi.WifiProvisioner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U5 / planning F4: "Scanning…" never ended. The ViewModel waits for BOTH scans; the BLE scan
 * closes after its 15 s window, but the SoftAP (Wi-Fi) scan flow never closed, so the button
 * stayed on "Scanning..." forever. The real [WifiProvisioner] runs here against a mocked
 * Context; only the platform services are fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelCompletionTest {

    private val wifiManager = mockk<WifiManager>(relaxed = true) {
        every { startScan() } returns true
        every { scanResults } returns emptyList()
    }
    private val receivers = mutableListOf<BroadcastReceiver>()
    private val context = mockk<Context>(relaxed = true).also { ctx ->
        every { ctx.applicationContext } returns ctx
        every { ctx.getSystemService(Context.WIFI_SERVICE) } returns wifiManager
        every { ctx.registerReceiver(capture(receivers), any()) } returns null
    }

    /** Behaves like the real BleScanner: emits what it finds, then closes after its window. */
    private val bleScanner = mockk<BleScanner> {
        every { scan() } returns flow {
            emit(BleFoundDevice("FRY-ESP32-ABC123", "AA:BB:CC:DD:EE:FF", -50))
            delay(BleScanner.SCAN_WINDOW_MS)
        }
    }

    private fun TestScope.viewModel(): ScanViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return ScanViewModel(bleScanner, WifiProvisioner(context))
    }

    @Test
    fun `a scan finishes once the scan window is over`() = runTest {
        try {
            val vm = viewModel()
            vm.startScan()
            runCurrent()
            assertTrue(vm.scanning.value)

            advanceTimeBy(BleScanner.SCAN_WINDOW_MS + 30_000)
            runCurrent()

            assertFalse("still scanning ${BleScanner.SCAN_WINDOW_MS + 30_000} ms after start", vm.scanning.value)
            assertTrue(vm.hasScanned.value)
            assertEquals(listOf("AA:BB:CC:DD:EE:FF"), vm.results.value.map { it.address })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `the Wi-Fi scan receiver is unregistered when the scan ends`() = runTest {
        try {
            val vm = viewModel()
            vm.startScan()
            advanceTimeBy(BleScanner.SCAN_WINDOW_MS + 30_000)
            runCurrent()

            assertTrue(receivers.isNotEmpty())
            verify { context.unregisterReceiver(receivers.first()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a second scan can start after the first one finished`() = runTest {
        try {
            val vm = viewModel()
            vm.startScan()
            advanceTimeBy(BleScanner.SCAN_WINDOW_MS + 30_000)
            runCurrent()
            vm.startScan()
            runCurrent()

            assertTrue("the button must not be stuck: a new scan is running", vm.scanning.value)
            assertEquals(2, receivers.size)

            advanceTimeBy(BleScanner.SCAN_WINDOW_MS + 30_000)
            runCurrent()
            assertFalse("the second scan ends too", vm.scanning.value)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
