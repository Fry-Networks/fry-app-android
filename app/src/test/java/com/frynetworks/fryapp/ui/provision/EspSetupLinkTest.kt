package com.frynetworks.fryapp.ui.provision

import com.frynetworks.fryapp.ble.KEY_WRITE_NEEDS_USB
import com.frynetworks.fryapp.ble.ProvError
import com.frynetworks.fryapp.provisioning.KeyTransportPolicy
import com.frynetworks.fryapp.ui.scan.DiscoveryInputs
import com.frynetworks.fryapp.ui.scan.DiscoveryPreflight
import com.frynetworks.fryapp.wifi.SOFTAP_NEEDS_ANDROID_10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2 r1 MINOR-1..3: a guide link is never followed by a full stop (it would join the anchor if the
 * text is ever linkified), the ESP8266 notices point at the setup-page steps (#no-android), and
 * the open-AP refusal says the board keeps its key.
 */
class EspSetupLinkTest {

    private val guide = "https://docs.frynetworks.com/docs/esp-miners.html"

    private fun inputs(sdk: Int, ble: Boolean) = DiscoveryInputs(sdk, emptyMap(), ble, bluetoothOn = true, locationOn = true, wifiOn = true)

    private val softAp = "Setting up an ESP8266 over its Wi-Fi setup network needs Android 10 or newer. " +
        "You can set it up from any phone or computer through the board's own Wi-Fi setup page: $guide#no-android"

    @Test
    fun `the ESP8266 pre-Android-10 notice is the same everywhere and points at the setup-page steps`() {
        assertEquals(softAp, SOFTAP_NEEDS_ANDROID_10)
        assertEquals(softAp, DiscoveryPreflight.evaluate(inputs(28, ble = true)).softAp.issues.single { it.code == "softap_unsupported" }.message)
    }

    @Test
    fun `the open-AP refusal says the board keeps its key`() {
        assertEquals(
            "This board's setup network is open, so Fry will not send a miner key over it. The board keeps the key it already has: $guide#esp8266",
            KeyTransportPolicy.OPEN_AP_REFUSAL,
        )
    }

    @Test
    fun `no guide link is followed by a full stop`() {
        val texts = mapOf(
            "ble_unsupported" to DiscoveryPreflight.evaluate(inputs(36, ble = false)).ble.issues.single { it.code == "ble_unsupported" }.message,
            "softap_unsupported" to DiscoveryPreflight.evaluate(inputs(28, ble = true)).softAp.issues.single { it.code == "softap_unsupported" }.message,
            "OPEN_AP_REFUSAL" to KeyTransportPolicy.OPEN_AP_REFUSAL,
            "SOFTAP_NEEDS_ANDROID_10" to SOFTAP_NEEDS_ANDROID_10,
            "KEY_LOCKED" to ProvisionErrorCopy.forError(ProvError.KEY_LOCKED, errorResetSupported = true),
            "KEY_WRITE_NEEDS_USB" to ProvisionErrorCopy.forFailure(KEY_WRITE_NEEDS_USB),
        )
        val link = Regex("""https://docs\.frynetworks\.com/docs/esp-miners\.html#[a-z0-9-]+""")
        for ((site, text) in texts) {
            val m = link.findAll(text).toList()
            assertTrue("$site has no guide link: $text", m.isNotEmpty())
            for (u in m) assertFalse("$site: '.' right after ${u.value}", text.startsWith(".", u.range.last + 1))
        }
        // Positive control: the check sees a stop glued to a link.
        assertTrue("x $guide#flash.".let { t -> link.findAll(t).any { t.startsWith(".", it.range.last + 1) } })
    }
}
