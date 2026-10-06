package com.frynetworks.fryapp.ui.miners.detail

import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IV6 A1 (S1): every server `reward_block_reason` code reads as clear text, including the new
 * `lease_held_elsewhere` (contract X2). The bare code, or "ineligible" standing in for a known
 * reason, is only ever the fallback for a code the app does not know.
 */
class MinerStateTermsLeaseTest {

    private val known = listOf(
        "update_required", "no_recent_heartbeat", "no_poc_data", "platform_not_enabled", "no_software_report",
        "no_slot_proofs", "integration_required", "not_registered", "no_reward_wallet", "ineligible", "lease_held_elsewhere",
    )

    @Test
    fun `lease_held_elsewhere says the key is active on another install and where to find the board's key`() {
        val words = MinerStateTerms.reasonWords("lease_held_elsewhere")
        assertEquals("this key is active on another install. Each board has its own key: register the key this board shows.", words)
        assertEquals(words, MinerStateTerms.reasonWords("lease_held_elsewhere", "esp32s3"))
        val d = DeviceDetail(rewardEligible = false, rewardBlockReason = "lease_held_elsewhere")
        assertEquals("Not earning: $words", MinerStateTerms.earning(d))
    }

    @Test
    fun `every known code reads as words on PC and on every ESP chip`() {
        for (code in known) for (platform in listOf(null, "windows", "esp32", "esp32c3", "esp32s3", "esp8266")) {
            val words = MinerStateTerms.reasonWords(code, platform)
            assertNotEquals("$code/$platform is still the bare code", code, words)
            assertTrue("$code/$platform is too short: $words", words.length > 20)
            assertFalse("$code/$platform has an em dash", words.contains('—'))
            if (code != "ineligible") assertFalse("$code/$platform falls back to the ineligible text", words == MinerStateTerms.reasonWords("ineligible"))
        }
    }

    @Test
    fun `only an unknown code is shown as sent`() {
        assertEquals("Not earning: brand_new_code", MinerStateTerms.earning(DeviceDetail(rewardEligible = false, rewardBlockReason = "brand_new_code")))
    }
}
