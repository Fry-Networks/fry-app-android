package com.frynetworks.fryapp.provisioning

import com.frynetworks.fryapp.ble.FryGattContract
import com.frynetworks.fryapp.ble.ProvError
import com.frynetworks.fryapp.ble.ProvState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProvisioningReducerTest {

    private val validWallet = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"
    private val invalidWallet = "not-a-real-wallet-address"

    // --- fromStatusBytes ---

    @Test
    fun `status byte 3 maps to Connected`() {
        val status = ProvisioningReducer.fromStatusBytes(byteArrayOf(3))
        assertEquals(ProvState.CONNECTED, status.state)
        assertEquals(ProvError.NONE, status.error)
    }

    @Test
    fun `bytes 4,2 map to Error with code 2`() {
        val status = ProvisioningReducer.fromStatusBytes(byteArrayOf(4, 2))
        assertEquals(ProvState.ERROR, status.state)
        assertEquals(ProvError.WIFI_AUTH_FAILED, status.error)
        assertEquals(2, status.error.code)
    }

    @Test
    fun `status byte 0 maps to Idle`() {
        val status = ProvisioningReducer.fromStatusBytes(byteArrayOf(0))
        assertEquals(ProvState.IDLE, status.state)
    }

    // --- plan ---

    @Test
    fun `write order is ssid, pass, wallet`() {
        val steps = ProvisioningReducer.plan("MySSID", "MyPass", validWallet)
        assertEquals(3, steps.size)
        assertEquals(FryGattContract.CHAR_WIFI_SSID, steps[0].characteristic)
        assertEquals(FryGattContract.CHAR_WIFI_PASS, steps[1].characteristic)
        assertEquals(FryGattContract.CHAR_WALLET, steps[2].characteristic)
        assertTrue(steps[0].value.contentEquals("MySSID".toByteArray(Charsets.UTF_8)))
        assertTrue(steps[1].value.contentEquals("MyPass".toByteArray(Charsets.UTF_8)))
        assertTrue(steps[2].value.contentEquals(validWallet.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `empty password still produces a zero-length write step`() {
        val steps = ProvisioningReducer.plan("MySSID", "", validWallet)
        assertEquals(3, steps.size)
        assertEquals(FryGattContract.CHAR_WIFI_PASS, steps[1].characteristic)
        assertEquals(0, steps[1].value.size)
    }

    @Test
    fun `invalid wallet throws before any step is emitted`() {
        try {
            ProvisioningReducer.plan("MySSID", "MyPass", invalidWallet)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected — and since plan() builds the whole list only after validation
            // succeeds, no WriteStep is ever constructed on this path.
        }
    }

    @Test
    fun `unknown state code is rejected by the strict decoder`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProvisioningReducer.fromStatusBytes(byteArrayOf(9))
        }
    }

    @Test
    fun `unknown state code returns null from the tolerant decoder instead of throwing`() {
        assertNull(ProvisioningReducer.fromStatusBytesOrNull(byteArrayOf(9)))
    }

    @Test
    fun `unknown error code degrades to NONE rather than failing the whole decode`() {
        // Only the STATE code is strict. ProvError.fromCode already falls back to NONE, so an
        // unrecognised error byte must still yield a usable ERROR status rather than nothing.
        val decoded = ProvisioningReducer.fromStatusBytesOrNull(byteArrayOf(4, 99))
        assertEquals(ProvState.ERROR, decoded?.state)
        assertEquals(ProvError.NONE, decoded?.error)
    }

    @Test
    fun `tolerant decoder still decodes a valid payload`() {
        val decoded = ProvisioningReducer.fromStatusBytesOrNull(byteArrayOf(4, 2))
        assertEquals(ProvState.ERROR, decoded?.state)
        assertEquals(ProvError.WIFI_AUTH_FAILED, decoded?.error)
    }

    @Test
    fun `empty payload returns null from the tolerant decoder`() {
        assertNull(ProvisioningReducer.fromStatusBytesOrNull(byteArrayOf()))
    }
}
