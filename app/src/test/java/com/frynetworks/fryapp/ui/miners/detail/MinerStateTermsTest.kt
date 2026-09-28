package com.frynetworks.fryapp.ui.miners.detail

import com.frynetworks.fryapp.data.dashboard.model.DashboardGson
import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import com.frynetworks.fryapp.data.dashboard.model.RewardGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MinerStateTermsTest {

    @Test
    fun `active comes only from the dashboard's is_active`() {
        assertEquals("Active", MinerStateTerms.rows(DeviceDetail(isActive = true), null, null).active)
        assertTrue(MinerStateTerms.rows(DeviceDetail(isActive = false), null, null).active.startsWith("Inactive"))
        assertEquals("Not reported by the dashboard", MinerStateTerms.rows(DeviceDetail(isActive = null), null, null).active)
        assertEquals("Not reported by the dashboard", MinerStateTerms.rows(null, true, true).active)
    }

    @Test
    fun `registered is registration, never activity`() {
        assertEquals("Registered", MinerStateTerms.rows(DeviceDetail(isRegistered = true, isActive = false), null, null).registered)
        assertEquals("Not registered", MinerStateTerms.rows(DeviceDetail(isRegistered = false), null, null).registered)
        assertEquals("Registered", MinerStateTerms.rows(null, registeredFallback = true, verifiedFallback = null).registered)
    }

    @Test
    fun `not earning names the gate in words, the dashboard's words first`() {
        val u13 = DeviceDetail(rewardEligible = false, rewardBlockReason = "platform_not_enabled")
        assertEquals("Not earning: rewards are not enabled for this board type yet", MinerStateTerms.earning(u13))
        val worded = u13.copy(rewardBlockMessage = "ESP boards do not earn yet")
        assertEquals("Not earning: ESP boards do not earn yet", MinerStateTerms.earning(worded))
        assertEquals("Not earning: the dashboard gives no reason", MinerStateTerms.earning(DeviceDetail(rewardEligible = false)))
        assertEquals("Earning", MinerStateTerms.earning(DeviceDetail(rewardEligible = true)))
        assertEquals("Not earning: something_new", MinerStateTerms.earning(DeviceDetail(rewardEligible = false, rewardBlockReason = "something_new")))
    }

    @Test
    fun `every C-4 reason code has words`() {
        val codes = listOf("update_required", "no_recent_heartbeat", "no_poc_data", "platform_not_enabled", "no_software_report",
            "no_slot_proofs", "integration_required", "not_registered", "no_reward_wallet", "ineligible")
        for (code in codes) assertTrue(code, MinerStateTerms.reasonWords(code) != code)
        assertEquals(codes.size, codes.map { MinerStateTerms.reasonWords(it) }.toSet().size)
    }

    @Test
    fun `verification is the stake badge`() {
        assertEquals("Verified (verification stake held)", MinerStateTerms.rows(DeviceDetail(verified = true), null, null).verification)
        assertEquals("Unverified (no verification stake)", MinerStateTerms.rows(DeviceDetail(verified = false), null, null).verification)
    }

    @Test
    fun `C-4 fields parse from the device JSON and old payloads still parse`() {
        val json = """{"miner_key":"FEM-TESTKEY0000000000000000000000001","is_active":false,"reward_eligible":false,
            "reward_block_reason":"no_recent_heartbeat","reward_block_message":"No heartbeat in the last 24 hours",
            "reward_block_gates":[{"gate":"heartbeat","met":false,"reason":"last seen 2 days ago"},{"gate":"wallet","met":true}],
            "registered_to":"you"}"""
        val d = DashboardGson.instance.fromJson(json, DeviceDetail::class.java)
        assertEquals("No heartbeat in the last 24 hours", d.rewardBlockMessage)
        assertEquals(listOf(RewardGate("heartbeat", false, "last seen 2 days ago"), RewardGate("wallet", true, null)), d.rewardBlockGates)
        assertEquals("you", d.registeredTo)
        val old = DashboardGson.instance.fromJson("""{"miner_key":"FEM-TESTKEY0000000000000000000000001"}""", DeviceDetail::class.java)
        assertEquals(emptyList<RewardGate>(), old.rewardBlockGates)
        assertEquals(null, old.registeredTo)
    }
}
