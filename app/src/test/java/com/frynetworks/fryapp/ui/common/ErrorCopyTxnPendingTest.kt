package com.frynetworks.fryapp.ui.common

import com.frynetworks.fryapp.domain.ClaimState
import com.frynetworks.fryapp.domain.StakeState
import com.frynetworks.fryapp.ui.miners.claim.ClaimUiState
import com.frynetworks.fryapp.ui.miners.stake.StakeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** TXN_PENDING has its own line, used only when the failure carries no message of its own; a specific message always wins. */
class ErrorCopyTxnPendingTest {

    private val pending = "This transaction is still pending. Try again shortly."
    private val support = "A previous claim fee payment may have been sent and is still pending. Do not try again: contact support so it can be checked."

    @Test
    fun `a blank, empty or missing message gets the pending copy`() {
        assertEquals(pending, ErrorCopy.forCode("TXN_PENDING"))
        assertEquals(pending, ErrorCopy.forCode("TXN_PENDING", null))
        assertEquals(pending, ErrorCopy.forCode("TXN_PENDING", ""))
        assertEquals(pending, ErrorCopy.forCode("TXN_PENDING", "   "))
    }

    @Test
    fun `a specific message still wins over the pending copy`() {
        assertEquals(support, ErrorCopy.forCode("TXN_PENDING", support))
        assertEquals("Another stake transaction is still being processed.", ErrorCopy.forCode("TXN_PENDING", " Another stake transaction is still being processed. "))
    }

    @Test
    fun `other unknown codes with no message are still generic, and known codes are untouched`() {
        assertEquals(ErrorCopy.GENERIC, ErrorCopy.forCode("TXN_RECORD_FAILED", ""))
        assertEquals(ErrorCopy.GENERIC, ErrorCopy.forCode(null, ""))
        assertEquals("Cancelled.", ErrorCopy.forCode("CANCELLED", ""))
        assertFalse(ErrorCopy.isKnown("TXN_PENDING"))
    }

    @Test
    fun `the claim and stake sheets show the pending copy for a blank failure`() {
        assertEquals(pending, ClaimUiState(state = ClaimState.Failed("TXN_PENDING", "", recoverable = true)).errorCopy)
        assertEquals(pending, StakeUiState(state = StakeState.Failed("TXN_PENDING", "", recoverable = true)).errorCopy)
        assertEquals(support, ClaimUiState(state = ClaimState.Failed("TXN_PENDING", support, recoverable = true)).errorCopy)
    }
}
