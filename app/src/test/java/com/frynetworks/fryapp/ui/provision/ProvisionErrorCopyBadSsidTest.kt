package com.frynetworks.fryapp.ui.provision

import com.frynetworks.fryapp.ble.ProvError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the owner reads when the board reports "network not found" (device error 1, BAD_SSID). The mapping already existed; this test
 * only keeps it, and keeps the wrong-password copy (WIFI_AUTH_FAILED) distinct and unchanged.
 */
class ProvisionErrorCopyBadSsidTest {

    @Test
    fun `device error 1 is headlined Wi-Fi network not found`() {
        assertEquals(1, ProvError.BAD_SSID.code)
        assertEquals("Wi-Fi network not found", ProvisionErrorCopy.title(ProvError.BAD_SSID))
    }

    @Test
    fun `the body says the network was not found and points at the name and the 2_4 GHz band`() {
        val text = ProvisionErrorCopy.forError(ProvError.BAD_SSID, errorResetSupported = true)
        assertEquals(
            "The board could not find that Wi-Fi network. Check the name (it is case-sensitive) and that the network is 2.4 GHz: ESP boards cannot see 5 GHz networks.",
            text,
        )
        assertTrue(!text.contains("password", ignoreCase = true))
    }

    @Test
    fun `a stored not-found body is headlined with the same title`() {
        val body = ProvisionErrorCopy.forError(ProvError.BAD_SSID, errorResetSupported = true)
        assertEquals("Wi-Fi network not found", ProvisionErrorCopy.titleFor(body))
        assertEquals("Error: Wi-Fi network not found\n$body", ProvisionErrorCopy.statusLine(body))
    }

    @Test
    fun `the wrong-password copy is unchanged and is not the not-found copy`() {
        assertEquals("Wi-Fi password not accepted", ProvisionErrorCopy.title(ProvError.WIFI_AUTH_FAILED))
        assertEquals(
            "The Wi-Fi password was not accepted. Check it and try again.",
            ProvisionErrorCopy.forError(ProvError.WIFI_AUTH_FAILED, errorResetSupported = true),
        )
    }
}
