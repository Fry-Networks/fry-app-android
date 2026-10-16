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
import com.frynetworks.fryapp.data.pending.ClaimFeeBinding
import com.frynetworks.fryapp.data.pending.ClaimFeeBindingStore
import com.frynetworks.fryapp.data.pending.PendingTransfer
import com.frynetworks.fryapp.data.pending.PendingTransferStore
import com.frynetworks.fryapp.data.pending.PendingTransferStoreTest
import com.frynetworks.fryapp.data.pending.SharedPrefsClaimFeeBindingStore
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
import com.frynetworks.fryapp.util.SignedTxnReader
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
 * The dashboard sends the user leg as a BARE unsigned transaction (no sig wrapper). The binding must still learn its id and last valid
 * round, and a claim that cannot be bound is refused before anything is signed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaimBareLegTest {

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
    private val bindingPrefs = PendingTransferStoreTest.FakePrefs()
    private val bindingReal = SharedPrefsClaimFeeBindingStore(bindingPrefs)
    private var failBindingPut = false
    private var failBindingRemove = false
    private val bindings: ClaimFeeBindingStore = object : ClaimFeeBindingStore by bindingReal {
        override fun put(sender: String, minerKey: String, row: String, binding: ClaimFeeBinding) =
            if (failBindingPut) false else bindingReal.put(sender, minerKey, row, binding)

        override fun remove(sender: String, minerKey: String, row: String) =
            if (failBindingRemove) false else bindingReal.remove(sender, minerKey, row)
    }
    private val store: PendingTransferStore = object : PendingTransferStore by real {
        override fun remove(sender: String, kind: com.frynetworks.fryapp.data.pending.TransferKind, minerKey: String, row: String) =
            if (failRemove) false else real.remove(sender, kind, minerKey, row)
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
    private var decodedId: String? = null
    private val bridge: WalletBridge = object : WalletBridge by fake {
        override suspend fun decodeTxn(txnB64: String) = fake.decodeTxn(txnB64).copy(txId = decodedId)

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
        bindings = bindings, bindingDispatcher = Dispatchers.Unconfined,
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

    private fun bindingJson(): String? = bindingPrefs.map[recordKey()] as String?

    private fun legacyJson() =
        """{"schemaVersion":1,"kind":"CLAIM_FEE","sender":"$TEST_ADDRESS","minerKey":"$key","row":"all","assetId":0,"amount":"${StakeRepository.CLAIM_FEE_MICROALGO}",""" +
            """"amountMicro":${StakeRepository.CLAIM_FEE_MICROALGO},"receiver":"${StakeRepository.CLAIM_FEE_WALLET}","txId":"$payTxId","fv":66029053,""" +
            """"lv":$payLastValid,"builtRound":1000,"state":"PRE_SUBMIT","createdAt":$now}"""

    private fun bare(signed: String): String = java.util.Base64.getEncoder().encodeToString(checkNotNull(SignedTxnReader.parse(signed)).rawTxn)

    private fun failedBareClaim() {
        userPays("G1", bare(leg1))
        rewards.confirmError = IOException("connection reset")
        claim()
        assertEquals(1, pays())
    }

    @Test
    fun `a bare leg is bound by its own id and rebinds on Absent - one fee in total`() = runTest {
        failedBareClaim()
        val text1 = checkNotNull(bindingJson())
        assertTrue("bound to the bare leg's id: $text1", text1.contains(leg1Id))
        verdicts[leg1Id] = TxResolution.Absent
        userPays("G2", bare(leg2))
        claim()
        assertEquals("the same fee is reused", 1, pays())
        val text = checkNotNull(bindingJson())
        assertTrue("bound to the new envelope: $text", text.contains("G2") && text.contains(leg2Id) && text.contains("\"rebound\":true"))
    }

    @Test
    fun `a binding whose last valid round is unknown never rebinds`() = runTest {
        decodedId = "DECODEDID"
        userPays("G1", "not-a-transaction")
        rewards.confirmError = IOException("connection reset")
        claim()
        assertTrue(checkNotNull(bindingJson()).contains("DECODEDID"))
        verdicts["DECODEDID"] = TxResolution.Absent
        val vm = claim()
        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(1, pays())
        assertEquals("no second envelope", 1, rewards.envelopeCalls.size)
    }

    @Test
    fun `a leg whose id cannot be computed fails before signing and persists no binding`() = runTest {
        userPays("G1", "not-a-transaction")
        val vm = claim()
        assertEquals("INVALID_ENVELOPE", failed(vm).code)
        assertEquals(0, fake.signRequests.count { group -> (group.singleOrNull()?.size ?: 0) > 1 })
        assertTrue(rewards.confirmCalls.isEmpty())
        assertEquals(null, bindingJson())
        userPays("G2", bare(leg2))
        assertTrue(claim().uiState.value.state is ClaimState.Done)
        assertEquals("the paid fee was reused", 1, pays())
    }

    @Test
    fun `an envelope with no user leg fails before signing and persists no binding`() = runTest {
        userPays("G1", "")
        val vm = claim()
        assertEquals("INVALID_ENVELOPE", failed(vm).code)
        assertEquals(0, fake.signRequests.size)
        assertTrue(rewards.confirmCalls.isEmpty())
        assertEquals(null, bindingJson())
    }

    @Test
    fun `a terminal block after a second Absent gives the support copy`() = runTest {
        failedBareClaim()
        verdicts[leg1Id] = TxResolution.Absent
        userPays("G2", bare(leg2))
        claim()
        verdicts[leg2Id] = TxResolution.Absent
        val vm = claim()
        assertEquals("TXN_PENDING", failed(vm).code)
        assertTrue(failed(vm).message, failed(vm).message.contains("contact support"))
    }

    @Test
    fun `a legacy fee record that was confirmed gives the support copy`() = runTest {
        prefs.map[recordKey()] = legacyJson()
        val vm = claim()
        assertEquals("TXN_PENDING", failed(vm).code)
        assertTrue(failed(vm).message, failed(vm).message.contains("contact support"))
    }
}
