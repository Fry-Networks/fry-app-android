package com.frynetworks.fryapp.ui.miners.claim

import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.data.dashboard.model.ClaimEnvelopeResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimPreviewResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimTotalDto
import com.frynetworks.fryapp.data.dashboard.model.ConfirmResultResponse
import com.frynetworks.fryapp.data.dashboard.repo.StakeRepository
import com.frynetworks.fryapp.domain.ClaimState
import com.frynetworks.fryapp.domain.ClaimTotal
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.fakes.FakeAlgodRepository
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.FakeRewardsRepository
import com.frynetworks.fryapp.fakes.FakeWalletBridge
import com.frynetworks.fryapp.fakes.FakeWalletBridge.SignBehaviour
import com.frynetworks.fryapp.fakes.OTHER_ADDRESS
import com.frynetworks.fryapp.fakes.TEST_ADDRESS
import com.frynetworks.fryapp.fakes.TestSession
import com.frynetworks.fryapp.fakes.fixedClock
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.ui.common.ErrorCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class ClaimViewModelTest {

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val asset = FryAsset.FNODE
    private val now = 1_757_592_000_000L
    private val rewards = FakeRewardsRepository()
    private val algod = FakeAlgodRepository()
    private val miners = FakeMinerRepository()
    private val bridge = FakeWalletBridge()
    private val opened = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        bridge.reconnectAddress = TEST_ADDRESS
        algod.markOptedIn(asset.id)
        rewards.preview = ClaimPreviewResponse(success = true, preview = true, totals = listOf(ClaimTotalDto(asset.id.toString(), BigDecimal("4.25"))))
        rewards.envelope = ClaimEnvelopeResponse(
            success = true,
            mode = "user_pays",
            groupId = "G1",
            unsignedUserLeg = "USERLEG",
            unsignedServerLegs = listOf("SERVER1", "SERVER2"),
        )
        rewards.confirmResult = ConfirmResultResponse(ok = true, txId = "CONFIRMTX", claimedAt = "2026-09-11T12:00:00.000Z")
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(session: SessionRepository = TestSession.signedIn()) = ClaimViewModel(
        rewards, algod, miners, bridge, session, fixedClock(now),
        openUri = { opened += it },
        pollDelayMillis = 0,
        optInRetryDelayMillis = 0,
    )

    private fun failed(vm: ClaimViewModel): ClaimState.Failed = vm.uiState.value.state as ClaimState.Failed

    @Test
    fun `start reaches Preview with the totals when the wallet is opted in`() = runTest {
        val vm = vm()
        vm.start(key)
        val s = vm.uiState.value
        assertEquals(ClaimState.Preview(listOf(ClaimTotal(asset.id, BigDecimal("4.25")))), s.state)
        assertTrue(s.connected)
        assertTrue(s.canConfirm)
        assertTrue(s.canCancel)
        assertEquals(listOf(key to null), rewards.previewCalls)
        assertEquals(StageStatus.CURRENT, s.stages()[ClaimStage.PREVIEW.ordinal].second)
    }

    @Test
    fun `not opted in - OptInRequired, then opt-in signs submits rechecks once and lands on Preview`() = runTest {
        algod.optInVisibleAfterChecks = 2
        val vm = vm()
        vm.start(key)
        assertEquals(ClaimState.OptInRequired(asset.id), vm.uiState.value.state)
        assertTrue(rewards.previewCalls.isEmpty())

        vm.optIn()

        assertTrue(bridge.built[0].startsWith("optin|$TEST_ADDRESS|${asset.id}"))
        assertEquals(1, bridge.submitted.size)
        assertEquals(3, algod.isOptedInCalls)
        assertTrue(vm.uiState.value.state is ClaimState.Preview)
        assertEquals(listOf(key to null), rewards.previewCalls)
    }

    @Test
    fun `rejecting the opt-in fails recoverably with USER_REJECTED`() = runTest {
        algod.optInVisibleAfterChecks = 5
        bridge.signBehaviour = listOf(SignBehaviour.Reject)
        val vm = vm()
        vm.start(key)
        vm.optIn()
        assertEquals("USER_REJECTED", failed(vm).code)
        assertTrue(failed(vm).recoverable)
        assertTrue(bridge.submitted.isEmpty())
    }

    @Test
    fun `preview NO_REWARDS is a recoverable failure with dashboard copy`() = runTest {
        rewards.previewError = DashboardException("NO_REWARDS", "no claimable rewards", httpStatus = 400)
        val vm = vm()
        vm.start(key)
        assertEquals("NO_REWARDS", failed(vm).code)
        assertEquals(ErrorCopy.forCode("NO_REWARDS"), vm.uiState.value.errorCopy)
        assertEquals(StageStatus.FAILED, vm.uiState.value.stages()[ClaimStage.OPT_IN.ordinal].second)
    }

    @Test
    fun `user rejection at the fee payment fails recoverably and never requests the envelope`() = runTest {
        bridge.signBehaviour = listOf(SignBehaviour.Reject)
        val vm = vm()
        vm.start(key)
        vm.confirm()
        val f = failed(vm)
        assertEquals("USER_REJECTED", f.code)
        assertTrue(f.recoverable)
        assertTrue(bridge.built[0].startsWith("pay|$TEST_ADDRESS|${StakeRepository.CLAIM_FEE_WALLET}|${StakeRepository.CLAIM_FEE_MICROALGO}|"))
        assertTrue(rewards.envelopeCalls.isEmpty())
        assertEquals(ClaimStage.FEE.ordinal, vm.uiState.value.failedStage)
    }

    @Test
    fun `insufficient ALGO stops before the fee payment`() = runTest {
        algod.algoBalance = 1_500L
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertEquals("INSUFFICIENT_ALGO", failed(vm).code)
        assertTrue(bridge.signRequests.isEmpty())
    }

    @Test
    fun `user rejection at the group signature fails recoverably after the fee was paid`() = runTest {
        bridge.signBehaviour = listOf(SignBehaviour.Approve, SignBehaviour.Reject)
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertEquals("USER_REJECTED", failed(vm).code)
        assertEquals(listOf(key to null), rewards.envelopeCalls)
        assertTrue(rewards.confirmCalls.isEmpty())
        val group = bridge.signRequests[1][0]
        assertEquals(listOf("USERLEG", "SERVER1", "SERVER2"), group.map { it.txnB64 })
        assertEquals(listOf(true, false, false), group.map { it.sign })
    }

    @Test
    fun `success path - fee, envelope, group signature, confirm, Done, then summary and detail refresh`() = runTest {
        val vm = vm()
        vm.start(key)
        vm.confirm()
        val done = vm.uiState.value.state as ClaimState.Done
        assertEquals("CONFIRMTX", done.txId)
        assertEquals(java.time.Instant.parse("2026-09-11T12:00:00Z").toEpochMilli(), done.claimedAt)
        assertEquals(listOf("G1" to "signed(USERLEG)"), rewards.confirmCalls)
        assertEquals(2, bridge.signRequests.size)
        assertEquals(1, bridge.submitted.size)
        assertEquals(1, rewards.refreshSummaryCalls)
        assertEquals(1, miners.refreshDetailCalls)
        assertTrue(vm.uiState.value.stages().all { it.second == StageStatus.DONE })
        assertNull(vm.uiState.value.pendingSummary)
    }

    @Test
    fun `CLAIM_GROUP_EXPIRED on confirm is a recoverable failure that says start again`() = runTest {
        rewards.confirmError = DashboardException("CLAIM_GROUP_EXPIRED", "The claim group expired.", httpStatus = 410)
        val vm = vm()
        vm.start(key)
        vm.confirm()
        val f = failed(vm)
        assertEquals("CLAIM_GROUP_EXPIRED", f.code)
        assertTrue(f.recoverable)
        assertTrue(f.message.contains("Start again"))
        assertEquals(ClaimStage.CONFIRM.ordinal, vm.uiState.value.failedStage)
    }

    @Test
    fun `REWARD_ALREADY_CLAIMING from the envelope is not recoverable`() = runTest {
        rewards.envelopeError = DashboardException("REWARD_ALREADY_CLAIMING", "in flight", httpStatus = 409)
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertEquals("REWARD_ALREADY_CLAIMING", failed(vm).code)
        assertFalse(failed(vm).recoverable)
    }

    @Test
    fun `custodial envelope skips the group signature and polls confirm until ok`() = runTest {
        rewards.envelope = ClaimEnvelopeResponse(success = true, txId = "CUSTODIALTX")
        rewards.custodialResults = listOf(ConfirmResultResponse(ok = false), ConfirmResultResponse(ok = true, txId = "CUSTODIALTX"))
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertEquals(ClaimState.Done("CUSTODIALTX", null), vm.uiState.value.state)
        assertEquals(2, rewards.custodialPolls)
        assertEquals(1, bridge.signRequests.size)
    }

    @Test
    fun `custodial envelope that never confirms gives up after six polls with CONFIRM_PENDING`() = runTest {
        rewards.envelope = ClaimEnvelopeResponse(success = true, txId = "CUSTODIALTX")
        rewards.custodialResults = listOf(ConfirmResultResponse(ok = false))
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertEquals("CONFIRM_PENDING", failed(vm).code)
        assertEquals(ClaimViewModel.CUSTODIAL_POLL_ATTEMPTS, rewards.custodialPolls)
        assertTrue(failed(vm).message.contains("CUSTODIALTX"))
    }

    @Test
    fun `wallet connected to a different address is WALLET_MISMATCH not a silent re-login`() = runTest {
        bridge.reconnectAddress = OTHER_ADDRESS
        val vm = vm()
        vm.start(key)
        assertEquals("WALLET_MISMATCH", failed(vm).code)
        assertFalse(vm.uiState.value.connected)
        assertTrue(rewards.previewCalls.isEmpty())
    }

    @Test
    fun `no wallet session - needsReconnect, and reconnect continues into the preview`() = runTest {
        bridge.reconnectAddress = null
        bridge.connectAddress = TEST_ADDRESS
        val vm = vm()
        vm.start(key)
        assertTrue(vm.uiState.value.needsReconnect)
        assertEquals(ClaimState.Idle, vm.uiState.value.state)
        assertEquals(StageStatus.CURRENT, vm.uiState.value.stages()[ClaimStage.WALLET.ordinal].second)

        vm.reconnectWallet()

        assertFalse(vm.uiState.value.needsReconnect)
        assertTrue(vm.uiState.value.state is ClaimState.Preview)
    }

    @Test
    fun `signed out session fails with UNAUTHORIZED`() = runTest {
        val vm = vm(TestSession.signedOut())
        vm.start(key)
        assertEquals("UNAUTHORIZED", failed(vm).code)
    }

    @Test
    fun `cancel is blocked while paying the fee, awaiting the signature or confirming`() {
        assertFalse(ClaimUiState(state = ClaimState.PayingFee).canCancel)
        assertFalse(ClaimUiState(state = ClaimState.AwaitingSignature("G1", now)).canCancel)
        assertFalse(ClaimUiState(state = ClaimState.Confirming).canCancel)
        assertTrue(ClaimUiState(state = ClaimState.Preview(emptyList())).canCancel)
        assertTrue(ClaimUiState(state = ClaimState.Failed("X", "x", true)).canCancel)
        assertFalse(ClaimUiState(state = ClaimState.CheckingBalance).canConfirm)
    }

    @Test
    fun `cancel from Preview resets to Idle and retry restarts the flow`() = runTest {
        val vm = vm()
        vm.start(key)
        vm.cancel()
        assertEquals(ClaimState.Idle, vm.uiState.value.state)
        rewards.previewError = DashboardException("NETWORK_ERROR", "offline")
        vm.start(key, no = 12)
        assertEquals("NETWORK_ERROR", failed(vm).code)
        rewards.previewError = null
        vm.retry()
        assertTrue(vm.uiState.value.state is ClaimState.Preview)
        assertEquals(key to 12, rewards.previewCalls.last())
    }

    @Test
    fun `openWallet re-fires the last deep link the bridge emitted`() = runTest {
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertEquals("perawallet-wc://", vm.uiState.value.lastWalletUri)
        vm.openWallet()
        assertEquals(listOf("perawallet-wc://"), opened)
    }

    @Test
    fun `a bridge timeout while signing surfaces TIMEOUT copy`() = runTest {
        bridge.signBehaviour = listOf(SignBehaviour.Timeout)
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertEquals("TIMEOUT", failed(vm).code)
        assertEquals(ErrorCopy.forCode("TIMEOUT"), vm.uiState.value.errorCopy)
    }
}
