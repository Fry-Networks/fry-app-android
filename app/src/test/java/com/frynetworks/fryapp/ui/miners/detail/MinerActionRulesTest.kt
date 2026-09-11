package com.frynetworks.fryapp.ui.miners.detail

import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import com.frynetworks.fryapp.data.dashboard.model.DeviceStakeInfo
import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.ProductReward
import com.frynetworks.fryapp.data.dashboard.model.ProductStake
import com.frynetworks.fryapp.data.dashboard.model.RewardSummary
import com.frynetworks.fryapp.data.dashboard.model.WithdrawableResponse
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.domain.StakeTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class MinerActionRulesTest {

    private val now = Instant.parse("2026-09-11T12:00:00Z").toEpochMilli()
    private val product = Product(
        key = "FEM",
        reward = ProductReward(stake = ProductStake(stakeOne = BigDecimal("5"), stakeTwo = BigDecimal("20"), register = BigDecimal("40"), node = BigDecimal("100"))),
    )
    private val unlocked = WithdrawableResponse(withdrawable = true, lockRemainingSeconds = 0)
    private val locked = WithdrawableResponse(withdrawable = true, lockRemainingSeconds = 3600)

    private fun compute(
        signedIn: Boolean = true,
        detail: DeviceDetail? = DeviceDetail(minerKey = "FEM-X", isRegistered = false),
        claimable: String = "0",
        family: MinerFamily = MinerFamily.FEM,
        isNodeFlag: Boolean = false,
        product: Product? = this.product,
        withdrawable: Map<String, WithdrawableResponse> = emptyMap(),
    ) = MinerActionRules.compute(signedIn, detail, RewardSummary(claimable = BigDecimal(claimable)), family, isNodeFlag, product, withdrawable, now)

    @Test
    fun `signed out disables everything`() {
        val gating = compute(signedIn = false, claimable = "10")
        assertEquals(MinerActionGating(), gating)
    }

    @Test
    fun `claim is enabled only when claimable is positive`() {
        assertFalse(compute(claimable = "0").claim)
        assertTrue(compute(claimable = "0.000001").claim)
    }

    @Test
    fun `stake registration only when unregistered and the product has a registration amount`() {
        assertTrue(compute().stakeRegistration)
        assertFalse(compute(detail = DeviceDetail(isRegistered = true)).stakeRegistration)
        assertFalse(compute(product = null).stakeRegistration)
        assertFalse(compute(product = Product(reward = ProductReward(stake = ProductStake()))).stakeRegistration)
    }

    @Test
    fun `stake node shows for node families or the remote node flag, enabled until a node stake exists`() {
        val fem = compute()
        assertFalse(fem.showStakeNode)
        val rdn = compute(family = MinerFamily.RDN)
        assertTrue(rdn.showStakeNode)
        assertTrue(rdn.stakeNode)
        val flagged = compute(isNodeFlag = true)
        assertTrue(flagged.showStakeNode)
        val staked = compute(family = MinerFamily.RDN, detail = DeviceDetail(node = DeviceStakeInfo(amount = BigDecimal("100"))))
        assertTrue(staked.showStakeNode)
        assertFalse(staked.stakeNode)
    }

    @Test
    fun `verification stake is offered until a verification stake exists`() {
        assertTrue(compute().stakeVerification)
        assertFalse(compute(detail = DeviceDetail(staked = DeviceStakeInfo(amount = BigDecimal("5")))).stakeVerification)
    }

    @Test
    fun `withdraw registration needs an existing stake, a withdrawable answer and no lock`() {
        val detail = DeviceDetail(registration = DeviceStakeInfo(amount = BigDecimal("40")))
        assertFalse(compute(detail = detail).withdrawRegistration)
        assertFalse(compute(detail = detail, withdrawable = mapOf("registration" to locked)).withdrawRegistration)
        val ok = compute(detail = detail, withdrawable = mapOf("registration" to unlocked))
        assertTrue(ok.withdrawRegistration)
        assertTrue(ok.showWithdraw)
        assertTrue(ok.anyWithdraw)
        assertFalse(compute().showWithdraw)
    }

    @Test
    fun `withdraw verification honours the tier lock from staked time and type`() {
        val recentTwo = DeviceDetail(staked = DeviceStakeInfo(amount = BigDecimal("20"), type = "two", time = "2026-09-01T00:00:00.000Z"))
        assertFalse(compute(detail = recentTwo, withdrawable = mapOf("verification" to unlocked)).withdrawVerification)
        val oldOne = DeviceDetail(staked = DeviceStakeInfo(amount = BigDecimal("5"), type = "one", time = "2026-09-01T00:00:00.000Z"))
        assertTrue(compute(detail = oldOne, withdrawable = mapOf("verification" to unlocked)).withdrawVerification)
        assertFalse(compute(detail = oldOne, withdrawable = emptyMap()).withdrawVerification)
    }

    @Test
    fun `lockTargets reports future unlock instants only`() {
        val detail = DeviceDetail(
            registration = DeviceStakeInfo(amount = BigDecimal("40")),
            staked = DeviceStakeInfo(amount = BigDecimal("20"), type = "two", time = "2026-09-01T00:00:00.000Z"),
        )
        val targets = MinerActionRules.lockTargets(detail, mapOf("registration" to locked, "node" to unlocked), now)
        assertEquals(now + 3_600_000L, targets["registration"])
        assertFalse(targets.containsKey("node"))
        val expectedVerification = Instant.parse("2026-09-01T00:00:00Z").plusSeconds(180L * 86_400).toEpochMilli()
        assertEquals(expectedVerification, targets["verification"])
    }

    @Test
    fun `existingStakes lists the contexts that have an amount`() {
        val detail = DeviceDetail(
            registration = DeviceStakeInfo(amount = BigDecimal("40")),
            staked = DeviceStakeInfo(amount = BigDecimal("20"), type = "two"),
        )
        assertEquals(listOf(StakeContext.Registration, StakeContext.Verification(StakeTier.TWO)), MinerActionRules.existingStakes(detail))
        assertTrue(MinerActionRules.existingStakes(null).isEmpty())
    }
}
