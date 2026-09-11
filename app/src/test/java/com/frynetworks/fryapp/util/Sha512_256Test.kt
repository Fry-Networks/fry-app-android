package com.frynetworks.fryapp.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

class Sha512_256Test {

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `empty input matches the FIPS 180-4 SHA-512-256 vector`() {
        assertEquals(
            "c672b8d1ef56ed28ab87c3622c5114069bdd3ad7b8f9737498d0c01ecef0967a",
            hex(Sha512_256.digest(ByteArray(0))),
        )
    }

    @Test
    fun `abc matches the FIPS 180-4 SHA-512-256 vector`() {
        assertEquals(
            "53048e2681941ef99b2e29b76b4c7dabe4c2d0c634fc6d46e0e2f13107e7af23",
            hex(Sha512_256.digest("abc".toByteArray(Charsets.US_ASCII))),
        )
    }

    @Test
    fun `two-block input matches the FIPS 180-4 SHA-512-256 vector`() {
        val input = "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"
        assertEquals(
            "3928e184fb8690f840da3988121d31be65cb9d3ef83ee6146feac861e19b563a",
            hex(Sha512_256.digest(input.toByteArray(Charsets.US_ASCII))),
        )
    }

    @Test
    fun `matches the JDK provider across padding boundaries and random lengths`() {
        val jdk = MessageDigest.getInstance("SHA-512/256")
        val random = Random(7)
        val lengths = (0..300).toList() + listOf(111, 112, 113, 127, 128, 129, 239, 240, 241, 255, 256, 257, 1024, 4096)
        for (len in lengths) {
            val input = ByteArray(len).also { random.nextBytes(it) }
            assertArrayEquals("length $len", jdk.digest(input), Sha512_256.digest(input))
        }
    }
}
