package com.frynetworks.fryapp.ui.miners.stake

import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.ProductReward
import com.frynetworks.fryapp.data.dashboard.model.ProductStake
import com.frynetworks.fryapp.data.dashboard.model.ProductTokens
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.TxStatus
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
import com.frynetworks.fryapp.wallet.TxnSummary
import com.frynetworks.fryapp.wallet.WalletBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

/**
 * S-5: the sheet is open for miner B while the recorded transfer is miner A's. Retry resumes A's transfer (funds are safe), and the
 * sheet must then say it was A that was recorded. The state machine and [StakeState.Done] are unchanged; the miner is a field of the
 * UI state. Read reflectively so this file also runs against 2091673, where the field does not exist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StakeCompletedMinerTest {

    private val minerA = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val minerB = "FEM-ZYXWVUTSRQPONMLKJIHGFEDCBA543210"
    private val tx = "TXAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val miners = FakeMinerRepository()
    private val rewards = FakeRewardsRepository()
    private val stakes = FakeStakeRepository()
    private val fake = FakeWalletBridge()
    private val backing = FakeAlgodRepository()
    private var throwOnSubmit: Throwable? = null
    private var status: TxStatus = TxStatus.Unknown

    private val algod = object : AlgodRepository by backing {
        override suspend fun txStatus(txId: String): TxStatus = status
        override suspend fun currentRound(): Long? = 1_000L
    }
    private val bridge: WalletBridge = object : WalletBridge by fake {
        override suspend fun decodeTxn(txnB64: String): TxnSummary {
            val summary = fake.decodeTxn(txnB64)
            return if (summary.type == "axfer") summary.copy(txId = tx, lastValid = 1_500L) else summary
        }

        override suspend fun submit(signedB64: List<String>, waitRounds: Int): List<String> {
            throwOnSubmit?.let {
                fake.submitted += signedB64
                throw it
            }
            return fake.submit(signedB64, waitRounds)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fake.reconnectAddress = TEST_ADDRESS
        fake.submitTxIds = listOf(tx)
        val product = Product(
            key = "FEM",
            reward = ProductReward(
                stake = ProductStake(stakeOne = BigDecimal("5"), stakeTwo = BigDecimal("20"), register = BigDecimal("40"), node = BigDecimal("100")),
                tokens = ProductTokens(stake = "tFRY", reward = "tFRY", register = "tFRY", node = "fNODE"),
            ),
        )
        miners.products = mapOf(minerA to product, minerB to product)
        rewards.priceTable = mapOf(FryAsset.TFRY.id to BigDecimal("0.5"))
        backing.markOptedIn(FryAsset.TFRY.id, 200_000_000L)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = StakeViewModel(miners, rewards, stakes, algod, bridge, TestSession.signedIn(), fixedClock(1_757_592_000_000L), openUri = {})

    private fun completedMiner(vm: StakeViewModel): String? =
        runCatching { vm.uiState.value::class.java.getMethod("getCompletedMinerKey").invoke(vm.uiState.value) as String? }.getOrNull()

    private fun stakeForAThenOpenB(): StakeViewModel {
        val vm = vm()
        throwOnSubmit = IOException("connection reset")
        vm.start(minerA, StakeContext.Registration, byod = false)
        vm.confirm()
        throwOnSubmit = null
        vm.start(minerB, StakeContext.Registration, byod = false)
        assertEquals(minerB, vm.uiState.value.minerKey)
        return vm
    }

    @Test
    fun `the sheet for B finishing the record of A reports A as the completed miner`() = runTest {
        val vm = stakeForAThenOpenB()
        status = TxStatus.Confirmed(1_200)
        vm.retry()
        assertEquals(StakeState.Done("RECORDEDTX", waived = false), vm.uiState.value.state)
        assertEquals("the sheet itself is still for B", minerB, vm.uiState.value.minerKey)
        assertEquals(minerA, completedMiner(vm))
        assertEquals(1, stakes.submitCalls.size)
        assertEquals("A's stake was recorded under A", minerA, stakes.submitCalls.single().second.minerKey)
        assertEquals(1, fake.built.count { it.startsWith("axfer|") })
    }

    @Test
    fun `an ordinary stake reports its own miner and starting again forgets it`() = runTest {
        val vm = vm()
        vm.start(minerA, StakeContext.Registration, byod = false)
        vm.confirm()
        assertEquals(StakeState.Done("RECORDEDTX", waived = false), vm.uiState.value.state)
        assertEquals(minerA, completedMiner(vm))
        vm.start(minerB, StakeContext.Registration, byod = false)
        assertNull(completedMiner(vm))
        assertTrue(vm.uiState.value.state !is StakeState.Done)
    }
}
