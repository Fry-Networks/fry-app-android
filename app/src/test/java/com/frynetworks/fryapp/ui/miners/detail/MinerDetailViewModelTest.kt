package com.frynetworks.fryapp.ui.miners.detail

import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import com.frynetworks.fryapp.data.dashboard.model.DeviceStakeInfo
import com.frynetworks.fryapp.data.dashboard.model.HardwareStatusEntry
import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.ProductReward
import com.frynetworks.fryapp.data.dashboard.model.ProductStake
import com.frynetworks.fryapp.data.dashboard.model.RewardSummary
import com.frynetworks.fryapp.data.dashboard.model.WithdrawableResponse
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.FakeRewardsRepository
import com.frynetworks.fryapp.fakes.FakeStakeRepository
import com.frynetworks.fryapp.fakes.TEST_ADDRESS
import com.frynetworks.fryapp.fakes.TestSession
import com.frynetworks.fryapp.fakes.fixedClock
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.ui.common.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class MinerDetailViewModelTest {

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val nodeKey = "RDN-BCDEFGHIJKLMNOPQRSTUVWXYZ0123456"
    private val now = 1_757_592_000_000L
    private val miners = FakeMinerRepository()
    private val rewards = FakeRewardsRepository()
    private val stakes = FakeStakeRepository()
    private val product = Product(
        key = "FEM",
        displayName = "FEM",
        reward = ProductReward(stake = ProductStake(stakeOne = BigDecimal("5"), stakeTwo = BigDecimal("20"), register = BigDecimal("40"), node = BigDecimal("100"))),
    )

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun vm(minerKey: String = key, session: SessionRepository = TestSession.signedIn()) =
        MinerDetailViewModel(minerKey, miners, rewards, stakes, session, fixedClock(now))

    private fun detail(minerKey: String = key, registered: Boolean = false, block: DeviceDetail.() -> DeviceDetail = { this }) {
        miners.details.value = miners.details.value + (minerKey to DeviceDetail(minerKey = minerKey, nickname = "Backyard", isRegistered = registered).block())
        miners.products = mapOf(minerKey to product)
    }

    @Test
    fun `loads detail summary product and hardware for a hardware family`() = runTest {
        detail()
        rewards.summaries.value = mapOf(key to RewardSummary(claimable = BigDecimal("4.25")))
        miners.hardware = mapOf(key to HardwareStatusEntry(linked = true, valid = true, macMatch = true))
        val vm = vm()
        val s = vm.uiState.value
        assertEquals(Phase.Content, s.phase)
        assertEquals("Backyard", s.displayName)
        assertEquals(product, s.product)
        assertNotNull(s.hardware)
        assertEquals(1, miners.refreshDetailCalls)
        assertEquals(1, rewards.refreshSummaryCalls)
        assertTrue(s.actions.claim)
        assertTrue(s.actions.stakeRegistration)
        assertFalse(s.actions.showStakeNode)
    }

    @Test
    fun `claim is disabled without claimable rewards`() = runTest {
        detail()
        val vm = vm()
        assertFalse(vm.uiState.value.actions.claim)
        assertTrue(vm.uiState.value.actions.rename)
        assertTrue(vm.uiState.value.actions.rewardWallet)
    }

    @Test
    fun `signed out - every action is disabled and the phase is Error UNAUTHORIZED`() = runTest {
        detail()
        val vm = vm(session = TestSession.signedOut())
        assertEquals(MinerActionGating(), vm.uiState.value.actions)
        assertEquals(0, miners.refreshDetailCalls)
        assertEquals("UNAUTHORIZED", vm.uiState.value.error?.code)
    }

    @Test
    fun `detail fetch failure without cache is Error with copy`() = runTest {
        miners.refreshDetailError = DashboardException("NOT_WIRED", "not wired")
        val vm = vm()
        assertEquals(Phase.Error, vm.uiState.value.phase)
        assertEquals("NOT_WIRED", vm.uiState.value.error?.code)
    }

    @Test
    fun `node family gets a stake-node action and withdraw gating follows withdrawable plus lock`() = runTest {
        detail(nodeKey) { copy(registration = DeviceStakeInfo(amount = BigDecimal("40")), node = null) }
        stakes.withdrawableTable = mapOf("registration" to WithdrawableResponse(withdrawable = true, lockRemainingSeconds = 0))
        val vm = vm(nodeKey)
        val a = vm.uiState.value.actions
        assertTrue(a.showStakeNode)
        assertTrue(a.stakeNode)
        assertTrue(a.showWithdraw)
        assertTrue(a.withdrawRegistration)
        assertFalse(a.withdrawNode)
        assertEquals(1, stakes.withdrawableCalls)
    }

    @Test
    fun `a locked registration stake disables withdraw and exposes the unlock instant`() = runTest {
        detail { copy(registration = DeviceStakeInfo(amount = BigDecimal("40"))) }
        stakes.withdrawableTable = mapOf("registration" to WithdrawableResponse(withdrawable = true, lockRemainingSeconds = 120))
        val vm = vm()
        assertFalse(vm.uiState.value.actions.withdrawRegistration)
        // ServerClock is an offset over the wall clock, so the target drifts by the few ms the test takes.
        val target = vm.uiState.value.lockTargets.getValue("registration")
        assertTrue("unlock target $target should be ~120 s after $now", target - (now + 120_000L) in 0L..5_000L)
    }

    @Test
    fun `hardware status is skipped for virtual miners`() = runTest {
        detail { copy(virtual = true) }
        miners.hardware = mapOf(key to HardwareStatusEntry(linked = true))
        val vm = vm()
        assertNull(vm.uiState.value.hardware)
        assertTrue(vm.uiState.value.isVirtual)
    }

    @Test
    fun `rename rejects a blank name and records a valid one then refreshes`() = runTest {
        detail()
        val vm = vm()
        vm.openDialog(DetailDialog.Rename)
        vm.rename("   ")
        assertEquals("INVALID_NAME", vm.uiState.value.actionError?.code)
        assertTrue(miners.renames.isEmpty())
        vm.rename("Roof FEM")
        assertEquals(listOf(key to "Roof FEM"), miners.renames)
        assertNull(vm.uiState.value.dialog)
        assertEquals("Roof FEM", vm.uiState.value.displayName)
        assertEquals("Miner renamed.", vm.uiState.value.actionMessage)
    }

    @Test
    fun `reward wallet is gated by AlgorandAddress validation`() = runTest {
        detail()
        val vm = vm()
        vm.setRewardWallet("not-an-address")
        assertEquals("INVALID_ADDRESS", vm.uiState.value.actionError?.code)
        assertTrue(miners.rewardWallets.isEmpty())
        vm.setRewardWallet(TEST_ADDRESS)
        assertEquals(listOf(key to TEST_ADDRESS), miners.rewardWallets)
        assertEquals("Reward wallet updated.", vm.uiState.value.actionMessage)
    }

    @Test
    fun `withdraw calls the stake repository with the session address and refreshes`() = runTest {
        detail { copy(registration = DeviceStakeInfo(amount = BigDecimal("40"))) }
        stakes.withdrawableTable = mapOf("registration" to WithdrawableResponse(withdrawable = true))
        val vm = vm()
        vm.openDialog(DetailDialog.Withdraw(StakeContext.Registration))
        vm.withdraw(StakeContext.Registration)
        assertEquals(listOf(Triple<StakeContext, String, String>(StakeContext.Registration, TEST_ADDRESS, key)), stakes.withdrawCalls)
        assertNull(vm.uiState.value.dialog)
        assertTrue(vm.uiState.value.actionMessage!!.startsWith("Withdrawal submitted"))
    }

    @Test
    fun `action failures surface as actionError and keep the dialog open`() = runTest {
        detail()
        miners.renameError = DashboardException("RATE_LIMIT_EXCEEDED", "slow down")
        val vm = vm()
        vm.openDialog(DetailDialog.Rename)
        vm.rename("New name")
        assertEquals("RATE_LIMIT_EXCEEDED", vm.uiState.value.actionError?.code)
        assertEquals(DetailDialog.Rename, vm.uiState.value.dialog)
        assertFalse(vm.uiState.value.busy)
    }

    @Test
    fun `sheets open only when signed in and remember the BYOD flag`() = runTest {
        detail { copy(byod = "BYOD-LICENSE-0042") }
        val vm = vm()
        vm.openClaim()
        assertEquals(DetailSheet.Claim(null), vm.uiState.value.sheet)
        vm.closeSheet()
        vm.openStake(StakeContext.Registration)
        assertEquals(DetailSheet.Stake(StakeContext.Registration, byod = true), vm.uiState.value.sheet)

        val signedOut = vm(session = TestSession.signedOut())
        signedOut.openClaim()
        assertNull(signedOut.uiState.value.sheet)
    }
}
