package com.frynetworks.fryapp.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlgorandAddressTest {

    private val validMainnetAddress = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"

    @Test
    fun `valid mainnet address passes`() {
        assertTrue(AlgorandAddress.isValid(validMainnetAddress))
    }

    @Test
    fun `wrong length fails`() {
        assertFalse(AlgorandAddress.isValid(validMainnetAddress.dropLast(1)))
        assertFalse(AlgorandAddress.isValid(validMainnetAddress + "A"))
    }

    @Test
    fun `mutated last character fails`() {
        val lastChar = validMainnetAddress.last()
        val replacement = if (lastChar == 'A') 'B' else 'A'
        val mutated = validMainnetAddress.dropLast(1) + replacement
        assertFalse(AlgorandAddress.isValid(mutated))
    }

    @Test
    fun `lowercase fails`() {
        assertFalse(AlgorandAddress.isValid(validMainnetAddress.lowercase()))
    }

    @Test
    fun `empty string fails`() {
        assertFalse(AlgorandAddress.isValid(""))
    }
}
