package com.frynetworks.fryapp.ui.miners.detail

import com.frynetworks.fryapp.data.dashboard.model.DashboardGson
import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R2 (IV5): when a dashboard sends a `reward_block_reason` code without its message, the app's
 * words for that code are the dashboard's canonical words (RUN/audit/r2-strings.json
 * iv5-r2-draft-1), picked by the same platform variant rule, so the same state reads the same.
 * `platform_not_enabled` keeps the wording MinerStateTermsTest pins.
 */
class MinerStateTermsFallbackTest {

    private val pc = mapOf(
        "not_registered" to "this device is not registered to a wallet yet. Register it to start earning.",
        "no_reward_wallet" to "no reward wallet is set for this device. Set one to start earning.",
        "no_poc_data" to "the server has no proof-of-connectivity report from this device yet. Power it on and connect it to the internet.",
        "update_required" to "this device reports an older PoC version but a newer version is required. Update its client or firmware.",
        "no_recent_heartbeat" to "no proof-of-connectivity heartbeat reached the server in the last 24 hours.",
        "no_software_report" to "the device does not report its software version, so the server cannot confirm it runs a supported client. Update its firmware or client.",
        "platform_not_enabled" to "rewards are not enabled for this board type yet",
        "no_slot_proofs" to "no valid 10-minute proof slots were recorded on the last scored day.",
        "integration_required" to "Fry Edge Miner rewards need an active integration (FryVPN or Olostep, or a partner integration), and none is active on this key.",
        "ineligible" to "the server marked this device as not reward-eligible.",
    )

    @Test
    fun `codes without a platform use the dashboard's PC words`() {
        for ((code, words) in pc) {
            assertEquals(code, words, MinerStateTerms.reasonWords(code))
            assertEquals(code, words, MinerStateTerms.reasonWords(code, "windows"))
        }
    }

    @Test
    fun `ESP boards get the dashboard's ESP variant`() {
        assertEquals("the server has not recorded this board's software version yet.", MinerStateTerms.reasonWords("no_software_report", "esp32"))
        assertEquals(
            "ESP8266 rewards are paid per valid 10-minute slot proof, and no valid slot proofs were recorded for this board on the last scored day.",
            MinerStateTerms.reasonWords("no_slot_proofs", "esp8266"),
        )
        assertEquals(
            "ESP32-C3 rewards need the board's secure link to the Fry server, and it has not connected yet.",
            MinerStateTerms.reasonWords("integration_required", "esp32c3"),
        )
        assertEquals("this board reports an older PoC version but a newer version is required.", MinerStateTerms.reasonWords("update_required", "esp32s3"))
        assertEquals(pc["no_recent_heartbeat"], MinerStateTerms.reasonWords("no_recent_heartbeat", "esp32"))
        assertEquals(pc["no_reward_wallet"], MinerStateTerms.reasonWords("no_reward_wallet", "esp32"))
    }

    @Test
    fun `the code-only fallback reads device_platform from the dashboard's device`() {
        val json = """{"miner_key":"FEM-TESTKEY0000000000000000000000001","reward_eligible":false,
            "reward_block_reason":"no_software_report","device_platform":"esp32s3"}"""
        val d = DashboardGson.instance.fromJson(json, DeviceDetail::class.java)
        assertEquals("Not earning: the server has not recorded this board's software version yet.", MinerStateTerms.earning(d))
    }
}
