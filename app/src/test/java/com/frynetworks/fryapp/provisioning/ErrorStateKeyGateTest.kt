package com.frynetworks.fryapp.provisioning

import com.frynetworks.fryapp.ble.DeviceStatusJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROTOCOL.md 11.8: a running v1.1 board in an API-side error (4, 6, 9-13) ignores SSID, password
 * and wallet writes over an unencrypted BLE link. Without an owner key to write (the `09` write is
 * what pairs the link) the app must stop before writing and ask for the FEM- key.
 */
class ErrorStateKeyGateTest {

    private val ownerKey = "FEM-TESTKEY0000000000000000000000001"

    private fun status(s: Int, d: Int, proto: Int = 2) = DeviceStatusJson.parse(
        """{"v":1,"proto":$proto,"caps":["key_write","error_reset","errs_v2"],"s":$s,"e":${if (s == 4) 4 else 0},"d":$d,"k":1,"kc":0,"reg":401,"hb":-1,"fw":"0.4.0","ota":"valid"}""",
    )

    @Test
    fun `0A reports the board's state and error detail`() {
        val caps = status(4, 9)
        assertEquals(4, caps.state)
        assertEquals(9, caps.detail)
        assertNull(DeviceCapabilities.PROTO_1.state)
        assertNull(DeviceStatusJson.parse("""{"v":1,"proto":2,"caps":["key_write"]}""").detail)
    }

    @Test
    fun `an API-side error with no key to write needs the key first`() {
        for (d in listOf(4, 6, 9, 10, 11, 12, 13)) {
            assertTrue("detail $d", KeyTransportPolicy.keyNeededBeforeWrite(status(4, d), ownerKey = null))
        }
        assertEquals(setOf(4, 6, 9, 10, 11, 12, 13), KeyTransportPolicy.API_SIDE_ERRORS)
    }

    @Test
    fun `a key about to be written, a Wi-Fi error, a key refusal or a board that is not in Error write as usual`() {
        assertFalse("owner key pairs the link", KeyTransportPolicy.keyNeededBeforeWrite(status(4, 11), ownerKey))
        for (d in listOf(1, 2, 3, 5, 7, 8)) {
            assertFalse("detail $d resets on an SSID write", KeyTransportPolicy.keyNeededBeforeWrite(status(4, d), null))
        }
        for (s in listOf(0, 1, 2, 3)) {
            assertFalse("state $s", KeyTransportPolicy.keyNeededBeforeWrite(status(s, 9), null))
        }
    }

    @Test
    fun `a protocol-1 board and a 0A without state never trigger the gate`() {
        assertFalse(KeyTransportPolicy.keyNeededBeforeWrite(status(4, 9, proto = 1), null))
        assertFalse(KeyTransportPolicy.keyNeededBeforeWrite(DeviceCapabilities.PROTO_1, null))
        assertFalse(KeyTransportPolicy.keyNeededBeforeWrite(DeviceCapabilities(proto = 2, caps = setOf("key_write")), null))
    }
}
