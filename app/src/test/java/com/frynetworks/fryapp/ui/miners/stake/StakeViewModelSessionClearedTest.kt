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
import com.frynetworks.fryapp.ui.common.ErrorCopy
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

/** The session clearing while the stake is on its balance or precheck step must end in a failure, not a frozen "checking" sheet. */
@OptIn(ExperimentalCoroutinesApi::class)
class StakeViewModelSessionClearedTest {

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val miners = FakeMinerRepository()
    private val rewards = FakeRewardsRepository()
    private val stakes = FakeStakeRepository()
    private val bridge = FakeWalletBridge()
    private val backing = FakeAlgodRepository()
    private val session = TestSession.signedIn()

    private var signOutOnAlgoRead = false
    private var signOutOnAssetRead = false

    /** The fake balances, except that the session is cleared while an armed read is in flight. */
    private val algod = object : AlgodRepository by backing {
        override suspend fun algoBalanceMicro(address: String): Long {
            if (signOutOnAlgoRead) session.markSignedOut()
            return backing.algoBalanceMicro(address)
        }

        override suspend fun assetBalanceMicro(address: String, assetId: Long): Long? {
            if (signOutOnAssetRead) session.markSignedOut()
            return backing.assetBalanceMicro(address, assetId)
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
        rewards.priceTable = mapOf(FryAsset.TFRY.id to BigDecimal("0.5"))
        backing.markOptedIn(FryAsset.TFRY.id, 200_000_000L)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = StakeViewModel(miners, rewards, stakes, algod, bridge, session, fixedClock(1_757_592_000_000L), openUri = {})

    private fun assertUnauthorized(vm: StakeViewModel) {
        val state = vm.uiState.value.state
        assertTrue("expected Failed but was $state", state is StakeState.Failed)
        state as StakeState.Failed
        assertEquals("UNAUTHORIZED", state.code)
        assertEquals(ErrorCopy.forCode("UNAUTHORIZED"), state.message)
        assertTrue(stakes.precheckCalls.isEmpty())
        assertTrue("no stake transfer is built", bridge.built.none { it.startsWith("axfer|") })
    }

    @Test
    fun `the session clearing during the balance check fails the stake as unauthorised`() = runTest {
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        signOutOnAlgoRead = true

        vm.confirm()

        assertUnauthorized(vm)
    }

    @Test
    fun `the session clearing after the opt-in fails the stake as unauthorised`() = runTest {
        backing.assetBalances.remove(FryAsset.TFRY.id)
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()
        assertEquals(StakeState.OptInRequired(FryAsset.TFRY.id), vm.uiState.value.state)
        backing.markOptedIn(FryAsset.TFRY.id, 200_000_000L)
        signOutOnAssetRead = true

        vm.optIn()

        assertUnauthorized(vm)
    }
}
