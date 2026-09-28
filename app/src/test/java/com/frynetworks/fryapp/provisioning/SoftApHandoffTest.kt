package com.frynetworks.fryapp.provisioning

import com.frynetworks.fryapp.wifi.errCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftApHandoffTest {

    @Test
    fun `losing the AP after the settings were accepted is a handoff`() {
        assertTrue(SoftApHandoff.isHandoff(accepted = true, consecutiveFailures = 0, apLost = true))
        assertTrue(SoftApHandoff.isHandoff(accepted = true, consecutiveFailures = SoftApHandoff.FAILURES_FOR_HANDOFF, apLost = false))
    }

    @Test
    fun `a single missed status poll is not yet a handoff`() {
        assertFalse(SoftApHandoff.isHandoff(accepted = true, consecutiveFailures = SoftApHandoff.FAILURES_FOR_HANDOFF - 1, apLost = false))
    }

    @Test
    fun `before acceptance nothing is a handoff`() {
        assertFalse(SoftApHandoff.isHandoff(accepted = false, consecutiveFailures = 10, apLost = true))
    }

    @Test
    fun `the err field of a provision error body is read, anything else is null`() {
        assertEquals("key_required", errCode("""{"err":"key_required"}"""))
        assertEquals("busy", errCode("""{"err":"busy","status":2}"""))
        assertEquals(null, errCode("""{"ok":false}"""))
        assertEquals(null, errCode("<html>"))
        assertEquals(null, errCode(null))
    }
}
