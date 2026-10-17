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

/** Registration is only offered when the product's register amount is positive (a 0 amount is not a stake). */
class MinerActionRegisterAmountTest {

    private fun registration(register: String?, registered: Boolean = false) = MinerActionRules.compute(
        true,
        DeviceDetail(minerKey = "FEM-X", isRegistered = registered),
        RewardSummary(claimable = BigDecimal.ZERO),
        MinerFamily.FEM,
        false,
        Product(key = "FEM", reward = ProductReward(stake = ProductStake(register = register?.let { BigDecimal(it) }))),
        emptyMap(),
        0L,
    ).stakeRegistration

    @Test fun `positive register amounts enable registration`() {
        assertTrue(registration("0.01"))
        assertTrue(registration("25"))
    }

    @Test fun `null register amount disables registration`() = assertFalse(registration(null))

    @Test fun `zero register amount disables registration`() {
        assertFalse(registration("0"))
        assertFalse(registration("0.00"))
    }

    @Test fun `already registered stays disabled`() = assertFalse(registration("25", registered = true))
}
