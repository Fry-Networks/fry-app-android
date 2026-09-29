package com.frynetworks.fryapp.ble

import com.frynetworks.fryapp.provisioning.ProvStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real BLE session against a scripted board ([FakeGattBoard]): what reaches `09`, when the
 * session stops before writing (PROTOCOL.md 11.8), and how long the writes may take.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleKeyStepTest {

    private val ownerKey = "FEM-TESTKEY0000000000000000000000001"
    private val boardKey = "FEM-TESTKEY0000000000000000000000002"
    private val wallet = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"
    private val wifiSteps = listOf(FryGattContract.CHAR_WIFI_SSID, FryGattContract.CHAR_WIFI_PASS, FryGattContract.CHAR_WALLET)

    private fun v11(s: Int = 0, d: Int = 0) =
        """{"v":1,"proto":2,"caps":["key_write","error_reset","errs_v2"],"s":$s,"e":${if (s == 4) 4 else 0},"d":$d,"k":1,"kc":0,"reg":0,"hb":-1,"fw":"0.4.0","ota":"valid"}"""

    private fun TestScope.board(statusJson: String? = v11(), mtuGranted: Boolean = true) = FakeGattBoard(backgroundScope, boardKey, statusJson, mtuGranted)

    /** Collects until the session is decided: a failure, an Error status, the read-back after a key write, or Connected without one. */
    private suspend fun session(board: FakeGattBoard, ownerKey: String?): List<ProvisionEvent> =
        BleProvisioner(board.context).provision("AA:BB:CC:DD:EE:FF", "lab", "pass", wallet, ownerKey)
            .transformWhile { event ->
                emit(event)
                when (event) {
                    is ProvisionEvent.Failed, is ProvisionEvent.KeyReadBack, ProvisionEvent.Handoff -> false
                    is ProvisionEvent.StatusUpdate ->
                        !(event.status.state == ProvState.ERROR || (event.status.state == ProvState.CONNECTED && board.written(FryGattContract.CHAR_MINER_KEY_WRITE) == null))
                    else -> true
                }
            }
            .toList()

    private fun List<ProvisionEvent>.lastStatus(): ProvStatus? = filterIsInstance<ProvisionEvent.StatusUpdate>().lastOrNull()?.status
    private fun List<ProvisionEvent>.failure(): String? = filterIsInstance<ProvisionEvent.Failed>().firstOrNull()?.reason

    @Test
    fun `the owner's key, not the board's, is what reaches 09`() = runTest {
        val board = board()
        val events = session(board, ownerKey)
        assertEquals(ownerKey, board.written(FryGattContract.CHAR_MINER_KEY_WRITE)?.toString(Charsets.US_ASCII))
        assertEquals(listOf(FryGattContract.CHAR_MINER_KEY_WRITE) + wifiSteps, board.writes.map { it.first })
        assertNull(events.failure())
        assertTrue("$events", events.any { it is ProvisionEvent.KeyReadBack })
    }

    @Test
    fun `with no owner key nothing is written to 09 and no read-back is reported`() = runTest {
        val board = board()
        val events = session(board, null)
        assertNull(board.written(FryGattContract.CHAR_MINER_KEY_WRITE))
        assertEquals(wifiSteps, board.writes.map { it.first })
        assertEquals(ProvStatus(ProvState.CONNECTED), events.lastStatus())
        assertTrue("$events", events.none { it is ProvisionEvent.KeyReadBack })
    }

    @Test
    fun `a running board whose writes would be ignored stops before any write and asks for the key`() = runTest {
        for (d in listOf(6, 7, 8, 9, 10, 11, 12)) {
            val board = board(v11(s = 4, d = d))
            val events = session(board, null)
            assertTrue("detail $d: ${board.writes}", board.writes.isEmpty())
            assertEquals("detail $d", ProvStatus(ProvState.ERROR, ProvError.KEY_REQUIRED), events.lastStatus())
        }
    }

    @Test
    fun `a board still retrying registration or unreachable keeps its own error instead of asking for a key`() = runTest {
        for ((d, error) in listOf(4 to ProvError.HARDWAREAPI_REGISTRATION_FAILED, 13 to ProvError.UNREACHABLE)) {
            val board = board(v11(s = 4, d = d))
            val events = session(board, null)
            assertTrue("detail $d: ${board.writes}", board.writes.isEmpty())
            assertEquals("detail $d", ProvStatus(ProvState.ERROR, error), events.lastStatus())
        }
    }

    @Test
    fun `with an owner key the same board is written to, key first, so the link pairs`() = runTest {
        val board = board(v11(s = 4, d = 9))
        val events = session(board, ownerKey)
        assertEquals(listOf(FryGattContract.CHAR_MINER_KEY_WRITE) + wifiSteps, board.writes.map { it.first })
        assertNull(events.failure())
    }

    @Test
    fun `the 09 write may take up to 30 s on the simple write path`() = runTest {
        val board = board().apply { writeDelayMs = mapOf(FryGattContract.CHAR_MINER_KEY_WRITE to 12_000L) }
        val events = session(board, ownerKey)
        assertNull(events.failure())
        assertEquals(ownerKey, board.written(FryGattContract.CHAR_MINER_KEY_WRITE)?.toString(Charsets.US_ASCII))
        assertTrue("$events", events.any { it is ProvisionEvent.KeyReadBack })
        assertTrue("took ${currentTime} ms", currentTime >= 12_000L)
    }

    @Test
    fun `the 09 write may take up to 30 s on the prepared write path too`() = runTest {
        val board = board(mtuGranted = false).apply {
            writeDelayMs = mapOf(FryGattContract.CHAR_MINER_KEY_WRITE to 12_000L)
            executeDelayMs = mapOf(FryGattContract.CHAR_MINER_KEY_WRITE to 12_000L)
        }
        val events = session(board, ownerKey)
        assertNull(events.failure())
        assertTrue("${board.preparedWrites}", FryGattContract.CHAR_MINER_KEY_WRITE in board.preparedWrites)
        assertTrue("$events", events.any { it is ProvisionEvent.KeyReadBack })
    }

    @Test
    fun `a 09 write slower than 30 s still fails, and every other write still has 5 s`() = runTest {
        val slowKey = board().apply { writeDelayMs = mapOf(FryGattContract.CHAR_MINER_KEY_WRITE to 31_000L) }
        assertEquals("Write failed for characteristic ${FryGattContract.CHAR_MINER_KEY_WRITE}", session(slowKey, ownerKey).failure())

        val slowSsid = board().apply { writeDelayMs = mapOf(FryGattContract.CHAR_WIFI_SSID to 6_000L) }
        assertEquals("Write failed for characteristic ${FryGattContract.CHAR_WIFI_SSID}", session(slowSsid, ownerKey).failure())

        // The wallet is a prepared write too: its execute keeps the 5 s window.
        val slowWalletExecute = board(mtuGranted = false).apply { executeDelayMs = mapOf(FryGattContract.CHAR_WALLET to 6_000L) }
        assertEquals("Write failed for characteristic ${FryGattContract.CHAR_WALLET}", session(slowWalletExecute, ownerKey).failure())
    }
}
