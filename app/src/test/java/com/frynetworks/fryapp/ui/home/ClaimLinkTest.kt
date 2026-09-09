package com.frynetworks.fryapp.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaimLinkTest {

    private val wallet = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"

    @Test
    fun `builds the dashboard claim URL with the wallet as a query param`() {
        val url = ClaimLink.build(wallet)
        assertEquals("https://dashboard.frynetworks.com/claim?wallet=$wallet", url)
    }

    @Test
    fun `blank wallet returns null so the caller can route to Settings`() {
        assertNull(ClaimLink.build(""))
        assertNull(ClaimLink.build("   "))
    }

    @Test
    fun `special characters in the wallet are URL-encoded`() {
        val url = ClaimLink.build("A B+C")
        assertEquals("https://dashboard.frynetworks.com/claim?wallet=A+B%2BC", url)
    }
}
