package com.frynetworks.fryapp.ui.miners.detail

import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.ProductReward
import com.frynetworks.fryapp.data.dashboard.model.ProductStake
import com.frynetworks.fryapp.data.dashboard.model.RewardSummary
import com.frynetworks.fryapp.domain.MinerFamily
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Node staking is only offered when the product's node amount is positive (a 0 amount is not a stake). */
class MinerActionNodeGateTest {

    private fun stakeNode(node: String?) = MinerActionRules.compute(
        true,
        DeviceDetail(minerKey = "RDN-X", isRegistered = true),
        RewardSummary(claimable = BigDecimal.ZERO),
        MinerFamily.RDN,
        false,
        Product(key = "RDN", reward = ProductReward(stake = ProductStake(node = node?.let { BigDecimal(it) }))),
        emptyMap(),
        0L,
    ).stakeNode

    @Test fun `positive node amount enables node staking`() = assertTrue(stakeNode("50"))

    @Test fun `zero node amount disables node staking`() {
        assertFalse(stakeNode("0"))
        assertFalse(stakeNode("0.00"))
    }

    @Test fun `null node amount disables node staking`() = assertFalse(stakeNode(null))
}
