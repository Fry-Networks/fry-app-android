package com.frynetworks.fryapp.ui.miners.stake

import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.ProductReward
import com.frynetworks.fryapp.data.dashboard.model.ProductStake
import com.frynetworks.fryapp.data.dashboard.model.ProductTokens
import com.frynetworks.fryapp.data.dashboard.model.StakeSubmitResponse
import com.frynetworks.fryapp.data.dashboard.repo.StakeRepository
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.domain.StakeState
import com.frynetworks.fryapp.domain.StakeTier
import com.frynetworks.fryapp.fakes.FakeAlgodRepository
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.FakeRewardsRepository
import com.frynetworks.fryapp.fakes.FakeStakeRepository
import com.frynetworks.fryapp.fakes.FakeWalletBridge
import com.frynetworks.fryapp.fakes.FakeWalletBridge.SignBehaviour
import com.frynetworks.fryapp.fakes.TEST_ADDRESS
import com.frynetworks.fryapp.fakes.TestSession
import com.frynetworks.fryapp.fakes.fixedClock
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.ui.miners.claim.StageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class StakeViewModelTest {

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val now = 1_757_592_000_000L
    private val miners = FakeMinerRepository()
    private val rewards = FakeRewardsRepository()
    private val stakes = FakeStakeRepository()
    private val algod = FakeAlgodRepository()
    private val bridge = FakeWalletBridge()
    private val product = Product(
        key = "FEM",
        reward = ProductReward(
            stake = ProductStake(stakeOne = BigDecimal("5"), stakeTwo = BigDecimal("20"), register = BigDecimal("40"), node = BigDecimal("100")),
            tokens = ProductTokens(stake = "tFRY", reward = "tFRY", register = "tFRY", node = "fNODE"),
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        bridge.reconnectAddress = TEST_ADDRESS
        miners.products = mapOf(key to product)
        rewards.priceTable = mapOf(FryAsset.TFRY.id to BigDecimal("0.5"), FryAsset.FNODE.id to BigDecimal("2"))
        algod.markOptedIn(FryAsset.TFRY.id, 200_000_000L)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(session: SessionRepository = TestSession.signedIn()) =
        StakeViewModel(miners, rewards, stakes, algod, bridge, session, fixedClock(now), openUri = {})

    private fun failed(vm: StakeViewModel): StakeState.Failed = vm.uiState.value.state as StakeState.Failed

    @Test
    fun `start computes the token amount from the product tier and the asset price`() = runTest {
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        val s = vm.uiState.value
        assertEquals(StakeState.Ready(BigDecimal("80"), FryAsset.TFRY, BigDecimal("40")), s.state)
        assertTrue(s.canConfirm)
        assertFalse(s.loading)
        assertEquals(StageStatus.CURRENT, s.stages()[StakeStage.AMOUNT.ordinal].second)
    }

    @Test
    fun `BYOD halves the USD amount`() = runTest {
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = true)
        assertEquals(StakeState.Ready(BigDecimal("40"), FryAsset.TFRY, BigDecimal("20.00")), vm.uiState.value.state)
    }

    @Test
    fun `node context uses the node token from the product catalog`() = runTest {
        algod.markOptedIn(FryAsset.FNODE.id, 1_000_000_000L)
        val vm = vm()
        vm.start(key, StakeContext.Node, byod = false)
        assertEquals(StakeState.Ready(BigDecimal("50"), FryAsset.FNODE, BigDecimal("100")), vm.uiState.value.state)
    }

    @Test
    fun `success path - precheck, note, transfer, wait, verify, record, Done, detail refresh`() = runTest {
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()

        assertEquals(StakeState.Done("RECORDEDTX", waived = false), vm.uiState.value.state)
        assertEquals(1, stakes.precheckCalls.size)
        assertEquals(Triple(key, TEST_ADDRESS, StakeContext.Registration), stakes.precheckCalls[0])
        val built = bridge.built[0]
        assertTrue(built, built.startsWith("axfer|$TEST_ADDRESS|${StakeRepository.STAKE_WALLET}|${FryAsset.TFRY.id}|80000000|"))
        val note = built.substringAfter("|80000000|").substringBefore('#')
        // Byte-exact F9 key order; the timestamp comes from the offset-based ServerClock so it may drift by a few ms.
        assertEquals(
            "{\"action\":\"Registration Staking\",\"miner_key\":\"FEM-ABCDEF\",\"asset_id\":${FryAsset.TFRY.id},\"from\":\"$TEST_ADDRESS\"," +
                "\"to\":\"${StakeRepository.STAKE_WALLET}\",\"amount\":80,\"operation\":\"registration_staking\",\"timestamp\":",
            note.substringBefore("\"timestamp\":") + "\"timestamp\":",
        )
        // Stake.tsx:511 sends `new Date().toISOString()`; parse it back and compare against the clock.
        val isoTimestamp = note.substringAfter("\"timestamp\":\"").substringBefore('"')
        assertTrue(isoTimestamp, Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z").matches(isoTimestamp))
        val timestamp = java.time.Instant.parse(isoTimestamp).toEpochMilli()
        assertTrue("note timestamp $timestamp should be ~now $now", timestamp - now in 0L..5_000L)
        assertTrue(note.endsWith("}"))
        assertEquals(listOf("SUBMITTEDTX"), algod.waited)
        assertEquals(listOf(TEST_ADDRESS to "SUBMITTEDTX"), stakes.verifyCalls)
        val (context, payload) = stakes.submitCalls.single()
        assertEquals(StakeContext.Registration, context)
        assertEquals(0, BigDecimal(80).compareTo(payload.amount))
        assertEquals(FryAsset.TFRY.id, payload.assetId)
        assertEquals("SUBMITTEDTX", payload.txId)
        assertEquals(1, miners.refreshDetailCalls)
        assertTrue(vm.uiState.value.stages().all { it.second == StageStatus.DONE })
    }

    @Test
    fun `verification context puts the tier in the note and selectTier recomputes`() = runTest {
        val vm = vm()
        vm.start(key, StakeContext.Verification(StakeTier.ONE), byod = false)
        // stake_one = 5.0 tokens is the amount itself (Stake.tsx:218-229); 5 x 0.5 USD is only the display value
        assertEquals(StakeState.Ready(BigDecimal("5"), FryAsset.TFRY, BigDecimal("2.50")), vm.uiState.value.state)
        vm.confirm()
        val note = bridge.built[0].substringAfter("|5000000|").substringBefore('#')
        assertTrue(note, note.contains("\"type\":\"one\""))
        assertTrue(note, note.contains("\"action\":\"Verification Staking\""))
        assertTrue(note, note.contains("\"operation\":\"verification_staking\""))
        assertEquals(StakeContext.Verification(StakeTier.ONE), stakes.submitCalls.single().first)

        vm.selectTier(StakeTier.TWO)
        assertEquals(StakeState.Ready(BigDecimal("20"), FryAsset.TFRY, BigDecimal("10.00")), vm.uiState.value.state)
    }

    @Test
    fun `precheck 429 fails with RATE_LIMIT_EXCEEDED and the Retry-After seconds`() = runTest {
        stakes.precheckError = DashboardException("RATE_LIMIT_EXCEEDED", "Too many requests", action = "30", httpStatus = 429)
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()
        val f = failed(vm)
        assertEquals("RATE_LIMIT_EXCEEDED", f.code)
        assertEquals(30, f.retryAfterSeconds)
        assertTrue(f.recoverable)
        assertTrue(bridge.signRequests.isEmpty())
        assertEquals(StakeStage.PRECHECK.ordinal, vm.uiState.value.failedStage)
    }

    @Test
    fun `rejecting the stake transfer fails with USER_REJECTED and nothing is submitted or recorded`() = runTest {
        bridge.signBehaviour = listOf(SignBehaviour.Reject)
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()
        assertEquals("USER_REJECTED", failed(vm).code)
        assertTrue(bridge.submitted.isEmpty())
        assertTrue(stakes.submitCalls.isEmpty())
        assertEquals(StakeStage.SIGN.ordinal, vm.uiState.value.failedStage)
    }

    @Test
    fun `a waived dashboard response is still Done`() = runTest {
        stakes.submitResponse = StakeSubmitResponse(success = true, waived = true)
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()
        assertEquals(StakeState.Done("SUBMITTEDTX", waived = true), vm.uiState.value.state)
    }

    @Test
    fun `no product in the catalog fails with NO_PRODUCT`() = runTest {
        miners.products = emptyMap()
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        assertEquals("NO_PRODUCT", failed(vm).code)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun `a missing price fails with INVALID_PRICE`() = runTest {
        rewards.priceTable = emptyMap()
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        assertEquals("INVALID_PRICE", failed(vm).code)
    }

    @Test
    fun `insufficient ALGO buffer fails before the precheck`() = runTest {
        algod.algoBalance = 1_000L
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()
        assertEquals("INSUFFICIENT_ALGO", failed(vm).code)
        assertTrue(stakes.precheckCalls.isEmpty())
    }

    @Test
    fun `insufficient asset balance fails with INSUFFICIENT_BALANCE`() = runTest {
        algod.markOptedIn(FryAsset.TFRY.id, 1_000_000L)
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()
        assertEquals("INSUFFICIENT_BALANCE", failed(vm).code)
    }

    @Test
    fun `not opted in - OptInRequired, then opt-in continues through to Done`() = runTest {
        algod.assetBalances.remove(FryAsset.TFRY.id)
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()
        assertEquals(StakeState.OptInRequired(FryAsset.TFRY.id), vm.uiState.value.state)

        algod.markOptedIn(FryAsset.TFRY.id, 200_000_000L)
        vm.optIn()

        assertTrue(bridge.built[0].startsWith("optin|$TEST_ADDRESS|${FryAsset.TFRY.id}"))
        assertTrue(bridge.built[1].startsWith("axfer|"))
        assertEquals(StakeState.Done("RECORDEDTX", waived = false), vm.uiState.value.state)
    }

    @Test
    fun `opting in with no tokens afterwards fails with INSUFFICIENT_BALANCE`() = runTest {
        algod.assetBalances.remove(FryAsset.TFRY.id)
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()
        algod.markOptedIn(FryAsset.TFRY.id, 0L)
        vm.optIn()
        assertEquals("INSUFFICIENT_BALANCE", failed(vm).code)
        assertTrue(stakes.precheckCalls.isEmpty())
    }

    @Test
    fun `verify-txn failure surfaces its dashboard code`() = runTest {
        stakes.verifyError = DashboardException("TXN_NOT_VERIFIED", "not found", httpStatus = 400)
        val vm = vm()
        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()
        assertEquals("TXN_NOT_VERIFIED", failed(vm).code)
        assertTrue(stakes.submitCalls.isEmpty())
        assertEquals(StakeStage.VERIFY.ordinal, vm.uiState.value.failedStage)
    }

    @Test
    fun `wallet mismatch and signed-out sessions fail before any work`() = runTest {
        bridge.reconnectAddress = com.frynetworks.fryapp.fakes.OTHER_ADDRESS
        val mismatch = vm()
        mismatch.start(key, StakeContext.Registration, byod = false)
        assertEquals("WALLET_MISMATCH", failed(mismatch).code)

        val signedOut = vm(TestSession.signedOut())
        signedOut.start(key, StakeContext.Registration, byod = false)
        assertEquals("UNAUTHORIZED", failed(signedOut).code)
        assertTrue(rewards.pricesError == null)
    }

    @Test
    fun `cancel is blocked while signing submitting verifying or recording`() {
        assertFalse(StakeUiState(state = StakeState.AwaitingSignature).canCancel)
        assertFalse(StakeUiState(state = StakeState.Submitting("T")).canCancel)
        assertFalse(StakeUiState(state = StakeState.Verifying).canCancel)
        assertFalse(StakeUiState(state = StakeState.Recording).canCancel)
        assertTrue(StakeUiState(state = StakeState.Ready(BigDecimal("1"), FryAsset.TFRY, BigDecimal.ONE)).canCancel)
        assertTrue(StakeUiState(state = null, loading = true).canCancel)
    }
}
