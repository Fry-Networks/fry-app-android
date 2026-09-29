package com.frynetworks.fryapp.ble

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pairing for the encrypted `09` write, against the two Android behaviours a Galaxy S22 (Android 16)
 * showed with a v0.4.0 ESP32-C3 on 2026-09-29: a `09` write that itself starts pairing is lost, and
 * a bond Android kept but the board did not is dropped on the next connect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlePairingTest {

    private val ownerKey = "FEM-TESTKEY0000000000000000000000001"
    private val wallet = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"
    private val wifiSteps = listOf(FryGattContract.CHAR_WIFI_SSID, FryGattContract.CHAR_WIFI_PASS, FryGattContract.CHAR_WALLET)
    private val boardKey = "FEM-TESTKEY0000000000000000000000002"
    private fun v11(k: Int) =
        """{"v":1,"proto":2,"caps":["key_write","error_reset","errs_v2"],"s":0,"e":0,"d":0,"k":$k,"kc":0,"reg":0,"hb":-1,"fw":"0.4.0","ota":"valid"}"""

    /** A v1.1 board (keyless unless [hasKey]) on an Android that loses the write that starts pairing (the S22). */
    private fun TestScope.board(hasKey: Boolean = false) = FakeGattBoard(backgroundScope, if (hasKey) boardKey else "", v11(if (hasKey) 1 else 0)).apply {
        losesWriteThatPairs = true
        pairDelayMs = 1_500L
    }

    private fun flow(board: FakeGattBoard, key: String?) =
        BleProvisioner(board.context).provision("AA:BB:CC:DD:EE:FF", "lab", "pass", wallet, key)

    /** Until the session is decided: a failure, an Error status, the read-back, or Connected without a key write. */
    private suspend fun session(board: FakeGattBoard, key: String?): List<ProvisionEvent> =
        flow(board, key).transformWhile { event ->
            emit(event)
            when (event) {
                is ProvisionEvent.Failed, is ProvisionEvent.KeyReadBack, ProvisionEvent.Handoff -> false
                is ProvisionEvent.StatusUpdate ->
                    !(event.status.state == ProvState.ERROR || (event.status.state == ProvState.CONNECTED && board.written(FryGattContract.CHAR_MINER_KEY_WRITE) == null))
                else -> true
            }
        }.toList()

    /** Everything the flow emits within [ms] of virtual time. */
    private suspend fun everything(board: FakeGattBoard, key: String?, ms: Long = 200_000L): List<ProvisionEvent> {
        val events = mutableListOf<ProvisionEvent>()
        withTimeoutOrNull(ms) { flow(board, key).toList(events) }
        return events
    }

    private fun List<ProvisionEvent>.failures() = filterIsInstance<ProvisionEvent.Failed>().map { it.reason }

    @Test
    fun `the key reaches 09 because the phone pairs before writing it`() = runTest {
        val board = board()
        val events = session(board, ownerKey)
        assertEquals(emptyList<String>(), events.failures())
        assertEquals(1, board.createBondCalls)
        assertEquals(ownerKey, board.written(FryGattContract.CHAR_MINER_KEY_WRITE)?.toString(Charsets.US_ASCII))
        assertEquals(listOf(FryGattContract.CHAR_MINER_KEY_WRITE) + wifiSteps, board.writes.map { it.first })
        assertTrue("$events", events.any { it is ProvisionEvent.KeyReadBack })
        assertEquals(0, board.cleartextKeyWrites)
    }

    @Test
    fun `a bond the board no longer has costs one reconnect, not the session`() = runTest {
        val board = board().apply {
            bondState = BluetoothDevice.BOND_BONDED
            staleBond = true
        }
        val events = session(board, ownerKey)
        assertEquals(emptyList<String>(), events.failures())
        assertEquals(2, board.connects)
        assertEquals(1, board.createBondCalls)
        assertEquals(ownerKey, board.written(FryGattContract.CHAR_MINER_KEY_WRITE)?.toString(Charsets.US_ASCII))
        assertTrue("$events", events.any { it is ProvisionEvent.KeyReadBack })
        assertEquals(0, board.cleartextKeyWrites)
    }

    @Test
    fun `after a successful retry no failure of the first session is reported, ever`() = runTest {
        val board = board().apply {
            bondState = BluetoothDevice.BOND_BONDED
            staleBond = true
        }
        val events = everything(board, ownerKey)
        assertEquals(emptyList<String>(), events.failures())
        assertTrue("$events", events.any { it is ProvisionEvent.KeyReadBack })
    }

    @Test
    fun `a board that reports no enc (firmware 0_4_0) is told to use USB and gets nothing`() = runTest {
        val board = board().apply { reportsEnc = false }
        val events = session(board, ownerKey)
        assertEquals(listOf(KEY_WRITE_NEEDS_USB), events.failures())
        assertEquals(0, board.createBondCalls)
        assertTrue("${board.writes}", board.writes.isEmpty())
    }

    @Test
    fun `a bonded link the board does not see encrypted gets no key`() = runTest {
        val board = board().apply {
            bondState = BluetoothDevice.BOND_BONDED
            encryptsBondedOnConnect = false
        }
        val events = session(board, ownerKey)
        assertEquals(listOf(LINK_NOT_ENCRYPTED), events.failures())
        assertEquals(0, board.cleartextKeyWrites)
        assertNull(board.written(FryGattContract.CHAR_MINER_KEY_WRITE))
    }

    @Test
    fun `a refused createBond fails at once`() = runTest {
        val board = board().apply { createBondRefused = true }
        val events = session(board, ownerKey)
        assertEquals(listOf("Pairing failed"), events.failures())
        assertTrue("took $currentTime ms", currentTime < 5_000L)
        assertTrue("${board.writes}", board.writes.isEmpty())
    }

    @Test
    fun `a stale bond is retried once even when the board then keeps its own key`() = runTest {
        val board = board(hasKey = true).apply {
            bondState = BluetoothDevice.BOND_BONDED
            staleBond = true
        }
        val events = session(board, null)
        assertEquals(emptyList<String>(), events.failures())
        assertEquals(2, board.connects)
        assertEquals(wifiSteps, board.writes.map { it.first })
    }

    @Test
    fun `a board still in Error 6 from an attempt without the key takes the key and starts over`() = runTest {
        // Seen on the C3: after a no-key attempt the board sits in Error 6; the key write re-sends that status.
        val board = board().apply { statusAfterKey = byteArrayOf(4, 4, ProvError.KEY_REQUIRED.code.toByte()) }
        val events = session(board, ownerKey)
        assertEquals(emptyList<String>(), events.failures())
        assertEquals(listOf(FryGattContract.CHAR_MINER_KEY_WRITE) + wifiSteps, board.writes.map { it.first })
        assertTrue("$events", events.any { it is ProvisionEvent.KeyReadBack })
        // Never shown: the UI (and the :qa test) would stop on it.
        assertTrue("$events", events.filterIsInstance<ProvisionEvent.StatusUpdate>().none { it.status.state == ProvState.ERROR })
    }

    @Test
    fun `a key the board refuses still stops the session before the Wi-Fi writes`() = runTest {
        for (refusal in listOf(ProvError.BAD_KEY, ProvError.KEY_LOCKED)) {
            val board = board().apply { statusAfterKey = byteArrayOf(4, 4, refusal.code.toByte()) }
            val events = session(board, ownerKey)
            assertEquals("$refusal", listOf(FryGattContract.CHAR_MINER_KEY_WRITE), board.writes.map { it.first })
            assertEquals("$refusal", ProvState.ERROR, events.filterIsInstance<ProvisionEvent.StatusUpdate>().last().status.state)
        }
    }

    @Test
    fun `a declined pairing stops before anything is written`() = runTest {
        val board = board().apply { pairingDeclined = true }
        val events = session(board, ownerKey)
        assertEquals(listOf("Pairing failed"), events.failures())
        assertTrue("${board.writes}", board.writes.isEmpty())
        assertTrue("declined is seen, not waited out: took $currentTime ms", currentTime < 10_000L)
    }

    @Test
    fun `a pairing that never finishes gives up after 30 s`() = runTest {
        val board = board().apply { pairDelayMs = 60_000L }
        val events = session(board, ownerKey)
        assertEquals(listOf("Pairing failed"), events.failures())
        assertTrue("${board.writes}", board.writes.isEmpty())
    }

    @Test
    fun `with no owner key the phone is never asked to pair`() = runTest {
        val board = board(hasKey = true)
        val events = session(board, null)
        assertEquals(emptyList<String>(), events.failures())
        assertEquals(0, board.createBondCalls)
        assertEquals(wifiSteps, board.writes.map { it.first })
    }

    @Test
    fun `a board the phone is still bonded to is not paired again`() = runTest {
        val board = board().apply { bondState = BluetoothDevice.BOND_BONDED }
        val events = session(board, ownerKey)
        assertEquals(emptyList<String>(), events.failures())
        assertEquals(0, board.createBondCalls)
        assertEquals(1, board.connects)
        assertEquals(ownerKey, board.written(FryGattContract.CHAR_MINER_KEY_WRITE)?.toString(Charsets.US_ASCII))
        assertEquals(0, board.cleartextKeyWrites)
    }

    @Test
    fun `a failed session reports its own reason and no timeout on top`() = runTest {
        val board = board().apply { writeDelayMs = mapOf(FryGattContract.CHAR_WIFI_SSID to 6_000L) }
        assertEquals(
            listOf("Write failed for characteristic ${FryGattContract.CHAR_WIFI_SSID}"),
            everything(board, ownerKey).failures(),
        )
    }

    @Test
    fun `a bonded board's own failure is still reported when no retry is due`() = runTest {
        val board = board().apply {
            bondState = BluetoothDevice.BOND_BONDED
            writeDelayMs = mapOf(FryGattContract.CHAR_WIFI_SSID to 6_000L)
        }
        val events = everything(board, ownerKey)
        assertEquals(listOf("Write failed for characteristic ${FryGattContract.CHAR_WIFI_SSID}"), events.failures())
        assertEquals(1, board.connects)
    }

    @Test
    fun `a session that really runs out of time still says so`() = runTest {
        val board = board().apply { statusAfterWallet = byteArrayOf(2) } // stays Connecting
        val events = everything(board, ownerKey)
        assertEquals(listOf("Provisioning timed out"), events.failures())
        assertNull(events.filterIsInstance<ProvisionEvent.KeyReadBack>().firstOrNull())
    }
}
