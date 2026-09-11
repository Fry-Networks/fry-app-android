package com.frynetworks.fryapp.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real Android runtime (emulator or device), not the desktop JVM. The desktop JDK ships
 * a SHA-512/256 MessageDigest provider; Android does not (Conscrypt and Android's BouncyCastle
 * both omit it), so a JVM-only unit test cannot prove that address validation works on a phone.
 */
@RunWith(AndroidJUnit4::class)
class AlgorandAddressInstrumentedTest {

    private val validMainnetAddress = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"

    @Test
    fun validMainnetAddressValidatesOnAndroidRuntime() {
        assertTrue(AlgorandAddress.isValid(validMainnetAddress))
    }

    @Test
    fun mutatedChecksumIsRejectedOnDevice() {
        val last = validMainnetAddress.last()
        val replacement = if (last == 'A') 'B' else 'A'
        val mutated = validMainnetAddress.dropLast(1) + replacement
        assertFalse(AlgorandAddress.isValid(mutated))
    }
}
