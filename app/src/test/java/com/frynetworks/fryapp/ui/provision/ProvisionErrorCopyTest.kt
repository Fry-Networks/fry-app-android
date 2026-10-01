package com.frynetworks.fryapp.ui.provision

import com.frynetworks.fryapp.ble.ProvError
import com.frynetworks.fryapp.ble.ProvState
import com.frynetworks.fryapp.provisioning.ProvisioningReducer
import com.frynetworks.fryapp.wifi.SOFTAP_NEEDS_ANDROID_10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionErrorCopyTest {

    @Test
    fun `every device error code 0 to 13 has its own guidance`() {
        assertEquals((0..13).toList(), ProvError.entries.map { it.code })
        val texts = ProvError.entries.map { ProvisionErrorCopy.forError(it, errorResetSupported = true) }
        assertTrue(texts.all { it.isNotBlank() && it.endsWith(".") })
        assertEquals("each code reads differently", texts.size, texts.toSet().size)
        assertFalse("the old catch-all is gone", texts.any { it.contains("hardwareapi", ignoreCase = true) })
    }

    @Test
    fun `codes 6 to 13 name the key and registration problems`() {
        assertTrue(ProvisionErrorCopy.forError(ProvError.KEY_REQUIRED).contains("FEM-"))
        assertTrue(ProvisionErrorCopy.forError(ProvError.BAD_KEY).contains("32 letters and digits"))
        assertTrue(ProvisionErrorCopy.forError(ProvError.KEY_LOCKED).let { t -> t.contains("docs/esp-miners.html#flash") && listOf("USB", "web setup", "browser", "Improv", "setup-miner", "dashboard setup", "Web Serial", "Web Bluetooth", "No Android needed").none { t.contains(it, ignoreCase = true) } })
        assertTrue(ProvisionErrorCopy.forError(ProvError.REG_UNAUTHORIZED).contains("IOT- keys"))
        assertTrue(ProvisionErrorCopy.forError(ProvError.REG_FORBIDDEN).contains("another wallet"))
        assertTrue(ProvisionErrorCopy.forError(ProvError.REG_KEY_IN_USE).contains("another install"))
        assertTrue(ProvisionErrorCopy.forError(ProvError.REG_REJECTED).contains("dashboard"))
        assertTrue(ProvisionErrorCopy.forError(ProvError.UNREACHABLE).contains("keeps retrying"))
    }

    @Test
    fun `a board that latches its error is told to restart, a v1-1 board and self-healing errors are not`() {
        assertTrue(ProvisionErrorCopy.forError(ProvError.WIFI_AUTH_FAILED).endsWith(ProvisionErrorCopy.LATCHED_HINT))
        assertFalse(ProvisionErrorCopy.forError(ProvError.WIFI_AUTH_FAILED, errorResetSupported = true).contains(ProvisionErrorCopy.LATCHED_HINT))
        assertFalse(ProvisionErrorCopy.forError(ProvError.UNREACHABLE).contains(ProvisionErrorCopy.LATCHED_HINT))
        assertFalse(ProvisionErrorCopy.forError(ProvError.HARDWAREAPI_REGISTRATION_FAILED).contains(ProvisionErrorCopy.LATCHED_HINT))
    }

    @Test
    fun `the v1-1 detail byte refines the legacy registration-failed code`() {
        val decode = { b: IntArray -> ProvisioningReducer.fromStatusBytesDetailedOrNull(ByteArray(b.size) { b[it].toByte() }) }
        assertEquals(ProvError.REG_KEY_IN_USE, decode(intArrayOf(4, 4, 11))?.error)
        assertEquals(ProvError.KEY_REQUIRED, decode(intArrayOf(4, 4, 6))?.error)
        assertEquals(ProvError.HARDWAREAPI_REGISTRATION_FAILED, decode(intArrayOf(4, 4))?.error)
        assertEquals("a detail below 6 never overrides", ProvError.HARDWAREAPI_REGISTRATION_FAILED, decode(intArrayOf(4, 4, 3))?.error)
        assertEquals(ProvError.WIFI_AUTH_FAILED, decode(intArrayOf(4, 2, 0))?.error)
        assertEquals(ProvState.CONNECTED, decode(intArrayOf(3, 0, 11))?.state)
        assertEquals(ProvError.NONE, decode(intArrayOf(3, 0, 11))?.error)
        assertEquals(null, decode(intArrayOf(9, 4, 11)))
    }

    @Test
    fun `provisioner failures become guidance and unknown ones pass through`() {
        assertTrue(ProvisionErrorCopy.forFailure("GATT connect failed: status=133").contains("within 2 metres"))
        assertTrue(ProvisionErrorCopy.forFailure("Provisioning timed out").contains("2 minutes"))
        assertTrue(ProvisionErrorCopy.forFailure("Failed to join FRY-SETUP-ABC123").contains("FRY-SETUP"))
        assertTrue(ProvisionErrorCopy.forFailure("Write failed for characteristic x").contains("closer"))
        assertEquals(SOFTAP_NEEDS_ANDROID_10, ProvisionErrorCopy.forFailure(SOFTAP_NEEDS_ANDROID_10))
        assertEquals("something new", ProvisionErrorCopy.forFailure("something new"))
    }

    @Test
    fun `SoftAP refusals map every v1-1 err code`() {
        assertEquals(ProvisionErrorCopy.forError(ProvError.KEY_REQUIRED, true), ProvisionErrorCopy.forRefusal(422, "key_required"))
        assertEquals(ProvisionErrorCopy.forError(ProvError.KEY_LOCKED, true), ProvisionErrorCopy.forRefusal(403, "key_locked"))
        assertEquals(ProvisionErrorCopy.forError(ProvError.BAD_KEY, true), ProvisionErrorCopy.forRefusal(400, "bad_key"))
        assertEquals(ProvisionErrorCopy.forError(ProvError.BAD_SSID, true), ProvisionErrorCopy.forRefusal(400, "bad_ssid"))
        assertEquals(ProvisionErrorCopy.forError(ProvError.BAD_WALLET, true), ProvisionErrorCopy.forRefusal(400, "bad_wallet"))
        assertTrue(ProvisionErrorCopy.forRefusal(403, "key_needs_secure_ap").contains("protected setup network"))
        assertTrue(ProvisionErrorCopy.forRefusal(409, "busy").contains("30 seconds"))
        assertTrue(ProvisionErrorCopy.forRefusal(500, null).contains("HTTP 500"))
    }
}
