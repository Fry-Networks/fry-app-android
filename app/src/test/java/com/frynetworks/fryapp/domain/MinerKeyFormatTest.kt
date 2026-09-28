package com.frynetworks.fryapp.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinerKeyFormatTest {

    private val base36 = "FEM-0Z9Y8X7W6V5U4T3S2R1Q0P9O8N7M6L5K"
    private val hexUpper = "FEM-" + "0123456789ABCDEF".repeat(2)
    private val hexLower = "FEM-" + "0123456789abcdef".repeat(2)

    @Test
    fun `dashboard, FEM PC and migrated keys are all accepted byte-exact`() {
        for (key in listOf(base36, hexUpper, hexLower)) {
            assertEquals(MinerKeyInput.Valid(key), MinerKeyFormat.parse(key))
        }
    }

    @Test
    fun `case is never changed`() {
        assertEquals(hexLower, (MinerKeyFormat.parse(hexLower) as MinerKeyInput.Valid).key)
        assertTrue(MinerKeyFormat.parse("fem-" + "0123456789ABCDEF".repeat(2)) is MinerKeyInput.Invalid)
    }

    @Test
    fun `surrounding whitespace and zero-width characters are removed`() {
        assertEquals(MinerKeyInput.Valid(base36), MinerKeyFormat.parse("  $base36\n"))
        assertEquals(MinerKeyInput.Valid(base36), MinerKeyFormat.parse("​" + base36 + "﻿"))
        assertEquals(MinerKeyInput.Valid(base36), MinerKeyFormat.parse(base36.substring(0, 10) + "‍" + base36.substring(10)))
    }

    @Test
    fun `an IOT- key is refused with its FEM- form offered`() {
        val legacy = MinerKeyFormat.parse("IOT-" + "0123456789ABCDEF".repeat(2))
        assertEquals(MinerKeyInput.LegacyIot(hexUpper), legacy)
        assertEquals("IOT- keys are now FEM- keys: use FEM- with the same 32 characters", MinerKeyFormat.LEGACY_GUIDANCE)
    }

    @Test
    fun `wrong shapes are invalid with a reason`() {
        assertEquals(MinerKeyInput.Empty, MinerKeyFormat.parse("  "))
        for (bad in listOf("FEM-ABC", "FEM-" + "A".repeat(33), "FEM-" + "A".repeat(31) + "-", "FEM-" + "A".repeat(16) + " " + "A".repeat(16), "hello", "FEM_" + "A".repeat(32))) {
            val r = MinerKeyFormat.parse(bad)
            assertTrue(bad, r is MinerKeyInput.Invalid && r.reason.endsWith("."))
        }
        assertTrue((MinerKeyFormat.parse("FEM-ABC") as MinerKeyInput.Invalid).reason.contains("this one has 3"))
    }

    @Test
    fun `masking shows six characters and an ellipsis, and masked values are recognised`() {
        assertEquals("FEM-0Z…", MinerKeyFormat.mask(base36))
        assertTrue(MinerKeyFormat.isMasked("FEM-AB…"))
        assertFalse(MinerKeyFormat.isMasked(base36))
        assertFalse(MinerKeyFormat.isMasked(null))
    }
}
