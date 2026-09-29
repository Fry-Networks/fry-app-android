package com.frynetworks.fryapp.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** The exact guidance of the two cleanup branches, so dropping either one is noticed. */
class MinerKeyFormatReasonTest {

    private val body = "0123456789ABCDEF".repeat(2)

    @Test
    fun `a lowercase fem- prefix is told to use capitals`() {
        val expected = MinerKeyInput.Invalid("Miner keys start with FEM- in capital letters.")
        assertEquals(expected, MinerKeyFormat.parse("fem-$body"))
        assertEquals(expected, MinerKeyFormat.parse("Fem-$body"))
    }

    @Test
    fun `a key broken by whitespace is told to paste it in one piece`() {
        val expected = MinerKeyInput.Invalid("A miner key has no spaces. Paste it again in one piece.")
        assertEquals(expected, MinerKeyFormat.parse("FEM-" + body.take(16) + " " + body.drop(16)))
        assertEquals(expected, MinerKeyFormat.parse("FEM-" + body.take(16) + "\t" + body.drop(16)))
    }
}
