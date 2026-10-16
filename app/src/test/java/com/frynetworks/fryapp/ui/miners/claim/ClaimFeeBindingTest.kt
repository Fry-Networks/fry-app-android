package com.frynetworks.fryapp.ui.miners.claim

import com.frynetworks.fryapp.data.dashboard.model.ClaimEnvelopeResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimPreviewResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimTotalDto
import com.frynetworks.fryapp.data.dashboard.model.ConfirmResultResponse
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.StakeRepository
import com.frynetworks.fryapp.data.dashboard.repo.TxResolution
import com.frynetworks.fryapp.data.dashboard.repo.TxResolver
import com.frynetworks.fryapp.data.dashboard.repo.TxStatus
import com.frynetworks.fryapp.data.pending.PendingTransfer
import com.frynetworks.fryapp.data.pending.PendingTransferStore
import com.frynetworks.fryapp.data.pending.PendingTransferStoreTest
import com.frynetworks.fryapp.data.pending.SharedPrefsPendingTransferStore
import com.frynetworks.fryapp.domain.ClaimState
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.fakes.FakeAlgodRepository
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.FakeRewardsRepository
import com.frynetworks.fryapp.fakes.FakeWalletBridge
import com.frynetworks.fryapp.fakes.TEST_ADDRESS
import com.frynetworks.fryapp.fakes.TestSession
import com.frynetworks.fryapp.fakes.fixedClock
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.wallet.TxnToSign
import com.frynetworks.fryapp.wallet.WalletBridge
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

/**
 * 0.4.8 claim-fee binding: a paid fee record is tied to the claim it pays for, so a record that outlives its claim can never let
 * a later claim skip its own fee, while a claim that never completed still consumes the fee it already paid (no burn loop).
 * Only the public surface of 0.4.7 is used (view model, store, resolver, JSON text) so these tests also run against 2091673.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaimFeeBindingTest {

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val asset = FryAsset.FNODE
    private val now = 1_757_592_000_000L
    private val paySigned = "gqNzaWfEQJ3tQu0YfcH7nHqxFeCRswiuwanJNK7WsUFubIbmoqnNo0JpuyJ9kOllpU0pLNaTKBfHdKP5Qqae3qFZ6QKStAOjdHhuiaNhbXTNA+ijZmVlzQPoomZ2zgPvhf2jZ2VurG1haW5uZXQtdjEuMKJnaMQgwGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit+ibHbOA++J5aNyY3bEIP0XJDhaoMdbZPt4zWAvodmR/ev3axPFjtcC6sg16fYYo3NuZMQg6kpsY+KcUgq+9VB7Ey7F+ZVHdq6+vnuSQh7qaRRG0iykdHlwZaNwYXk="
    private val payTxId = "3TMZOQPUKWBLEVC2CQZCDUFS2O2KTDD3MHNC5JGZ4UWCF442SFWA"
    private val payLastValid = 66_030_053L

    // Two real signed blobs (algosdk, throwaway key) standing in for the claim's unsigned user leg: parseable, so the leg's id and last valid round are known.
    private val leg1 = "gqNzaWfEQOgumRWlPG+C/xnCYYeu0Parj8rqN31ruUF09EiPWnHQYd6zfXMqZ6iH3AJb1aqnsj3P4iN9f5jZhNWjZHCyDwKjdHhuiqRhYW10zgBMS0CkYXJjdsQg/RckOFqgx1tk+3jNYC+h2ZH96/drE8WO1wLqyDXp9hijZmVlzQPoomZ2zQPoo2dlbqxtYWlubmV0LXYxLjCiZ2jEIMBhxNj8Hb3e0tdgS+RWjj9tBBmHrDe95LYgtas5JIrfomx2zQXco3NuZMQg6kpsY+KcUgq+9VB7Ey7F+ZVHdq6+vnuSQh7qaRRG0iykdHlwZaVheGZlcqR4YWlkzp/Uxu0="
    private val leg2 = "gqNzaWfEQMXiGsqdMWlSh+Cn1oQlfZIkElil8Ofksr/ZUmFcophGECVlMV29qqwBbVkKQdS3/2nyhVwdGzz1HueOr4MwkAyjdHhuiqRhYW10zgBMS0CkYXJjdsQg/RckOFqgx1tk+3jNYC+h2ZH96/drE8WO1wLqyDXp9hijZmVlzQPoomZ2zQPoo2dlbqxtYWlubmV0LXYxLjCiZ2jEIMBhxNj8Hb3e0tdgS+RWjj9tBBmHrDe95LYgtas5JIrfomx2zROIo3NuZMQg6kpsY+KcUgq+9VB7Ey7F+ZVHdq6+vnuSQh7qaRRG0iykdHlwZaVheGZlcqR4YWlkzp/Uxu0="
    private val leg1Id = "SPWQWS7VMRTDHZWM2VTUGF6INVO3BXZYGSAW7G4U6FJAUDI4VOUQ"
    private val leg2Id = "SJF3LQCVVM4AWZF4WAW7UENC5Z2ATRJ56QIBX5SM2XA7QDFTCTQA"

    private val rewards = FakeRewardsRepository()
    private val backing = FakeAlgodRepository()
    private val miners = FakeMinerRepository()
    private val fake = FakeWalletBridge()
    private val prefs = PendingTransferStoreTest.FakePrefs()
    private val real = SharedPrefsPendingTransferStore(prefs)
    private var failRemove = false
    private var failPut = false
    private val store: PendingTransferStore = object : PendingTransferStore by real {
        override fun remove(sender: String, kind: com.frynetworks.fryapp.data.pending.TransferKind, minerKey: String, row: String) =
            if (failRemove) false else real.remove(sender, kind, minerKey, row)

        override fun put(record: PendingTransfer) = if (failPut) false else real.put(record)
    }
    private val session = TestSession.signedIn()

    private val statuses = mutableMapOf<String, TxStatus>()
    private val verdicts = mutableMapOf<String, TxResolution>()
    private val resolver = object : TxResolver {
        override suspend fun resolve(txId: String, lastValid: Long?): TxResolution = verdicts[txId] ?: TxResolution.Unknown
        override suspend fun findTransfer(sender: String, receiver: String, assetId: Long, amountMicro: Long, minRound: Long?): TxResolution =
            TxResolution.Unknown
    }
    private val algod = object : AlgodRepository by backing {
        override suspend fun txStatus(txId: String): TxStatus = statuses[txId] ?: if (txId == payTxId) TxStatus.Confirmed(66_029_500) else TxStatus.Unknown
        override suspend fun currentRound(): Long? = 1_000L
    }
    private val bridge: WalletBridge = object : WalletBridge by fake {
        override suspend fun signTxns(groups: List<List<TxnToSign>>): List<List<String?>> {
            val result = fake.signTxns(groups)
            val single = groups.singleOrNull()?.singleOrNull()
            return if (single != null && single.txnB64.startsWith("pay|")) listOf(listOf(paySigned)) else result
        }

        override suspend fun submit(signedB64: List<String>, waitRounds: Int): List<String> {
            fake.submitted += signedB64
            return listOf(payTxId)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fake.reconnectAddress = TEST_ADDRESS
        backing.markOptedIn(asset.id)
        rewards.preview = ClaimPreviewResponse(success = true, preview = true, totals = listOf(ClaimTotalDto(asset.id.toString(), BigDecimal("4.25"))))
        rewards.confirmResult = ConfirmResultResponse(ok = true, txId = "CONFIRMTX", claimedAt = "2026-09-11T12:00:00.000Z")
        custodial()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun custodial(vararg results: ConfirmResultResponse) {
        rewards.envelope = ClaimEnvelopeResponse(success = true, txId = "CUSTODIALTX")
        rewards.custodialResults = results.toList().ifEmpty { listOf(ConfirmResultResponse(ok = true, txId = "CUSTODIALTX")) }
    }

    private fun userPays(group: String, leg: String) {
        rewards.envelope = ClaimEnvelopeResponse(
            success = true, mode = "user_pays", groupId = group, unsignedUserLeg = leg, unsignedServerLegs = listOf("SERVER1"),
        )
    }

    private fun vm() = ClaimViewModel(
        rewards, algod, miners, bridge, session, fixedClock(now),
        openUri = {}, pollDelayMillis = 0, optInRetryDelayMillis = 0, store = store, resolver = resolver, ioDispatcher = Dispatchers.Unconfined,
    )

    private fun claim(vm: ClaimViewModel = vm()): ClaimViewModel {
        vm.start(key)
        vm.confirm()
        return vm
    }

    private fun pays() = fake.built.count { it.startsWith("pay|") }

    private fun failed(vm: ClaimViewModel): ClaimState.Failed =
        vm.uiState.value.state as? ClaimState.Failed ?: throw AssertionError("expected Failed, was ${vm.uiState.value.state}")

    private fun recordKey() = "$TEST_ADDRESS|CLAIM_FEE|$key|all"

    private fun json(): String? = prefs.map[recordKey()] as String?

    private fun legacyJson() =
        """{"schemaVersion":1,"kind":"CLAIM_FEE","sender":"$TEST_ADDRESS","minerKey":"$key","row":"all","assetId":0,"amount":"${StakeRepository.CLAIM_FEE_MICROALGO}",""" +
            """"amountMicro":${StakeRepository.CLAIM_FEE_MICROALGO},"receiver":"${StakeRepository.CLAIM_FEE_WALLET}","txId":"$payTxId","fv":66029053,""" +
            """"lv":$payLastValid,"builtRound":1000,"state":"PRE_SUBMIT","createdAt":$now}"""

    // --- (a) a failed clear after success ---------------------------------------------------------------------------------

    @Test
    fun `a failed clear after success - the next claim pays its own fee, exactly one fee per claim`() = runTest {
        failRemove = true
        val first = claim()
        assertTrue(first.uiState.value.state is ClaimState.Done)
        assertEquals(1, pays())
        assertNotNull("the spent fee record could not be removed", json())

        failRemove = false
        val second = claim()
        assertTrue(second.uiState.value.state is ClaimState.Done)
        assertEquals("the second claim paid its own fee", 2, pays())
        assertEquals(null, json())
    }

    // --- (b) a bound record is consumed only by its own claim -------------------------------------------------------------

    @Test
    fun `a bound record whose own claim completed is consumed and the next claim pays a new fee`() = runTest {
        userPays("G1", leg1)
        rewards.confirmError = DashboardException("CLAIM_GROUP_EXPIRED", "late", httpStatus = 410)
        claim()
        assertEquals(1, pays())
        assertNotNull(json())

        verdicts[leg1Id] = TxResolution.Confirmed(leg1Id, 66_029_900)
        rewards.confirmError = null
        userPays("G2", leg2)
        val second = claim()
        assertTrue(second.uiState.value.state is ClaimState.Done)
        assertEquals("the completed claim consumed the first fee", 2, pays())
        assertEquals(null, json())
    }

    @Test
    fun `a custodial claim confirmed by the dashboard consumes its record and the next claim pays a new fee`() = runTest {
        custodial(ConfirmResultResponse(ok = false))
        val first = claim()
        assertEquals("CONFIRM_PENDING", failed(first).code)
        assertEquals(1, pays())

        custodial(ConfirmResultResponse(ok = true, txId = "CUSTODIALTX"))
        val second = claim()
        assertTrue(second.uiState.value.state is ClaimState.Done)
        assertEquals(2, pays())
    }

    // --- (c) legacy ---------------------------------------------------------------------------------------------------------

    @Test
    fun `a legacy fee record blocks the claim and is never reused`() = runTest {
        prefs.map[recordKey()] = legacyJson()
        val vm = claim()
        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(0, pays())
        assertTrue("no envelope is requested", rewards.envelopeCalls.isEmpty())
        assertEquals(legacyJson(), json())
    }

    @Test
    fun `a legacy fee record whose payment is proven dead is cleared and a new fee is paid`() = runTest {
        prefs.map[recordKey()] = legacyJson()
        statuses[payTxId] = TxStatus.Unknown
        verdicts[payTxId] = TxResolution.Absent
        val vm = claim()
        assertTrue(vm.uiState.value.state is ClaimState.Done)
        assertEquals(1, pays())
    }

    // --- (d) no burn loop ----------------------------------------------------------------------------------------------------

    @Test
    fun `repeated errors never pay a second fee, never clear the record and never run the claim again`() = runTest {
        custodial(ConfirmResultResponse(ok = false))
        val first = claim()
        assertEquals("CONFIRM_PENDING", failed(first).code)
        val before = json()
        assertNotNull(before)
        repeat(5) {
            val vm = claim()
            assertEquals("TXN_PENDING", failed(vm).code)
            assertEquals(1, pays())
            assertEquals("the record is untouched", before, json())
        }
        assertEquals("the claim was requested once", 1, rewards.envelopeCalls.size)
    }

    @Test
    fun `a custodial claim still in progress blocks with the pending copy and keeps its record`() = runTest {
        custodial(ConfirmResultResponse(ok = false))
        claim()
        val vm = claim()
        assertEquals("TXN_PENDING", failed(vm).code)
        assertTrue(failed(vm).message.contains("still processing"))
        assertNotNull(json())
        assertEquals(1, pays())
    }

    // --- rebind --------------------------------------------------------------------------------------------------------------

    private fun failedUserPaysClaim() {
        userPays("G1", leg1)
        rewards.confirmError = IOException("connection reset")
        claim()
        assertEquals(1, pays())
    }

    @Test
    fun `a claim leg proven absent rebinds the same paid fee to the new envelope - one fee in total`() = runTest {
        failedUserPaysClaim()
        verdicts[leg1Id] = TxResolution.Absent
        userPays("G2", leg2)
        val second = claim()
        assertEquals("the same fee is reused", 1, pays())
        val text = checkNotNull(json())
        assertTrue("bound to the new envelope: $text", text.contains("G2") && text.contains(leg2Id) && !text.contains(leg1Id))
        assertTrue("rebound once: $text", text.contains("\"rebound\":true"))
        assertEquals("the new confirm failed too", "NETWORK_ERROR", failed(second).code)
    }

    @Test
    fun `an error after a rebind blocks, keeps the record, pays nothing and never rebinds a second time`() = runTest {
        failedUserPaysClaim()
        verdicts[leg1Id] = TxResolution.Absent
        userPays("G2", leg2)
        claim()
        val bound = json()
        verdicts[leg2Id] = TxResolution.Absent
        userPays("G3", leg1)
        val third = claim()
        assertEquals("TXN_PENDING", failed(third).code)
        assertEquals(1, pays())
        assertEquals(bound, json())
        assertEquals("no third envelope", 2, rewards.envelopeCalls.size)
    }

    @Test
    fun `a claim leg that is not provably absent blocks the next claim`() = runTest {
        failedUserPaysClaim()
        for (verdict in listOf(TxResolution.Unknown)) {
            verdicts[leg1Id] = verdict
            val vm = claim()
            assertEquals("TXN_PENDING", failed(vm).code)
            assertEquals(1, pays())
        }
    }

    // --- binding is durable before anything is signed ---------------------------------------------------------------------

    @Test
    fun `a binding that cannot be committed means the group is not signed`() = runTest {
        failPut = true
        userPays("G1", leg1)
        val vm = claim()
        assertEquals("TXN_RECORD_FAILED", failed(vm).code)
        assertEquals(0, fake.signRequests.count { group -> (group.singleOrNull()?.size ?: 0) > 1 })
        assertTrue(rewards.confirmCalls.isEmpty())
    }

    // --- an envelope that was never signed or confirmed does not hold the fee hostage ---------------------------------------

    @Test
    fun `a rejected group signature leaves the fee reusable at once - one fee in total`() = runTest {
        userPays("G1", leg1)
        fake.signBehaviour = listOf(FakeWalletBridge.SignBehaviour.Approve, FakeWalletBridge.SignBehaviour.Reject, FakeWalletBridge.SignBehaviour.Approve)
        val first = claim()
        assertFalse(first.uiState.value.state is ClaimState.Done)
        assertTrue(rewards.confirmCalls.isEmpty())
        assertFalse("not bound after an unsigned envelope", checkNotNull(json()).contains("binding"))

        val second = claim()
        assertTrue(second.uiState.value.state is ClaimState.Done)
        assertEquals(1, pays())
    }
}
