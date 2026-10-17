package com.frynetworks.fryapp.domain

import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.ProductReward
import com.frynetworks.fryapp.data.dashboard.model.ProductStake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** A 0 USD registration/node amount must fail with NO_STAKE_AMOUNT, never reach Ready with 0 tokens. */
class StakeFlowZeroAmountTest {

    private val price = BigDecimal("0.05")

    private fun start(context: StakeContext, stake: ProductStake, byod: Boolean = false): StakeState {
        val product = Product(key = "FEM", reward = ProductReward(stake = stake))
        return reduce(StakeState.Loading(product, price, context, byod = byod, asset = FryAsset.FNODE), StakeEvent.Start)
    }

    private fun assertNoAmount(next: StakeState) {
        assertTrue(next.toString(), next is StakeState.Failed)
        next as StakeState.Failed
        assertEquals("NO_STAKE_AMOUNT", next.code)
        assertFalse(next.recoverable)
    }

    @Test fun `zero registration amount fails with no stake amount`() {
        assertNoAmount(start(StakeContext.Registration, ProductStake(register = BigDecimal.ZERO)))
        assertNoAmount(start(StakeContext.Registration, ProductStake(register = BigDecimal.ZERO), byod = true))
    }

    @Test fun `zero node amount fails with no stake amount`() =
        assertNoAmount(start(StakeContext.Node, ProductStake(node = BigDecimal.ZERO)))

    @Test fun `positive registration amount is still Ready`() {
        val next = start(StakeContext.Registration, ProductStake(register = BigDecimal("25")))
        assertEquals(StakeState.Ready(BigDecimal("500"), FryAsset.FNODE, BigDecimal("25")), next)
    }
}
