package com.frynetworks.fryapp.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Once the stake transfer is broadcast, every failure must carry its transaction id so nothing can send a second transfer. */
class StakeFlowPostBroadcastTest {

    private fun failed(state: StakeState, event: StakeEvent): StakeState.Failed = reduce(state, event) as StakeState.Failed

    @Test
    fun `Submitting plus SubmitFailed keeps the txId of the state`() {
        val next = failed(StakeState.Submitting("TX1"), StakeEvent.SubmitFailed("TXN_NOT_CONFIRMED", "slow"))
        assertEquals("TX1", next.txId)
        assertTrue(next.recoverable)
    }

    @Test
    fun `AwaitingSignature plus SubmitFailed carries the txId derived before submit`() {
        val next = failed(StakeState.AwaitingSignature, StakeEvent.SubmitFailed("TIMEOUT", "no answer", txId = "TX2"))
        assertEquals("TX2", next.txId)
        assertTrue(next.recoverable)
    }

    @Test
    fun `Verifying plus VerifyFailed carries the txId`() {
        val next = failed(StakeState.Verifying, StakeEvent.VerifyFailed("TXN_VERIFY_FAILED", "no", txId = "TX3"))
        assertEquals("TX3", next.txId)
        assertTrue(next.recoverable)
    }

    @Test
    fun `Recording plus RecordFailed carries the txId`() {
        val next = failed(StakeState.Recording, StakeEvent.RecordFailed("NETWORK_ERROR", "offline", txId = "TX4"))
        assertEquals("TX4", next.txId)
        assertTrue(next.recoverable)
    }

    @Test
    fun `Cancel from Submitting keeps the txId`() {
        val next = failed(StakeState.Submitting("TX5"), StakeEvent.Cancel)
        assertEquals("CANCELLED", next.code)
        assertEquals("TX5", next.txId)
        assertTrue(next.recoverable)
    }

    @Test
    fun `failures before the broadcast carry no txId`() {
        assertNull(failed(StakeState.Precheck, StakeEvent.PrecheckFailed("X", "y")).txId)
        assertNull(failed(StakeState.AwaitingSignature, StakeEvent.SignRejected).txId)
        assertNull(failed(StakeState.AwaitingSignature, StakeEvent.SubmitFailed("X", "y")).txId)
        assertNull(failed(StakeState.CheckingBalances, StakeEvent.Cancel).txId)
    }
}
