package com.frynetworks.fryapp.ui.miners.stake

import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.ProductReward
import com.frynetworks.fryapp.data.dashboard.model.ProductStake
import com.frynetworks.fryapp.data.dashboard.model.ProductTokens
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

@OptIn(ExperimentalCoroutinesApi::class)
class StakeViewModelUnknownAssetTest {

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val miners = FakeMinerRepository()
    private val rewards = FakeRewardsRepository()
    private val stakes = FakeStakeRepository()
    private val algod = FakeAlgodRepository()
    private val bridge = FakeWalletBridge()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        bridge.reconnectAddress = TEST_ADDRESS
        rewards.priceTable = mapOf(FryAsset.TFRY.id to BigDecimal("0.5"), FryAsset.FNODE.id to BigDecimal("2"))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = StakeViewModel(miners, rewards, stakes, algod, bridge, TestSession.signedIn(), fixedClock(1_757_592_000_000L), openUri = {})

    private fun productWithRegister(register: String) = Product(
        key = "FEM",
        reward = ProductReward(
            stake = ProductStake(register = BigDecimal("40")),
            tokens = ProductTokens(register = register),
        ),
    )

    @Test
    fun `unknown numeric register token fails the stake instead of falling back`() = runTest {
        miners.products = mapOf(key to productWithRegister("999"))
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        val state = vm.uiState.value.state
        assertTrue(state.toString(), state is StakeState.Failed)
        assertEquals("UNKNOWN_ASSET", (state as StakeState.Failed).code)
        assertTrue(stakes.submitCalls.isEmpty())
    }

    @Test
    fun `numeric FNODE register token starts a Ready stake with FNODE`() = runTest {
        miners.products = mapOf(key to productWithRegister("2485202024"))
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        assertEquals(StakeState.Ready(BigDecimal("20"), FryAsset.FNODE, BigDecimal("40")), vm.uiState.value.state)
    }
}
