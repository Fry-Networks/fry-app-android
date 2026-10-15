package com.frynetworks.fryapp.ui.miners.claim

import com.frynetworks.fryapp.data.dashboard.model.ClaimEnvelopeResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimPreviewResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimTotalDto
import com.frynetworks.fryapp.data.dashboard.model.ConfirmResultResponse
import com.frynetworks.fryapp.domain.ClaimState
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.domain.StakeState
import com.frynetworks.fryapp.fakes.FakeAlgodRepository
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.FakeRewardsRepository
import com.frynetworks.fryapp.fakes.FakeStakeRepository
import com.frynetworks.fryapp.fakes.FakeWalletBridge
import com.frynetworks.fryapp.fakes.TEST_ADDRESS
import com.frynetworks.fryapp.fakes.TestSession
import com.frynetworks.fryapp.fakes.fixedClock
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.ui.miners.stake.StakeViewModel
import com.frynetworks.fryapp.wallet.BridgeErrorCode
import com.frynetworks.fryapp.wallet.BridgeException
import com.frynetworks.fryapp.wallet.TxnToSign
import com.frynetworks.fryapp.wallet.WalletAccount
import com.frynetworks.fryapp.wallet.WalletBridge
import com.frynetworks.fryapp.wallet.WalletVendor
import kotlinx.coroutines.CancellationException
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

/**
 * A bridge reply of the wrong shape surfaces as an [IllegalStateException]. That must end the claim or stake as
 * a failure (a [BridgeException] keeps its own code), never crash the app or leave the sheet on "connecting".
 * Cancellation is an [IllegalStateException] too, so it has to be rethrown before the wrong-shape handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaimBridgeShapeTest {

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val asset = FryAsset.FNODE
    private val fake = FakeWalletBridge()
    private val rewards = FakeRewardsRepository()
    private val algod = FakeAlgodRepository()

    private var onReconnect: () -> Unit = {}
    private var onConnect: () -> Unit = {}
    private var onSign: (call: Int) -> Unit = {}
    private var signCalls = 0

    private val bridge: WalletBridge = object : WalletBridge by fake {
        override suspend fun reconnect(vendor: WalletVendor): WalletAccount? {
            onReconnect()
            return fake.reconnect(vendor)
        }

        override suspend fun connect(vendor: WalletVendor): WalletAccount {
            onConnect()
            return fake.connect(vendor)
        }

        override suspend fun signTxns(groups: List<List<TxnToSign>>): List<List<String?>> {
            onSign(++signCalls)
            return fake.signTxns(groups)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fake.reconnectAddress = TEST_ADDRESS
        algod.markOptedIn(asset.id)
        rewards.preview = ClaimPreviewResponse(success = true, preview = true, totals = listOf(ClaimTotalDto(asset.id.toString(), BigDecimal("4.25"))))
        rewards.envelope = ClaimEnvelopeResponse(success = true, mode = "user_pays", groupId = "G1", unsignedUserLeg = "USERLEG", unsignedServerLegs = listOf("SERVER1"))
        rewards.confirmResult = ConfirmResultResponse(ok = true, txId = "CONFIRMTX", claimedAt = "2026-09-11T12:00:00.000Z")
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun claimVm() = ClaimViewModel(
        rewards, algod, FakeMinerRepository(), bridge, TestSession.signedIn(), fixedClock(1_757_592_000_000L),
        openUri = {}, pollDelayMillis = 0, optInRetryDelayMillis = 0,
    )

    private fun stakeVm() = StakeViewModel(
        FakeMinerRepository(), rewards, FakeStakeRepository(), algod, bridge, TestSession.signedIn(), fixedClock(1_757_592_000_000L), openUri = {},
    )

    private fun wrongShape(): Nothing = throw IllegalStateException("Not a JSON Object")

    private fun claimFailed(vm: ClaimViewModel): ClaimState.Failed {
        val state = vm.uiState.value.state
        assertTrue("expected Failed but was $state", state is ClaimState.Failed)
        return state as ClaimState.Failed
    }

    private fun stakeFailed(vm: StakeViewModel): StakeState.Failed {
        val state = vm.uiState.value.state
        assertTrue("expected Failed but was $state", state is StakeState.Failed)
        return state as StakeState.Failed
    }

    // --- claim: connecting ----------------------------------------------------------------------

    @Test
    fun `claim - a wrong-shape reconnect reply fails with UNKNOWN and stops connecting`() = runTest {
        onReconnect = { wrongShape() }
        val vm = claimVm()
        vm.start(key)
        assertEquals("UNKNOWN", claimFailed(vm).code)
        assertFalse(vm.uiState.value.connecting)
    }

    @Test
    fun `claim - a wrong-shape connect reply fails with UNKNOWN and stops connecting`() = runTest {
        fake.reconnectAddress = null
        onConnect = { wrongShape() }
        val vm = claimVm()
        vm.start(key)
        vm.reconnectWallet()
        assertEquals("UNKNOWN", claimFailed(vm).code)
        assertFalse(vm.uiState.value.connecting)
    }

    @Test
    fun `claim - a bridge error on connect keeps its own code`() = runTest {
        fake.reconnectAddress = null
        onConnect = { throw BridgeException(BridgeErrorCode.TIMEOUT, "The wallet did not answer") }
        val vm = claimVm()
        vm.start(key)
        vm.reconnectWallet()
        assertEquals("TIMEOUT", claimFailed(vm).code)
    }

    @Test
    fun `claim - cancellation while reconnecting is rethrown, not turned into a failure`() = runTest {
        onReconnect = { throw CancellationException("cancelled") }
        val vm = claimVm()
        vm.start(key)
        assertFalse("${vm.uiState.value.state}", vm.uiState.value.state is ClaimState.Failed)
    }

    // --- claim: signing -------------------------------------------------------------------------

    @Test
    fun `claim - a wrong-shape sign reply for the claim group fails with UNKNOWN`() = runTest {
        onSign = { call -> if (call == 2) wrongShape() }
        val vm = claimVm()
        vm.start(key)
        vm.confirm()
        assertEquals(2, signCalls)
        assertEquals("UNKNOWN", claimFailed(vm).code)
        assertTrue(rewards.confirmCalls.isEmpty())
    }

    @Test
    fun `claim - a bridge error signing the claim group keeps its own code`() = runTest {
        onSign = { call -> if (call == 2) throw BridgeException(BridgeErrorCode.TIMEOUT, "signTxns timed out") }
        val vm = claimVm()
        vm.start(key)
        vm.confirm()
        assertEquals("TIMEOUT", claimFailed(vm).code)
    }

    @Test
    fun `claim - cancellation while signing the claim group is rethrown, not turned into a failure`() = runTest {
        onSign = { call -> if (call == 2) throw CancellationException("cancelled") }
        val vm = claimVm()
        vm.start(key)
        vm.confirm()
        assertEquals(2, signCalls)
        assertFalse("${vm.uiState.value.state}", vm.uiState.value.state is ClaimState.Failed)
    }

    // --- stake: connecting ----------------------------------------------------------------------

    @Test
    fun `stake - a wrong-shape reconnect reply fails with UNKNOWN and stops connecting`() = runTest {
        onReconnect = { wrongShape() }
        val vm = stakeVm()
        vm.start(key, StakeContext.Registration, byod = false)
        assertEquals("UNKNOWN", stakeFailed(vm).code)
        assertFalse(vm.uiState.value.connecting)
    }

    @Test
    fun `stake - a wrong-shape connect reply fails with UNKNOWN and stops connecting`() = runTest {
        fake.reconnectAddress = null
        onConnect = { wrongShape() }
        val vm = stakeVm()
        vm.start(key, StakeContext.Registration, byod = false)
        vm.reconnectWallet()
        assertEquals("UNKNOWN", stakeFailed(vm).code)
        assertFalse(vm.uiState.value.connecting)
    }

    @Test
    fun `stake - cancellation while reconnecting is rethrown, not turned into a failure`() = runTest {
        onReconnect = { throw CancellationException("cancelled") }
        val vm = stakeVm()
        vm.start(key, StakeContext.Registration, byod = false)
        assertFalse("${vm.uiState.value.state}", vm.uiState.value.state is StakeState.Failed)
    }

    @Test
    fun `stake - cancellation while connecting is rethrown, not turned into a failure`() = runTest {
        fake.reconnectAddress = null
        onConnect = { throw CancellationException("cancelled") }
        val vm = stakeVm()
        vm.start(key, StakeContext.Registration, byod = false)
        vm.reconnectWallet()
        assertFalse("${vm.uiState.value.state}", vm.uiState.value.state is StakeState.Failed)
    }
}
