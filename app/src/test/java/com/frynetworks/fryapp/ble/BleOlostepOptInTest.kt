package com.frynetworks.fryapp.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Best-effort contract of the Olostep opt-in write: [FakeGattBoard] models a board WITHOUT the
 * characteristic (older firmware), so the 6-arg overload must skip it and provision exactly as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleOlostepOptInTest {

    private val boardKey = "FEM-TESTKEY0000000000000000000000002"
    private val wallet = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"
    private val statusJson =
        """{"v":1,"proto":2,"caps":["key_write","error_reset","errs_v2"],"s":0,"e":0,"d":0,"k":1,"kc":0,"reg":0,"hb":-1,"fw":"0.4.0","ota":"valid"}"""

    @Test
    fun `a board without the opt-in characteristic is provisioned with no extra write and no failure`() = runTest {
        for (optIn in listOf(true, false)) {
            val board = FakeGattBoard(backgroundScope, boardKey, statusJson)
            val events = BleProvisioner(board.context)
                .provision("AA:BB:CC:DD:EE:FF", "lab", "pass", wallet, null, optIn)
                .transformWhile { event ->
                    emit(event)
                    !(event is ProvisionEvent.Failed ||
                        (event is ProvisionEvent.StatusUpdate && (event.status.state == ProvState.ERROR || event.status.state == ProvState.CONNECTED)))
                }
                .toList()
            assertTrue("$events", events.none { it is ProvisionEvent.Failed })
            assertEquals(
                listOf(FryGattContract.CHAR_WIFI_SSID, FryGattContract.CHAR_WIFI_PASS, FryGattContract.CHAR_WALLET),
                board.writes.map { it.first },
            )
        }
    }
}
