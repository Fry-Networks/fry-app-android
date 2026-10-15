package com.frynetworks.fryapp.ui.miners.claim

import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.data.dashboard.model.ClaimEnvelopeResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimPreviewResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimTotalDto
import com.frynetworks.fryapp.data.dashboard.model.ConfirmResultResponse
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.domain.ClaimState
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.fakes.FakeAlgodRepository
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.FakeRewardsRepository
import com.frynetworks.fryapp.fakes.FakeWalletBridge
import com.frynetworks.fryapp.fakes.TEST_ADDRESS
import com.frynetworks.fryapp.fakes.TestSession
import com.frynetworks.fryapp.fakes.fixedClock
import com.frynetworks.fryapp.wallet.WalletBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
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

/** A recreated sheet re-opens the flow; that must not re-run a claim whose fee is already being paid. */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaimViewModelResumeGuardTest {

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val otherKey = "FEM-ZYXWVUTSRQPONMLKJIHGFEDCBA543210"
    private val asset = FryAsset.FNODE
    private val rewards = FakeRewardsRepository()
    private val algod = FakeAlgodRepository()
    private val fake = FakeWalletBridge()
    private val submitGate = CompletableDeferred<Unit>()
    private val envelopeGate = CompletableDeferred<Unit>()

    /** The scripted bridge with `submit` held until [submitGate] opens: the fee payment in flight. */
    private val bridge: WalletBridge = object : WalletBridge by fake {
        override suspend fun submit(signedB64: List<String>, waitRounds: Int): List<String> {
            submitGate.await()
            return fake.submit(signedB64, waitRounds)
        }
    }

    /** The scripted dashboard with `claimEnvelope` held until [envelopeGate] opens. */
    private val gatedRewards: RewardsRepository = object : RewardsRepository by rewards {
        override suspend fun claimEnvelope(minerKey: String, no: Int?): ClaimEnvelopeResponse {
            val response = rewards.claimEnvelope(minerKey, no)
            envelopeGate.await()
            return response
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

    private fun vm() = ClaimViewModel(
        gatedRewards, algod, FakeMinerRepository(), bridge, TestSession.signedIn(), fixedClock(1_757_592_000_000L),
        openUri = {}, pollDelayMillis = 0, optInRetryDelayMillis = 0,
    )

    /** Cancels the running job but leaves the screen state as it was, as a process-side cancellation would. */
    private fun killJob(vm: ClaimViewModel) {
        vm.viewModelScope.coroutineContext[Job]!!.cancelChildren()
    }

    private fun payingFee(no: Int? = null): ClaimViewModel {
        val vm = vm()
        vm.start(key, no)
        vm.confirm()
        assertEquals(ClaimState.PayingFee, vm.uiState.value.state)
        assertFalse(vm.uiState.value.canCancel)
        return vm
    }

    @Test
    fun `resume while the fee is being paid does not pay it again`() = runTest {
        val vm = payingFee()
        assertEquals(1, fake.built.size)

        vm.resume(key, null)

        assertEquals(ClaimState.PayingFee, vm.uiState.value.state)
        assertEquals(1, rewards.previewCalls.size)
        submitGate.complete(Unit)
        envelopeGate.complete(Unit)
        assertTrue("${vm.uiState.value.state}", vm.uiState.value.state is ClaimState.Done)
        assertEquals(1, fake.built.count { it.startsWith("pay|") })
    }

    @Test
    fun `resume while the claim is being prepared leaves that job running`() = runTest {
        submitGate.complete(Unit)
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertEquals(ClaimState.RequestingEnvelope, vm.uiState.value.state)
        assertTrue(vm.uiState.value.canCancel)

        vm.resume(key, null)

        assertEquals(ClaimState.RequestingEnvelope, vm.uiState.value.state)
        assertEquals(1, rewards.previewCalls.size)
        assertEquals(1, rewards.envelopeCalls.size)
    }

    @Test
    fun `resume for the same single reward row is guarded too`() = runTest {
        val vm = payingFee(no = 7)

        vm.resume(key, 7)

        assertEquals(ClaimState.PayingFee, vm.uiState.value.state)
        assertEquals(listOf(key to 7), rewards.previewCalls)
    }

    @Test
    fun `resume for another miner or another row starts over`() = runTest {
        val vm = payingFee(no = 7)

        vm.resume(key, 8)
        assertEquals(2, rewards.previewCalls.size)
        assertEquals(key to 8, rewards.previewCalls.last())
        assertTrue("${vm.uiState.value.state}", vm.uiState.value.state is ClaimState.Preview)

        vm.resume(otherKey, 8)
        assertEquals(otherKey to 8, rewards.previewCalls.last())
        assertEquals(otherKey, vm.uiState.value.minerKey)
    }

    @Test
    fun `resume restarts when the job is gone even though the screen still shows the fee step`() = runTest {
        val vm = payingFee()
        killJob(vm)
        // The guard condition on the screen state is still met; only the dead job should let resume through.
        assertEquals(ClaimState.PayingFee, vm.uiState.value.state)
        assertFalse(vm.uiState.value.canCancel)

        vm.resume(key, null)

        assertEquals(2, rewards.previewCalls.size)
        assertTrue("${vm.uiState.value.state}", vm.uiState.value.state is ClaimState.Preview)
    }

    @Test
    fun `resume opens the flow when it is idle or already showing the preview`() = runTest {
        val vm = vm()
        vm.resume(key, null)
        assertTrue("${vm.uiState.value.state}", vm.uiState.value.state is ClaimState.Preview)
        assertEquals(1, rewards.previewCalls.size)

        vm.resume(key, null)
        assertTrue(vm.uiState.value.state is ClaimState.Preview)
        assertEquals(2, rewards.previewCalls.size)
    }
}
