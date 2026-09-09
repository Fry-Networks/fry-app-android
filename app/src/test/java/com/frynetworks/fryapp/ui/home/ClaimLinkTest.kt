package com.frynetworks.fryapp.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class ClaimLinkTest {

    private val wallet = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"

    @Test
    fun `returns the exact verified rewards-claim URL`() {
        // Asserts the literal final string, not just a host/prefix check, so a future edit
        // cannot silently reintroduce the 404'ing /claim?wallet=... URL this replaced.
        assertEquals("https://dashboard.frynetworks.com/rewards-claim", ClaimLink.build(wallet))
    }

    @Test
    fun `blank wallet returns null so the caller can route to Settings`() {
        assertNull(ClaimLink.build(""))
        assertNull(ClaimLink.build("   "))
    }

    @Test
    fun `the wallet value is never appended to the URL`() {
        val url = requireNotNull(ClaimLink.build(wallet))
        assertFalse(url.contains(wallet))
        assertFalse(url.contains("?"))
    }
}
