package com.frynetworks.fryapp.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryPreflightTest {

    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"
    private val scan = "android.permission.BLUETOOTH_SCAN"
    private val connect = "android.permission.BLUETOOTH_CONNECT"
    private val nearby = "android.permission.NEARBY_WIFI_DEVICES"
    private val all = listOf(fine, coarse, scan, connect, nearby)

    private fun inputs(
        sdk: Int,
        granted: List<String> = all,
        blocked: List<String> = emptyList(),
        ble: Boolean = true,
        bt: Boolean = true,
        location: Boolean = true,
        wifi: Boolean = true,
    ) = DiscoveryInputs(
        sdkInt = sdk,
        permissions = all.associateWith {
            when (it) {
                in granted -> PermissionState.GRANTED
                in blocked -> PermissionState.PERMANENTLY_DENIED
                else -> PermissionState.DENIED
            }
        },
        bleSupported = ble,
        bluetoothOn = bt,
        locationOn = location,
        wifiOn = wifi,
    )

    private fun codes(t: TransportPreflight) = t.issues.map { it.code }

    @Test
    fun `everything granted and on - both transports ready on every supported SDK`() {
        for (sdk in listOf(29, 31, 33, 36)) {
            val r = DiscoveryPreflight.evaluate(inputs(sdk))
            assertTrue("sdk $sdk ble", r.ble.ready)
            assertTrue("sdk $sdk softap", r.softAp.ready)
            assertTrue(r.issues.isEmpty())
        }
    }

    @Test
    fun `API 26 - BLE needs only location, SoftAP setup is unsupported`() {
        val r = DiscoveryPreflight.evaluate(inputs(26, granted = listOf(fine, coarse)))
        assertTrue(r.ble.ready)
        assertEquals(listOf("softap_unsupported"), codes(r.softAp))
        assertTrue(r.canScan)
        assertEquals(listOf(fine, coarse), DiscoveryPreflight.blePermissions(26))
        assertEquals(emptyList<String>(), DiscoveryPreflight.softApPermissions(28))
    }

    @Test
    fun `API 29 - denied location blocks both transports with one request for location`() {
        val r = DiscoveryPreflight.evaluate(inputs(29, granted = emptyList()))
        assertEquals(listOf("permission_needed"), codes(r.ble))
        assertEquals(listOf("permission_needed"), codes(r.softAp))
        assertFalse(r.canScan)
        assertEquals(1, r.issues.size)
        assertEquals(PreflightAction.RequestPermissions(listOf(fine, coarse)), r.issues.single().action)
    }

    @Test
    fun `API 31 - Bluetooth permissions are judged for BLE only`() {
        val r = DiscoveryPreflight.evaluate(inputs(31, granted = listOf(fine, coarse)))
        assertEquals(listOf("permission_needed"), codes(r.ble))
        assertTrue("SoftAP does not need Bluetooth permissions", r.softAp.ready)
        assertTrue(r.canScan)
        assertEquals(PreflightAction.RequestPermissions(listOf(scan, connect, fine, coarse)), r.ble.issues.single().action)
    }

    @Test
    fun `API 31 - approximate location only asks for Precise`() {
        val r = DiscoveryPreflight.evaluate(inputs(31, granted = listOf(coarse, scan, connect)))
        assertEquals(listOf("location_precise"), codes(r.ble))
        assertTrue(r.ble.issues.single().message.contains("Precise"))
    }

    @Test
    fun `API 33 - nearby Wi-Fi devices is judged for SoftAP only, and one prompt asks for everything missing`() {
        val r = DiscoveryPreflight.evaluate(inputs(33, granted = listOf(fine, coarse, scan, connect)))
        assertTrue(r.ble.ready)
        assertEquals(listOf("permission_needed"), codes(r.softAp))
        assertEquals(PreflightAction.RequestPermissions(listOf(fine, coarse, nearby)), r.softAp.issues.single().action)

        val none = DiscoveryPreflight.evaluate(inputs(33, granted = emptyList()))
        val prompt = none.issues.single { it.code == "permission_needed" }.action as PreflightAction.RequestPermissions
        assertEquals(setOf(fine, coarse, scan, connect, nearby), prompt.permissions.toSet())
    }

    @Test
    fun `API 36 - permanently denied points to App settings, never to a prompt that cannot appear`() {
        val r = DiscoveryPreflight.evaluate(inputs(36, granted = listOf(coarse), blocked = listOf(fine, scan)))
        assertEquals(listOf("permission_blocked"), codes(r.ble))
        assertEquals(PreflightAction.OpenAppSettings, r.ble.issues.single().action)
        assertEquals(PreflightAction.OpenAppSettings, r.softAp.issues.single().action)
    }

    @Test
    fun `Location off blocks both transports and opens Location settings`() {
        for (sdk in listOf(26, 29, 31, 33, 36)) {
            val r = DiscoveryPreflight.evaluate(inputs(sdk, location = false))
            assertTrue("sdk $sdk", "location_off" in codes(r.ble))
            assertEquals(PreflightAction.OpenLocationSettings, r.ble.issues.first { it.code == "location_off" }.action)
            assertFalse(r.canScan)
            assertEquals(1, r.issues.count { it.code == "location_off" })
        }
    }

    @Test
    fun `Bluetooth off blocks only BLE, Wi-Fi off blocks only SoftAP`() {
        val btOff = DiscoveryPreflight.evaluate(inputs(33, bt = false))
        assertEquals(listOf("bluetooth_off"), codes(btOff.ble))
        assertTrue(btOff.softAp.ready)
        assertEquals(PreflightAction.OpenBluetoothSettings, btOff.ble.issues.single().action)

        val wifiOff = DiscoveryPreflight.evaluate(inputs(33, wifi = false))
        assertTrue(wifiOff.ble.ready)
        assertEquals(listOf("wifi_off"), codes(wifiOff.softAp))
        assertEquals(PreflightAction.OpenWifiSettings, wifiOff.softAp.issues.single().action)
    }

    @Test
    fun `a phone without Bluetooth LE is told to use the USB setup`() {
        val r = DiscoveryPreflight.evaluate(inputs(31, ble = false))
        assertEquals(listOf("ble_unsupported"), codes(r.ble))
        assertEquals(PreflightAction.None, r.ble.issues.single().action)
        assertTrue(r.softAp.ready)
    }

    @Test
    fun `every issue with a label has an action and every message is a sentence`() {
        val r = DiscoveryPreflight.evaluate(inputs(33, granted = emptyList(), bt = false, location = false, wifi = false))
        for (issue in r.issues) {
            assertTrue(issue.code, issue.message.endsWith("."))
            if (issue.actionLabel != null) assertTrue(issue.code, issue.action != PreflightAction.None)
        }
    }
}
