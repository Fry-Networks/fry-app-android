package com.frynetworks.fryapp.ui.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AP5 no-contradiction: the device screen shows exactly one live status, and its words come only
 * from LiveStatus.kt (which takes them from MinerStateTerms), never from literals in the screen.
 */
class DeviceScreenLiveStatusSourceTest {

    private val liveWords = listOf("Active", "Inactive", "Not reported", "Status unavailable", "Sign in to see live status", "Live status")

    private fun source(name: String): String {
        val src = File("src/main/java/com/frynetworks/fryapp/ui/device/$name")
        assertTrue("run from the app module: ${src.absolutePath}", src.isFile)
        return src.readText()
    }

    private fun literals(text: String) =
        Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"").findAll(text).map { it.groupValues[1] }.toList()

    private fun liveLiterals(text: String) = literals(text).filter { lit -> liveWords.any { lit.contains(it) } }

    @Test
    fun `the matcher finds live words in a literal (positive control)`() {
        assertEquals(listOf("Live status: Active"), liveLiterals("Text(\"Live status: Active\")"))
        assertTrue(liveLiterals("Text(\"Joined Wi-Fi\")").isEmpty())
    }

    @Test
    fun `the device screen has no live-status literals of its own`() {
        val screen = source("DeviceDetailScreen.kt")
        assertTrue("scanner sees the screen's literals", "Check for update" in literals(screen))
        assertEquals(emptyList<String>(), liveLiterals(screen))
    }

    @Test
    fun `the device screen renders the live status from exactly one source`() {
        val screen = source("DeviceDetailScreen.kt")
        assertEquals("one status render site", 1, Regex("LiveStatusText\\.status\\(").findAll(screen).count())
        assertEquals("one checked-time render site", 1, Regex("LiveStatusText\\.checked\\(").findAll(screen).count())
        assertEquals("one live-status collector", 1, Regex("viewModel\\.liveStatus\\.collectAsStateWithLifecycle\\(").findAll(screen).count())
        assertTrue("status node is tagged", screen.contains("\"device_live_status\""))
        assertTrue("label comes from LiveStatusText", screen.contains("LiveStatusText.LABEL"))
    }
}
