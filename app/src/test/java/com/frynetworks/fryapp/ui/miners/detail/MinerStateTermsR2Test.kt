package com.frynetworks.fryapp.ui.miners.detail

import com.frynetworks.fryapp.data.dashboard.model.DashboardGson
import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R2 (IV5): the app shows the dashboard's own words for the same state. The dashboard's
 * `reward_block_message` already starts with "Not earning: " (dashb lib/rewardBlockReason.ts), and
 * 0.4.2 prefixed it again ("Not earning: Not earning: …"). `reward_fix_text` is shown verbatim.
 */
class MinerStateTermsR2Test {

    private val dashboardMessage = "Not earning: no reward wallet is set for this device. Set one to start earning."

    @Test
    fun `the dashboard's message is shown verbatim, never with a second prefix`() {
        val detail = DeviceDetail(rewardEligible = false, rewardBlockReason = "no_reward_wallet", rewardBlockMessage = dashboardMessage)
        assertEquals(dashboardMessage, MinerStateTerms.earning(detail))
        val lower = detail.copy(rewardBlockMessage = "not earning: lower-case prefix from the server")
        assertEquals("not earning: lower-case prefix from the server", MinerStateTerms.earning(lower))
    }

    @Test
    fun `reward_fix_text parses and is shown verbatim`() {
        val json = """{"miner_key":"FEM-TESTKEY0000000000000000000000001","reward_eligible":false,
            "reward_block_reason":"no_reward_wallet","reward_block_message":"$dashboardMessage",
            "reward_fix_text":"Open the dashboard, choose this device and set a reward wallet."}"""
        val d = DashboardGson.instance.fromJson(json, DeviceDetail::class.java)
        assertEquals("Open the dashboard, choose this device and set a reward wallet.", d.rewardFixText)
        assertEquals(dashboardMessage, MinerStateTerms.earning(d))
        assertEquals("Open the dashboard, choose this device and set a reward wallet.", MinerStateTerms.fixText(d))
    }

    @Test
    fun `no fix text when earning, blank, or not sent`() {
        assertNull(MinerStateTerms.fixText(DeviceDetail(rewardEligible = true, rewardFixText = "stale")))
        assertNull(MinerStateTerms.fixText(DeviceDetail(rewardEligible = false, rewardFixText = "  ")))
        assertNull(MinerStateTerms.fixText(DeviceDetail(rewardEligible = false)))
        assertNull(MinerStateTerms.fixText(null))
    }

    @Test
    fun `the identity card renders the fix text under the reason`() {
        val src = File("src/main/java/com/frynetworks/fryapp/ui/miners/detail/MinerDetailSections.kt")
        assertTrue("run from the app module: ${src.absolutePath}", src.isFile)
        val text = src.readText()
        assertTrue(text.contains("MinerStateTerms.fixText("))
        assertTrue(text.contains("\"miner_reward_fix_text\""))
    }
}
