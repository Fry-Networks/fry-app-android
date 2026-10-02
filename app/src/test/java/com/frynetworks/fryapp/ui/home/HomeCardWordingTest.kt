package com.frynetworks.fryapp.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AP6 (OD-44): the Home card's phone-local chip says when this phone set the board up, not when
 * it was "seen" (which read as live status next to AP5's dashboard status).
 */
class HomeCardWordingTest {

    private fun literals(text: String) =
        Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"").findAll(text).map { it.groupValues[1] }.toList()

    private fun seenWording(lits: List<String>) = lits.filter { it.startsWith("Paired · seen") }

    private fun homeLiterals(): List<String> {
        val src = File("src/main/java/com/frynetworks/fryapp/ui/home/HomeScreen.kt")
        assertTrue("run from the app module: ${src.absolutePath}", src.isFile)
        return literals(src.readText())
    }

    @Test
    fun `the matcher catches the old chip wording (positive control)`() {
        assertEquals(listOf("Paired · seen "), seenWording(literals("Text(\"Paired · seen \" + x)")))
        assertTrue(seenWording(literals("Text(\"Paired · set up \" + x)")).isEmpty())
    }

    @Test
    fun `the Home card chip says set up, not seen`() {
        val lits = homeLiterals()
        assertTrue("scanner sees HomeScreen literals", "Claim" in lits)
        assertEquals(emptyList<String>(), seenWording(lits))
        assertTrue("no 'Paired · set up ' chip in HomeScreen.kt", "Paired · set up " in lits)
    }
}
