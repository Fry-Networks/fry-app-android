package com.frynetworks.fryapp.ui.provision

import com.frynetworks.fryapp.ble.ProvError
import com.frynetworks.fryapp.data.keys.KeyCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IV6 A4 (U2/U3): a key refused because another install holds it says why, and points to the key
 * the board made for itself (the Identity card).
 */
class ProvisionLeaseDenialCopyTest {

    private val guidance = "This board has its own FEM key. Find it on the Identity card and register that key."

    @Test
    fun `the BLE key-in-use error keeps its reason and adds the own-key guidance`() {
        val text = ProvisionErrorCopy.forError(ProvError.REG_KEY_IN_USE)
        assertTrue(text, text.startsWith("This miner key is already active on another install."))
        assertTrue(text, text.contains(guidance))
        assertFalse(text, text.contains('—'))
        assertEquals("Miner key active elsewhere", ProvisionErrorCopy.titleFor(text))
    }

    @Test
    fun `a key check that carries lease_held_elsewhere shows the guidance after the dashboard's words`() {
        val r = KeyCheckResult.Checked("ok_active_elsewhere", "you", "Runs on another install.", null, emptyList(), "blocked_until_other_stops", true, "lease_held_elsewhere")
        assertEquals("Runs on another install.\n$guidance", KeyCheckCopy.summary(r))
        val plain = KeyCheckResult.Checked("ok_active_elsewhere", "you", "Runs on another install.", null, emptyList(), null, true)
        assertEquals("Runs on another install.", KeyCheckCopy.summary(plain))
    }

    @Test
    fun `the fallback for a key active elsewhere also carries the guidance`() {
        val r = KeyCheckResult.Checked("ok_active_elsewhere", "you", null, null, emptyList(), null, true)
        assertEquals("This key is yours but already active on another install.\n$guidance", KeyCheckCopy.summary(r))
    }
}
