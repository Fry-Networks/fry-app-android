package com.frynetworks.fryapp.ui.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R3 follow-up (IV5 T2 F2): while the board's own key is being checked the screen holds, so it
 * says so; otherwise a slow network looks like a hang ("Connected" and nothing else).
 */
class ProvisionBoardKeyCheckingTest {

    @Test
    fun `the checking line has its words`() {
        assertEquals("Checking this board's key with the dashboard…", ProvisionViewModel.BOARD_KEY_CHECKING)
    }

    @Test
    fun `the provision screen shows the checking line while the board key check runs`() {
        val src = File("src/main/java/com/frynetworks/fryapp/ui/provision/ProvisionScreen.kt")
        assertTrue("run from the app module: ${src.absolutePath}", src.isFile)
        val screen = src.readText()
        val checking = Regex("""if \(boardKeyCheck is KeyCheckUi\.Checking\) \{[^}]*ProvisionViewModel\.BOARD_KEY_CHECKING[^}]*"prov_board_key_checking"""")
        assertTrue("no checking line bound to KeyCheckUi.Checking", checking.containsMatchIn(screen))
    }
}
