package com.frynetworks.fryapp.ui.miners.claim

import com.frynetworks.fryapp.data.dashboard.model.ClaimEnvelopeResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimPreviewResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimTotalDto
import com.frynetworks.fryapp.data.dashboard.model.ConfirmResultResponse
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.FeeAwareRewardsRepository
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
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

/** 0.4.9 fee enforcement: the paid fee txid rides the envelope request; FEE_* rejections and terminal custodial answers never burn or re-pay a fee. */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaimFeeEnforceTest {

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

    private val backingRewards = FakeRewardsRepository()
    private val rewards = backingRewards
    /** The fee txid each envelope request was given (the 3-arg overload the view model must call). */
    private val envelopeFees = mutableListOf<String?>()
    private val recordingRewards: RewardsRepository = object : FeeAwareRewardsRepository, RewardsRepository by backingRewards {
        override suspend fun claimEnvelope(minerKey: String, no: Int?, feeTxId: String?): ClaimEnvelopeResponse {
            envelopeFees += feeTxId
            return backingRewards.claimEnvelope(minerKey, no)
        }
    }
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
        recordingRewards, algod, miners, bridge, session, fixedClock(now),
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

    private val feeCodes = listOf("FEE_INVALID" to 402, "FEE_REUSED" to 402, "FEE_IN_USE" to 402, "FEE_REQUIRED" to 402, "FEE_UNVERIFIABLE" to 503)

    private fun terminal(code: String = "CLAIM_EXPIRED") = ConfirmResultResponse(ok = null, success = false, code = code, terminal = true)

    @Test
    fun `the envelope request carries the paid fee txid`() = runTest {
        claim()
        assertEquals(listOf<String?>(payTxId), envelopeFees)
    }

    @Test
    fun `a rebound envelope request carries the same fee txid`() = runTest {
        userPays("G1", leg1)
        rewards.confirmError = IOException("connection reset")
        claim()
        verdicts[leg1Id] = TxResolution.Absent
        userPays("G2", leg2)
        claim()
        assertEquals(1, pays())
        assertEquals(listOf<String?>(payTxId, payTxId), envelopeFees)
    }

    @Test
    fun `every fee rejection blocks without Retry, keeps the record, pays nothing and the next attempt pays nothing`() = runTest {
        for ((code, status) in feeCodes) {
            prefs.map.clear()
            bindingPrefs.map.clear()
            envelopeFees.clear()
            fake.built.clear()
            rewards.envelopeError = DashboardException(code, "server says $code", httpStatus = status)
            val first = claim()
            val f = failed(first)
            assertEquals(code, f.code)
            assertFalse("$code must not offer Retry", f.recoverable)
            val shown = com.frynetworks.fryapp.ui.common.ErrorCopy.forCode(code, f.message)
            assertTrue("$code copy points to support: $shown", shown.contains("contact support", ignoreCase = true))
            assertEquals("$code: the fee was paid once", 1, pays())
            val record = json()
            assertNotNull(record)
            assertEquals("$code: no binding is written", null, bindingJson())

            val second = claim()
            assertEquals(code, failed(second).code)
            assertEquals("$code: no second fee", 1, pays())
            assertEquals("$code: record identical after the error and the next attempt", record, json())
            assertEquals("$code: the same fee is resent", listOf<String?>(payTxId, payTxId), envelopeFees)
        }
    }

    @Test
    fun `custodial terminal answers rebind the same fee once - exactly one fee over two claims`() = runTest {
        for (code in listOf("CLAIM_EXPIRED", "CLAIM_FAILED")) {
            prefs.map.clear()
            bindingPrefs.map.clear()
            fake.built.clear()
            envelopeFees.clear()
            rewards.custodialPolls = 0
            custodial(terminal(code))
            val first = claim()
            assertEquals(code, failed(first).code)
            assertEquals(1, rewards.custodialPolls)
            assertEquals(1, pays())
            assertNotNull("the binding is kept", bindingJson())

            val second = claim()
            assertEquals("$code: still one fee after the second claim", 1, pays())
            assertEquals(listOf<String?>(payTxId, payTxId), envelopeFees)
            assertTrue(checkNotNull(bindingJson()).contains("\"rebound\":true"))
            assertEquals(code, failed(second).code)
        }
    }

    @Test
    fun `a second terminal answer after the rebind blocks and never moves the fee again`() = runTest {
        custodial(terminal())
        claim()
        claim()
        val bound = json() to bindingJson()
        val third = claim()
        assertEquals("TXN_PENDING", failed(third).code)
        assertTrue(failed(third).message.contains("contact support", ignoreCase = true))
        assertEquals(1, pays())
        assertEquals(bound, json() to bindingJson())
        assertEquals("no envelope for the blocked claim", 2, rewards.envelopeCalls.size)
    }

    @Test
    fun `a custodial confirm that is not terminal keeps polling and then blocks the next claim as before`() = runTest {
        val notTerminal = listOf(
            ConfirmResultResponse(ok = false, code = "CLAIM_EXPIRED", terminal = false),
            ConfirmResultResponse(ok = false, code = "CLAIM_EXPIRED"),
            ConfirmResultResponse(ok = false, code = "NETWORK_ERROR", terminal = true),
            ConfirmResultResponse(ok = false, code = "SOMETHING_ELSE", terminal = true),
            ConfirmResultResponse(ok = false, code = "NETWORK_ERROR"),
        )
        for (answer in notTerminal) {
            prefs.map.clear()
            bindingPrefs.map.clear()
            fake.built.clear()
            envelopeFees.clear()
            rewards.envelopeCalls.clear()
            rewards.custodialPolls = 0
            custodial(answer)
            val first = claim()
            assertEquals("$answer", "CONFIRM_PENDING", failed(first).code)
            assertEquals("$answer polled all six times", ClaimViewModel.CUSTODIAL_POLL_ATTEMPTS, rewards.custodialPolls)
            val second = claim()
            assertEquals("$answer", "TXN_PENDING", failed(second).code)
            assertEquals("$answer", 1, pays())
            assertEquals("$answer: no second envelope", 1, rewards.envelopeCalls.size)
        }
    }

    @Test
    fun `a custodial confirm that throws is not terminal`() = runTest {
        custodial(ConfirmResultResponse(ok = false))
        rewards.custodialError = IOException("down")
        val first = claim()
        assertEquals("NETWORK_ERROR", failed(first).code)
        val second = claim()
        assertEquals("TXN_PENDING", failed(second).code)
        assertEquals(1, pays())
        assertEquals(1, rewards.envelopeCalls.size)
    }
}
