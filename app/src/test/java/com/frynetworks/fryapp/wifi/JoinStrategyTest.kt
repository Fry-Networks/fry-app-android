package com.frynetworks.fryapp.wifi

import org.junit.Assert.assertEquals
import org.junit.Test

class JoinStrategyTest {

    private val ap = "FRY-SETUP-ABC123"

    @Test
    fun `below Android 10 there is no join, only the USB guidance`() {
        assertEquals(JoinPlan.NeedsAndroid10, JoinStrategy.decide(28, ap, "ABCD2345"))
        assertEquals(JoinPlan.NeedsAndroid10, JoinStrategy.decide(26, ap, null))
        assertEquals(29, JoinStrategy.MIN_SDK)
    }

    @Test
    fun `a keyless v1-1 board's WPA2 setup AP is joined with its setup code`() {
        assertEquals(JoinPlan.Specifier(ap, "ABCD2345"), JoinStrategy.decide(29, ap, "ABCD2345"))
        assertEquals(JoinPlan.Specifier(ap, "ABCD2345"), JoinStrategy.decide(36, ap, "ABCD2345"))
    }

    @Test
    fun `a keyed board's open AP is joined without a passphrase, and a blank code is no passphrase`() {
        assertEquals(JoinPlan.Specifier(ap, null), JoinStrategy.decide(29, ap, null))
        assertEquals(JoinPlan.Specifier(ap, null), JoinStrategy.decide(29, ap, "  "))
    }
}
