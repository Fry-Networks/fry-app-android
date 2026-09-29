package com.frynetworks.fryapp.provisioning

import com.frynetworks.fryapp.ble.DeviceStatusJson
import com.frynetworks.fryapp.ble.ProvError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The BLE key step as a pure decision: only the owner's key is ever a write candidate, a running
 * board in an ignored-writes state stops the session with the right copy, and a board that will
 * recover by itself keeps its own error instead of "key needed".
 */
class KeyPlanTest {

    private val ownerKey = "FEM-TESTKEY0000000000000000000000001"

    private fun v11(s: Int = 0, d: Int = 0, caps: String = """"key_write","error_reset","errs_v2"""") = DeviceStatusJson.parse(
        """{"v":1,"proto":2,"caps":[$caps],"s":$s,"e":${if (s == 4) 4 else 0},"d":$d,"k":1,"kc":0,"reg":0,"hb":-1,"fw":"0.4.0","ota":"valid"}""",
    )

    @Test
    fun `the owner's key is written to a key_write board, whatever the board holds`() {
        assertEquals(KeyPlan.Write(ownerKey), KeyTransportPolicy.planKeySteps(ownerKey, v11()))
        assertEquals(KeyPlan.Write(ownerKey), KeyTransportPolicy.planKeySteps(ownerKey, v11(s = 4, d = 9)))
    }

    @Test
    fun `no owner key means no key step, on an idle v1-1 board and on a protocol-1 board`() {
        assertEquals(KeyPlan.NoKeyStep, KeyTransportPolicy.planKeySteps(null, v11()))
        assertEquals(KeyPlan.NoKeyStep, KeyTransportPolicy.planKeySteps(null, DeviceCapabilities.PROTO_1))
        assertEquals(KeyPlan.NoKeyStep, KeyTransportPolicy.planKeySteps(ownerKey, DeviceCapabilities.PROTO_1))
        assertEquals(KeyPlan.NoKeyStep, KeyTransportPolicy.planKeySteps(ownerKey, v11(caps = "\"error_reset\"")))
    }

    @Test
    fun `a running board in an API-side error with no key entered stops with KEY_REQUIRED`() {
        for (d in listOf(6, 7, 8, 9, 10, 11, 12)) {
            assertEquals("detail $d", KeyPlan.Stop(ProvError.KEY_REQUIRED), KeyTransportPolicy.planKeySteps(null, v11(s = 4, d = d)))
        }
    }

    @Test
    fun `a Wi-Fi error or a board that is not in Error writes as usual`() {
        assertEquals(KeyPlan.NoKeyStep, KeyTransportPolicy.planKeySteps(null, v11(s = 4, d = 2)))
        assertEquals(KeyPlan.NoKeyStep, KeyTransportPolicy.planKeySteps(null, v11(s = 3, d = 9)))
    }
}
