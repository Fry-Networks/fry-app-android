package com.frynetworks.fryapp.ui.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * I9 (AP4): the device screen shows only what this phone recorded at setup (the board's last
 * provisioning state and when this phone set it up). It must not word that as live status
 * ("VPN state: Connected", "Last seen: just now"); live activity is the dashboard's, shown in Miners.
 */
class DeviceScreenCopyTest {

    private val retired = listOf("VPN state: ", "Last seen: ", "Connected", "Connecting")

    private fun literals(): List<String> {
        val src = File("src/main/java/com/frynetworks/fryapp/ui/device/DeviceDetailScreen.kt")
        assertTrue("run from the app module: ${src.absolutePath}", src.isFile)
        return Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"").findAll(src.readText()).map { it.groupValues[1] }.toList()
    }

    private fun liveWording(s: String) = retired.filter { s == it || s.startsWith(it) }

    @Test
    fun `the matcher catches the old live-status wording (positive control)`() {
        assertEquals(listOf("VPN state: "), liveWording("VPN state: "))
        assertEquals(listOf("Last seen: "), liveWording("Last seen: "))
        assertEquals(listOf("Connected"), liveWording("Connected"))
        assertTrue(liveWording("Joined Wi-Fi").isEmpty())
    }

    @Test
    fun `the device screen words its values as setup facts, not live status`() {
        val lits = literals()
        assertTrue("scanner sees the screen's literals", "Check for update" in lits)
        assertEquals("live-status wording left: ${lits.filter { liveWording(it).isNotEmpty() }}", emptyList<String>(), lits.filter { liveWording(it).isNotEmpty() })
        assertTrue("Last setup result: " in lits)
        assertTrue("Last set up from this phone: " in lits)
        assertTrue("Joined Wi-Fi" in lits)
    }
}
