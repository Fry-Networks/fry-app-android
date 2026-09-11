package com.frynetworks.fryapp.ui.common

import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.wallet.BridgeErrorCode
import com.frynetworks.fryapp.wallet.BridgeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ErrorCopyTest {

    private val specCodes = listOf(
        "NO_REWARDS", "REWARD_ON_HOLD", "WALLET_ASSET_NOT_OPTED_IN", "REWARD_VAULT_DEPLETED", "CLAIM_GROUP_EXPIRED",
        "REWARD_ALREADY_CLAIMING", "RATE_LIMIT_EXCEEDED", "WALLET_MISMATCH", "UNAUTHORIZED", "DEVICE_MISMATCH",
        "ACTIVITY_UNAVAILABLE", "NETWORK_ERROR", "USER_REJECTED", "TIMEOUT", "WALLET_NOT_INSTALLED", "NOT_WIRED",
    )

    @Test
    fun `every code in the blueprint list has dedicated, non-generic copy`() {
        for (code in specCodes) {
            val copy = ErrorCopy.forCode(code)
            assertTrue("$code should be known", ErrorCopy.isKnown(code))
            assertNotEquals("$code should not fall back to the generic line", ErrorCopy.GENERIC, copy)
            assertFalse("$code copy must not leak the code itself", copy.contains(code))
            assertTrue("$code copy should be a sentence", copy.endsWith(".") && copy.length > 15)
        }
    }

    @Test
    fun `a known code ignores the server message`() {
        assertEquals(ErrorCopy.forCode("NO_REWARDS"), ErrorCopy.forCode("NO_REWARDS", "server said something else"))
    }

    @Test
    fun `an unknown code falls back to the server message when present`() {
        assertEquals("Custom server text", ErrorCopy.forCode("SOMETHING_NEW", "Custom server text"))
    }

    @Test
    fun `an unknown code without a message and a null code both fall back to the generic line`() {
        assertEquals(ErrorCopy.GENERIC, ErrorCopy.forCode("SOMETHING_NEW"))
        assertEquals(ErrorCopy.GENERIC, ErrorCopy.forCode("SOMETHING_NEW", "   "))
        assertEquals(ErrorCopy.GENERIC, ErrorCopy.forCode(null))
    }

    @Test
    fun `CLAIM_GROUP_EXPIRED copy tells the user to start again`() {
        assertTrue(ErrorCopy.forCode("CLAIM_GROUP_EXPIRED").contains("Start again"))
    }

    @Test
    fun `toUiError maps dashboard, bridge and IO failures to codes with copy`() {
        val dashboard = DashboardException("REWARD_ON_HOLD", "on hold", httpStatus = 409).toUiError()
        assertEquals("REWARD_ON_HOLD", dashboard.code)
        assertEquals(ErrorCopy.forCode("REWARD_ON_HOLD"), dashboard.message)

        val bridge = BridgeException(BridgeErrorCode.USER_REJECTED, "rejected").toUiError()
        assertEquals("USER_REJECTED", bridge.code)
        assertEquals(ErrorCopy.forCode("USER_REJECTED"), bridge.message)

        val io = IOException("timeout").toUiError()
        assertEquals("NETWORK_ERROR", io.code)

        val unknown = IllegalStateException("boom").toUiError()
        assertEquals("UNKNOWN", unknown.code)
        assertEquals("boom", unknown.message)
    }

    @Test
    fun `errorCode extracts the wire code from each failure type`() {
        assertEquals("NOT_WIRED", DashboardException("NOT_WIRED", "x").errorCode())
        assertEquals("TIMEOUT", BridgeException(BridgeErrorCode.TIMEOUT, "x").errorCode())
        assertEquals("NETWORK_ERROR", IOException("x").errorCode())
        assertEquals("UNKNOWN", RuntimeException("x").errorCode())
    }
}
