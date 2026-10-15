package com.frynetworks.fryapp.ui.miners.stake

import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.ProductReward
import com.frynetworks.fryapp.data.dashboard.model.ProductStake
import com.frynetworks.fryapp.data.dashboard.model.ProductTokens
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.domain.StakeState
import com.frynetworks.fryapp.fakes.FakeAlgodRepository
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.FakeRewardsRepository
import com.frynetworks.fryapp.fakes.FakeStakeRepository
import com.frynetworks.fryapp.fakes.FakeWalletBridge
import com.frynetworks.fryapp.fakes.TEST_ADDRESS
import com.frynetworks.fryapp.fakes.TestSession
import com.frynetworks.fryapp.fakes.fixedClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/** A sheet recreated mid-stake (rotation, theme change) must not cancel a stake that is already broadcasting. */
@OptIn(ExperimentalCoroutinesApi::class)
class StakeViewModelRestartGuardTest {

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val miners = FakeMinerRepository()
    private val rewards = FakeRewardsRepository()
    private val stakes = FakeStakeRepository()
    private val bridge = FakeWalletBridge()
    private val confirmed = CompletableDeferred<Unit>()
    private val backing = FakeAlgodRepository()

    /** Same balances as the fake, but the on-chain confirmation wait holds until the test releases it. */
    private val algod = object : AlgodRepository by backing {
        override suspend fun waitConfirmed(txId: String, rounds: Int) {
            backing.waited += txId
            confirmed.await()
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        bridge.reconnectAddress = TEST_ADDRESS
        miners.products = mapOf(
            key to Product(
                key = "FEM",
                reward = ProductReward(
                    stake = ProductStake(stakeOne = BigDecimal("5"), stakeTwo = BigDecimal("20"), register = BigDecimal("40"), node = BigDecimal("100")),
                    tokens = ProductTokens(stake = "tFRY", reward = "tFRY", register = "tFRY", node = "fNODE"),
                ),
            ),
        )
        rewards.priceTable = mapOf(FryAsset.TFRY.id to BigDecimal("0.5"), FryAsset.FNODE.id to BigDecimal("2"))
        backing.markOptedIn(FryAsset.TFRY.id, 200_000_000L)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = StakeViewModel(miners, rewards, stakes, algod, bridge, TestSession.signedIn(), fixedClock(1_757_592_000_000L), openUri = {})

    @Test
    fun `start while the transfer is broadcasting leaves the stake running and records it`() = runTest {
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()
        assertEquals(StakeState.Submitting("SUBMITTEDTX"), vm.uiState.value.state)

        vm.start(key, StakeContext.Registration, byod = false)
        assertEquals(StakeState.Submitting("SUBMITTEDTX"), vm.uiState.value.state)

        confirmed.complete(Unit)
        assertEquals(StakeState.Done("RECORDEDTX", waived = false), vm.uiState.value.state)
        assertEquals(1, stakes.submitCalls.size)
    }

    @Test
    fun `start while the amount is only on offer still restarts`() = runTest {
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        assertTrue(vm.uiState.value.canCancel)

        vm.start(key, StakeContext.Node, byod = true)
        assertEquals(StakeContext.Node, vm.uiState.value.context)
        assertTrue(vm.uiState.value.byod)
    }
}
