package com.frynetworks.fryapp.ui.common

import com.frynetworks.fryapp.domain.ClaimEvent
import com.frynetworks.fryapp.domain.ClaimState
import com.frynetworks.fryapp.domain.reduce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorCopyFeeCodesTest {

    private val codes = listOf("FEE_INVALID", "FEE_REUSED", "FEE_IN_USE", "FEE_REQUIRED", "FEE_UNVERIFIABLE")

    @Test
    fun `every fee rejection code has its own copy that points to support`() {
        for (code in codes) {
            assertTrue(code, ErrorCopy.isKnown(code))
            val text = ErrorCopy.forCode(code, "server text")
            assertTrue("$code: $text", text.contains("contact support", ignoreCase = true) && text.contains("Do not pay again"))
        }
    }

    @Test
    fun `an envelope failure with a fee code is not recoverable while other codes still are`() {
        for (code in codes) {
            val next = reduce(ClaimState.RequestingEnvelope, ClaimEvent.EnvelopeFailed(code, "m"))
            assertEquals(ClaimState.Failed(code, "m", recoverable = false), next)
            assertFalse(code, (next as ClaimState.Failed).recoverable)
        }
        assertTrue((reduce(ClaimState.RequestingEnvelope, ClaimEvent.EnvelopeFailed("NETWORK_ERROR", "m")) as ClaimState.Failed).recoverable)
        assertFalse((reduce(ClaimState.RequestingEnvelope, ClaimEvent.EnvelopeFailed("REWARD_ALREADY_CLAIMING", "m")) as ClaimState.Failed).recoverable)
    }
}
