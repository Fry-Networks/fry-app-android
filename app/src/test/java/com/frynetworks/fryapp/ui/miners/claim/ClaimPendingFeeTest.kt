package com.frynetworks.fryapp.ui.miners.claim

import com.frynetworks.fryapp.auth.SessionProfile
import com.frynetworks.fryapp.data.dashboard.model.ClaimEnvelopeResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimPreviewResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimTotalDto
import com.frynetworks.fryapp.data.dashboard.model.ConfirmResultResponse
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.StakeRepository
import com.frynetworks.fryapp.data.dashboard.repo.TxResolution
import com.frynetworks.fryapp.data.dashboard.repo.TxResolver
import com.frynetworks.fryapp.data.dashboard.repo.TxStatus
import com.frynetworks.fryapp.data.pending.InMemoryPendingTransferStore
import com.frynetworks.fryapp.data.pending.PendingLookup
import com.frynetworks.fryapp.data.pending.PendingTransfer
import com.frynetworks.fryapp.data.pending.PendingTransferStore
import com.frynetworks.fryapp.data.pending.PendingTransferStoreTest
import com.frynetworks.fryapp.data.pending.SharedPrefsPendingTransferStore
import com.frynetworks.fryapp.data.pending.TransferKind
import com.frynetworks.fryapp.data.pending.TransferState
import com.frynetworks.fryapp.domain.ClaimState
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.fakes.FakeAlgodRepository
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.FakeRewardsRepository
import com.frynetworks.fryapp.fakes.FakeWalletBridge
import com.frynetworks.fryapp.fakes.OTHER_ADDRESS
import com.frynetworks.fryapp.fakes.TEST_ADDRESS
import com.frynetworks.fryapp.fakes.TestSession
import com.frynetworks.fryapp.fakes.fixedClock
import com.frynetworks.fryapp.wallet.TxnSummary
import com.frynetworks.fryapp.wallet.TxnToSign
import com.frynetworks.fryapp.wallet.WalletBridge
import com.frynetworks.fryapp.wallet.WalletVendor
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
 * The claim fee follows the stake pattern: recorded before submit, resumed from its txid by Retry, reopening the sheet, a new
 * process, a logout and the same sender coming back; paid again only when the resolver proves the first payment dead. The signed
 * blob is a real algosdk 3.7.0 payment (see SignedTxnReaderTest), so its txid and last valid round come from its own bytes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaimPendingFeeTest {

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val asset = FryAsset.FNODE
    private val now = 1_757_592_000_000L
    private val paySigned = "gqNzaWfEQJ3tQu0YfcH7nHqxFeCRswiuwanJNK7WsUFubIbmoqnNo0JpuyJ9kOllpU0pLNaTKBfHdKP5Qqae3qFZ6QKStAOjdHhuiaNhbXTNA+ijZmVlzQPoomZ2zgPvhf2jZ2VurG1haW5uZXQtdjEuMKJnaMQgwGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit+ibHbOA++J5aNyY3bEIP0XJDhaoMdbZPt4zWAvodmR/ev3axPFjtcC6sg16fYYo3NuZMQg6kpsY+KcUgq+9VB7Ey7F+ZVHdq6+vnuSQh7qaRRG0iykdHlwZaNwYXk="
    private val payTxId = "3TMZOQPUKWBLEVC2CQZCDUFS2O2KTDD3MHNC5JGZ4UWCF442SFWA"
    private val payLastValid = 66_030_053L

    private val rewards = FakeRewardsRepository()
    private val backing = FakeAlgodRepository()
    private val miners = FakeMinerRepository()
    private val fake = FakeWalletBridge()
    private var store: PendingTransferStore = InMemoryPendingTransferStore()
    private var session = TestSession.signedIn()

    private var status: TxStatus = TxStatus.Unknown
    private var signedBlob: String? = null
    private var decodedTxId: String? = null
    private var throwOnFeeSubmit: Throwable? = null
    private var submitReturns: List<String>? = null
    private var onSubmit: () -> Unit = {}
    private var cancelAfterSubmit = false
    private var feeSigned = false
    private var onGroupSign: () -> Unit = {}
    private var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined

    private class ScriptedResolver : TxResolver {
        var verdict: TxResolution = TxResolution.Unknown
        var findVerdict: TxResolution = TxResolution.Unknown
        val resolveCalls = mutableListOf<Pair<String, Long?>>()
        val findCalls = mutableListOf<List<Any?>>()

        override suspend fun resolve(txId: String, lastValid: Long?): TxResolution {
            resolveCalls += txId to lastValid
            return verdict
        }

        override suspend fun findTransfer(sender: String, receiver: String, assetId: Long, amountMicro: Long, minRound: Long?): TxResolution {
            findCalls += listOf(sender, receiver, assetId, amountMicro, minRound)
            return findVerdict
        }
    }

    private val resolver = ScriptedResolver()

    private val algod = object : AlgodRepository by backing {
        override suspend fun txStatus(txId: String): TxStatus = status
        override suspend fun currentRound(): Long? = 1_000L
    }

    private val bridge: WalletBridge = object : WalletBridge by fake {
        override suspend fun decodeTxn(txnB64: String): TxnSummary {
            val summary = fake.decodeTxn(txnB64)
            return if (summary.type == "pay") summary.copy(txId = decodedTxId) else summary
        }

        override suspend fun signTxns(groups: List<List<TxnToSign>>): List<List<String?>> {
            val result = fake.signTxns(groups)
            if ((groups.singleOrNull()?.size ?: 0) > 1) onGroupSign()
            val blob = signedBlob
            // only the fee payment (the first signature of a claim) carries the real bytes
            if (blob == null || feeSigned || groups.singleOrNull()?.singleOrNull() == null) return result
            feeSigned = true
            return result.map { g -> g.map { if (it != null) blob else null } }
        }

        override suspend fun submit(signedB64: List<String>, waitRounds: Int): List<String> {
            val result = submitInner(signedB64, waitRounds)
            if (cancelAfterSubmit) kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            return result
        }

        private suspend fun submitInner(signedB64: List<String>, waitRounds: Int): List<String> {
            onSubmit()
            throwOnFeeSubmit?.let {
                fake.submitted += signedB64
                throw it
            }
            val ids = submitReturns ?: return fake.submit(signedB64, waitRounds)
            fake.submitted += signedB64
            return ids
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fake.reconnectAddress = TEST_ADDRESS
        fake.submitTxIds = listOf(payTxId)
        backing.markOptedIn(asset.id)
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

    private fun vm(s: com.frynetworks.fryapp.auth.SessionRepository = session) = ClaimViewModel(
        rewards, algod, miners, bridge, s, fixedClock(now),
        openUri = {}, pollDelayMillis = 0, optInRetryDelayMillis = 0, store = store, resolver = resolver, ioDispatcher = ioDispatcher,
    )

    private fun pays() = fake.built.count { it.startsWith("pay|") }

    private fun failed(vm: ClaimViewModel) = vm.uiState.value.state as ClaimState.Failed

    /** What a new process would read from [prefs]: a downgraded record is blocked in memory in this one, so [record] cannot see it. */
    private fun persistedRecord(prefs: PendingTransferStoreTest.FakePrefs) =
        (SharedPrefsPendingTransferStore(prefs).get(TEST_ADDRESS, TransferKind.CLAIM_FEE, key, "all") as? PendingLookup.Found)?.record

    private fun record(sender: String = TEST_ADDRESS, row: String = "all") =
        (store.get(sender, TransferKind.CLAIM_FEE, key, row) as? PendingLookup.Found)?.record

    /** Opens the sheet, confirms the preview and (with a failing fee submit) ends on a failed fee. */
    private fun payAndFail(vm: ClaimViewModel = vm()): ClaimViewModel {
        signedBlob = paySigned
        throwOnFeeSubmit = IOException("connection reset")
        vm.start(key)
        vm.confirm()
        throwOnFeeSubmit = null
        return vm
    }

    private fun reopenAndConfirm(vm: ClaimViewModel) {
        vm.retry()
        vm.confirm()
    }

    // --- recorded before submit -------------------------------------------------------------------------------------------

    @Test
    fun `the fee record is in the store, with the real txid and rounds, before the fee is submitted`() = runTest {
        signedBlob = paySigned
        var seen: PendingTransfer? = null
        onSubmit = { seen = record() }
        vm().also { it.start(key); it.confirm() }
        val r = seen
        assertTrue("no record at submit time", r != null)
        assertEquals(payTxId, r!!.txId)
        assertEquals(payLastValid, r.lastValid)
        assertEquals(66_029_053L, r.firstValid)
        assertEquals(TransferKind.CLAIM_FEE, r.kind)
        assertEquals(TEST_ADDRESS, r.sender)
        assertEquals(StakeRepository.CLAIM_FEE_WALLET, r.receiver)
        assertEquals(StakeRepository.CLAIM_FEE_MICROALGO, r.amountMicro)
        assertEquals(0L, r.assetId)
        assertEquals(TransferState.PRE_SUBMIT, r.state)
    }

    @Test
    fun `a failed commit of the fee record means the fee is not submitted and the envelope is not requested`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs().also { it.commitResult = false }
        store = SharedPrefsPendingTransferStore(prefs)
        signedBlob = paySigned
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertTrue(fake.submitted.isEmpty())
        assertTrue(rewards.envelopeCalls.isEmpty())
        assertEquals("TXN_RECORD_FAILED", failed(vm).code)
    }

    @Test
    fun `a fee record with an unknown schema version blocks the fee and the envelope`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs()
        store = SharedPrefsPendingTransferStore(prefs)
        payAndFail()
        val k = prefs.map.keys.single()
        prefs.map[k] = (prefs.map[k] as String).replace("\"schemaVersion\":1", "\"schemaVersion\":9")

        val vm2 = vm()
        vm2.start(key)
        vm2.confirm()

        assertEquals("TXN_PENDING", failed(vm2).code)
        assertEquals(1, pays())
        assertTrue(rewards.envelopeCalls.isEmpty())
    }

    // --- resume and retry never pay again ----------------------------------------------------------------------------------

    @Test
    fun `retry after a fee submit that failed resumes the recorded payment and never pays again`() = runTest {
        val vm = payAndFail()
        status = TxStatus.Confirmed(66_029_500)

        reopenAndConfirm(vm)

        assertTrue(vm.uiState.value.state is ClaimState.Done)
        assertEquals(1, pays())
        assertEquals(1, fake.submitted.size)
        assertEquals(1, rewards.envelopeCalls.size)
        assertNull(record())
    }

    @Test
    fun `reopening the sheet with resume after a fee submit that failed never pays again`() = runTest {
        val vm = payAndFail()
        status = TxStatus.Confirmed(66_029_500)

        vm.resume(key, null)
        vm.confirm()

        assertTrue(vm.uiState.value.state is ClaimState.Done)
        assertEquals(1, pays())
        assertEquals(1, fake.submitted.size)
    }

    @Test
    fun `a new view model over the same store after process death never pays again`() = runTest {
        payAndFail()
        status = TxStatus.Confirmed(66_029_500)

        val reborn = vm()
        reborn.start(key)
        reborn.confirm()

        assertTrue(reborn.uiState.value.state is ClaimState.Done)
        assertEquals(1, pays())
        assertEquals(1, fake.submitted.size)
    }

    @Test
    fun `logging out and back in as the same sender never pays the fee again`() = runTest {
        payAndFail()
        session.clear()
        session.markSignedIn(SessionProfile(TEST_ADDRESS, vendor = WalletVendor.PERA), fingerprintBound = true)
        assertTrue(record() != null)
        status = TxStatus.Confirmed(66_029_500)

        val again = vm()
        again.start(key)
        again.confirm()

        assertTrue(again.uiState.value.state is ClaimState.Done)
        assertEquals(1, pays())
    }

    @Test
    fun `a fee payment still in the pool is waited for, not paid again`() = runTest {
        val vm = payAndFail()
        status = TxStatus.InPool

        reopenAndConfirm(vm)

        assertEquals(listOf(payTxId), backing.waited)
        assertEquals(1, pays())
        assertTrue(vm.uiState.value.state is ClaimState.Done)
    }

    @Test
    fun `a verdict that the indexers have the payment resumes without paying when algod does not know it`() = runTest {
        val vm = payAndFail()
        status = TxStatus.Unknown
        resolver.verdict = TxResolution.Confirmed(payTxId, 66_029_600)

        reopenAndConfirm(vm)

        assertTrue(vm.uiState.value.state is ClaimState.Done)
        assertEquals(1, pays())
    }

    @Test
    fun `an unknown verdict never pays again and never requests the envelope`() = runTest {
        val vm = payAndFail()
        for (s in listOf(TxStatus.Absent, TxStatus.Unknown, TxStatus.Rejected("x"))) {
            status = s
            reopenAndConfirm(vm)
            assertEquals("TXN_PENDING", failed(vm).code)
        }
        assertEquals(1, pays())
        assertTrue(rewards.envelopeCalls.isEmpty())
        assertTrue(record() != null)
    }

    // --- paying again only on proof of death --------------------------------------------------------------------------------

    @Test
    fun `an Absent verdict clears the record and only then pays the fee again`() = runTest {
        val vm = payAndFail()
        status = TxStatus.Absent
        resolver.verdict = TxResolution.Absent
        signedBlob = paySigned
        feeSigned = false

        reopenAndConfirm(vm)

        assertEquals(payTxId to payLastValid, resolver.resolveCalls.last())
        assertEquals(2, pays())
        assertTrue(vm.uiState.value.state is ClaimState.Done)
    }

    @Test
    fun `an Absent verdict whose record cannot be cleared pays nothing`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs()
        store = SharedPrefsPendingTransferStore(prefs)
        val vm = payAndFail()
        resolver.verdict = TxResolution.Absent
        prefs.commitResult = false

        reopenAndConfirm(vm)

        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(1, pays())
        assertTrue(record() != null)
    }

    // --- an id that could not be read from the signed bytes -----------------------------------------------------------------

    @Test
    fun `an unreadable blob falls back to the decoded txid with no last valid round, and Absent is then ignored`() = runTest {
        decodedTxId = "DECODEDTX"
        throwOnFeeSubmit = IOException("connection reset")
        val vm = vm()
        vm.start(key)
        vm.confirm()
        throwOnFeeSubmit = null
        val r = record()!!
        assertEquals("DECODEDTX", r.txId)
        assertNull(r.lastValid)

        resolver.verdict = TxResolution.Absent
        status = TxStatus.Absent
        reopenAndConfirm(vm)

        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(1, pays())
        assertTrue(record() != null)
    }

    @Test
    fun `a submit that returns another txid than the signed bytes give blocks the claim`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs()
        store = SharedPrefsPendingTransferStore(prefs)
        signedBlob = paySigned
        submitReturns = listOf("SOMETHINGELSE")
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(TransferState.POSSIBLY_BROADCAST, persistedRecord(prefs)!!.state)
        assertTrue(rewards.envelopeCalls.isEmpty())
        resolver.verdict = TxResolution.Absent
        submitReturns = null
        reopenAndConfirm(vm)
        assertEquals(1, pays())
    }

    @Test
    fun `a fee with no readable or decoded id is possibly broadcast, blocks, and is resolved only by the account search`() = runTest {
        throwOnFeeSubmit = IOException("connection reset")
        val vm = vm()
        vm.start(key)
        vm.confirm()
        throwOnFeeSubmit = null
        val r = record()!!
        assertEquals(TransferState.POSSIBLY_BROADCAST, r.state)
        assertNull(r.txId)
        resolver.verdict = TxResolution.Absent

        reopenAndConfirm(vm)
        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(1, pays())

        resolver.findVerdict = TxResolution.Confirmed("FOUNDFEE", null)
        status = TxStatus.Confirmed(66_029_500)
        reopenAndConfirm(vm)

        assertEquals(listOf<Any?>(TEST_ADDRESS, StakeRepository.CLAIM_FEE_WALLET, 0L, StakeRepository.CLAIM_FEE_MICROALGO, 1_000L), resolver.findCalls.last())
        assertTrue(vm.uiState.value.state is ClaimState.Done)
        assertEquals(1, pays())
    }

    // --- accounts and rows ------------------------------------------------------------------------------------------------

    @Test
    fun `a fee record of one sender does not block another sender`() = runTest {
        payAndFail()
        signedBlob = null
        fake.reconnectAddress = OTHER_ADDRESS

        val other = vm(TestSession.signedIn(address = OTHER_ADDRESS))
        other.start(key)
        other.confirm()

        assertTrue(other.uiState.value.state is ClaimState.Done)
        assertEquals(2, pays())
        assertTrue(record(TEST_ADDRESS) != null)
    }

    @Test
    fun `a fee record of one reward row does not block another row`() = runTest {
        signedBlob = paySigned
        throwOnFeeSubmit = IOException("connection reset")
        val vm = vm()
        vm.start(key, no = 3)
        vm.confirm()
        throwOnFeeSubmit = null
        signedBlob = null
        assertTrue(record(row = "3") != null)

        vm.start(key, no = 4)
        vm.confirm()

        assertTrue(vm.uiState.value.state is ClaimState.Done)
        assertEquals(2, pays())
    }

    @Test
    fun `the session clearing leaves the fee record intact`() = runTest {
        payAndFail()
        session.clear()
        assertTrue(record() != null)
    }

    // --- the record outlives failures until the claim completes ----------------------------------------------------------------

    @Test
    fun `a failing envelope request keeps the record, and the next attempt does not pay again`() = runTest {
        status = TxStatus.Confirmed(66_029_500)
        signedBlob = paySigned
        rewards.envelopeError = com.frynetworks.fryapp.network.dashboard.DashboardException("SERVER", "down", httpStatus = 500)
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertTrue(record() != null)
        assertEquals(1, pays())

        rewards.envelopeError = null
        reopenAndConfirm(vm)

        assertTrue(vm.uiState.value.state is ClaimState.Done)
        assertEquals(1, pays())
        assertNull(record())
    }

    @Test
    fun `a completed claim clears the fee record`() = runTest {
        signedBlob = paySigned
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertTrue(vm.uiState.value.state is ClaimState.Done)
        assertNull(record())
        assertEquals(1, pays())
    }

    // --- round 2 ------------------------------------------------------------------------------------------------------------

    private fun seeded(
        txId: String? = payTxId,
        createdAt: Long = now,
        lastValid: Long? = payLastValid,
        state: TransferState = TransferState.PRE_SUBMIT,
        row: String = "all",
    ): PendingTransfer {
        val r = PendingTransfer(
            kind = TransferKind.CLAIM_FEE, sender = TEST_ADDRESS, minerKey = key, row = row, assetId = 0L,
            amount = StakeRepository.CLAIM_FEE_MICROALGO.toString(), amountMicro = StakeRepository.CLAIM_FEE_MICROALGO,
            receiver = StakeRepository.CLAIM_FEE_WALLET, txId = txId, firstValid = 66_029_053L, lastValid = lastValid, builtRound = 1_000L,
            state = state, createdAt = createdAt,
        )
        assertTrue(store.put(r))
        return r
    }

    private fun confirmedFeeThenFail(configure: () -> Unit): ClaimViewModel {
        status = TxStatus.Confirmed(66_029_500)
        signedBlob = paySigned
        configure()
        val vm = vm()
        vm.start(key)
        vm.confirm()
        return vm
    }

    private fun permanent(code: String, status: Int) = com.frynetworks.fryapp.network.dashboard.DashboardException(code, code, httpStatus = status)

    @Test
    fun `a confirmed fee is kept and reused when the dashboard keeps saying a claim is already in progress`() = runTest {
        status = TxStatus.Confirmed(66_029_500)
        signedBlob = paySigned
        rewards.envelopeError = permanent("REWARD_ALREADY_CLAIMING", 409)
        repeat(3) {
            val vm = vm()
            vm.start(key)
            vm.confirm()
            assertEquals("REWARD_ALREADY_CLAIMING", failed(vm).code)
            assertTrue(record() != null)
        }
        assertEquals(1, pays())
        assertEquals(1, fake.submitted.size)
    }

    @Test
    fun `a confirmed fee is kept when the claim group expired before it was confirmed`() = runTest {
        confirmedFeeThenFail { rewards.confirmError = permanent("CLAIM_GROUP_EXPIRED", 410) }
        assertTrue(record() != null)
        assertEquals(1, pays())
    }

    @Test
    fun `a confirmed fee is kept when the dashboard reports no claimable rewards at the envelope`() = runTest {
        confirmedFeeThenFail { rewards.envelopeError = permanent("NO_REWARDS", 400) }
        assertTrue(record() != null)
        assertEquals(1, pays())
    }

    @Test
    fun `a transient envelope failure keeps the confirmed fee`() = runTest {
        confirmedFeeThenFail { rewards.envelopeError = com.frynetworks.fryapp.network.dashboard.DashboardException("SERVER", "down", httpStatus = 500) }
        assertTrue(record() != null)
    }

    @Test
    fun `a fee whose payment may still be in flight is never cleared by a permanent failure`() = runTest {
        status = TxStatus.Unknown // never confirmed as far as the node can say
        signedBlob = paySigned
        rewards.envelopeError = com.frynetworks.fryapp.network.dashboard.DashboardException("REWARD_ALREADY_CLAIMING", "in flight", httpStatus = 409)
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertTrue(record() != null)
        status = TxStatus.InPool
        vm.retry()
        vm.confirm()
        assertTrue(record() != null)
    }

    @Test
    fun `a confirmed fee older than 30 minutes is reused and no new fee is paid`() = runTest {
        for (age in listOf(31 * 60_000L, 3 * 60 * 60_000L)) {
            store = InMemoryPendingTransferStore()
            seeded(createdAt = now - age)
            status = TxStatus.Confirmed(66_029_500)
            fake.built.clear()
            val vm = vm()
            vm.start(key)
            vm.confirm()
            assertEquals(0, pays())
            assertTrue(vm.uiState.value.state is ClaimState.Done)
            assertNull("cleared by the success", record())
        }
    }

    @Test
    fun `after a claim succeeds its fee record is cleared and the next claim pays a new fee`() = runTest {
        status = TxStatus.Confirmed(66_029_500)
        signedBlob = paySigned
        val first = vm()
        first.start(key)
        first.confirm()
        assertTrue(first.uiState.value.state is ClaimState.Done)
        assertNull(record())
        assertEquals(1, pays())
        val second = vm()
        second.start(key)
        second.confirm()
        assertEquals(2, pays())
    }

    @Test
    fun `a confirmed fee inside the window is reused and not paid again`() = runTest {
        seeded(createdAt = now - 20 * 60_000L)
        status = TxStatus.Confirmed(66_029_500)
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertEquals(0, pays())
        assertTrue(vm.uiState.value.state is ClaimState.Done)
    }

    @Test
    fun `an old fee whose payment is not confirmed is never cleared by the window`() = runTest {
        seeded(createdAt = now - 3 * 60 * 60_000L)
        status = TxStatus.Unknown
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(0, pays())
        assertTrue(record() != null)
        status = TxStatus.InPool
        backing.waitError = com.frynetworks.fryapp.network.dashboard.DashboardException("TXN_NOT_CONFIRMED", "slow")
        vm.retry()
        vm.confirm()
        assertEquals(0, pays())
        assertTrue(record() != null)
    }

    @Test
    fun `the record is cleared with the sender captured at the start even when the session is gone by then`() = runTest {
        status = TxStatus.Confirmed(66_029_500)
        signedBlob = paySigned
        onGroupSign = { session.clear() }
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertTrue(vm.uiState.value.state is ClaimState.Done)
        assertNull(record())
    }

    @Test
    fun `a blank txid from submit is no id and never reaches the envelope`() = runTest {
        for (blank in listOf("", "  ")) {
            store = InMemoryPendingTransferStore()
            fake.built.clear()
            rewards.envelopeCalls.clear()
            submitReturns = listOf(blank)
            val vm = vm()
            vm.start(key)
            vm.confirm()
            val r = record()!!
            assertEquals(TransferState.POSSIBLY_BROADCAST, r.state)
            assertNull(r.txId)
            assertEquals("TXN_PENDING", failed(vm).code)
            assertTrue(rewards.envelopeCalls.isEmpty())
        }
    }

    @Test
    fun `a blank txid from submit never replaces the id the bytes gave`() = runTest {
        signedBlob = paySigned
        submitReturns = listOf("")
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertEquals(payTxId, record()!!.txId)
        assertTrue(rewards.envelopeCalls.isEmpty())
    }

    @Test
    fun `a blank decoded txid is no id`() = runTest {
        decodedTxId = ""
        throwOnFeeSubmit = IOException("connection reset")
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertNull(record()!!.txId)
        assertEquals(TransferState.POSSIBLY_BROADCAST, record()!!.state)
    }

    @Test
    fun `a fee that may have been sent says it is still pending and to contact support`() = runTest {
        throwOnFeeSubmit = IOException("connection reset")
        val vm = vm()
        vm.start(key)
        vm.confirm()
        val message = failed(vm).message
        assertTrue(message, message.contains("still pending", ignoreCase = true) && message.contains("contact support", ignoreCase = true))
        throwOnFeeSubmit = null
        reopenAndConfirm(vm)
        assertTrue(failed(vm).message.contains("contact support", ignoreCase = true))
    }

    @Test
    fun `when the mismatch downgrade cannot be written the fee key is blocked for the process`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs()
        store = SharedPrefsPendingTransferStore(prefs)
        signedBlob = paySigned
        submitReturns = listOf("SOMETHINGELSE")
        onSubmit = { prefs.commitResult = false }
        val vm = vm()
        vm.start(key)
        vm.confirm()
        prefs.commitResult = true
        assertEquals("TXN_PENDING", failed(vm).code)
        resolver.verdict = TxResolution.Absent
        status = TxStatus.Absent
        submitReturns = null
        reopenAndConfirm(vm)
        assertEquals(1, pays())

        val reborn = vm()
        reborn.start(key)
        reborn.confirm()
        assertEquals(1, pays())
        assertEquals(1, fake.submitted.size)
        assertTrue(failed(reborn).message.contains("contact support", ignoreCase = true))
    }

    @Test
    fun `the fee downgrade write is retried once`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs()
        store = SharedPrefsPendingTransferStore(prefs)
        signedBlob = paySigned
        submitReturns = listOf("SOMETHINGELSE")
        onSubmit = { prefs.failCommits = 1 }
        vm().also { it.start(key); it.confirm() }
        assertEquals(TransferState.POSSIBLY_BROADCAST, persistedRecord(prefs)!!.state)
        assertNull(persistedRecord(prefs)!!.txId)
    }

    @Test
    fun `a fee record that appears between the check and the write is never overwritten and nothing is submitted`() = runTest {
        val rival = seeded(txId = "RIVALTX")
        store.remove(TEST_ADDRESS, TransferKind.CLAIM_FEE, key, "all")
        val inner = InMemoryPendingTransferStore()
        store = object : PendingTransferStore by inner {
            override fun putNew(record: PendingTransfer): Boolean {
                inner.put(rival)
                return inner.putNew(record)
            }
        }
        signedBlob = paySigned
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertTrue(fake.submitted.isEmpty())
        assertEquals("RIVALTX", record()!!.txId)
        assertEquals("TXN_RECORD_FAILED", failed(vm).code)
    }

    /** Runs every block inline, noting that it is inside the dispatcher while it runs. */
    private class CountingDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        var dispatched = 0
        var inside = 0
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            dispatched++
            inside++
            try {
                block.run()
            } finally {
                inside--
            }
        }
    }

    /** Counts every store method call and notes any made outside the dispatcher. */
    private class AuditedStore(private val inner: PendingTransferStore, private val dispatcher: CountingDispatcher) : PendingTransferStore {
        val calls = sortedMapOf<String, Int>()
        val outside = mutableListOf<String>()
        private fun <T> note(name: String, block: () -> T): T {
            calls.merge(name, 1, Int::plus)
            if (dispatcher.inside == 0) outside += name
            return block()
        }
        override fun find(sender: String, kind: TransferKind) = note("find") { inner.find(sender, kind) }
        override fun get(sender: String, kind: TransferKind, minerKey: String, row: String) = note("get") { inner.get(sender, kind, minerKey, row) }
        override fun put(record: PendingTransfer) = note("put") { inner.put(record) }
        override fun putNew(record: PendingTransfer) = note("putNew") { inner.putNew(record) }
        override fun remove(sender: String, kind: TransferKind, minerKey: String, row: String) = note("remove") { inner.remove(sender, kind, minerKey, row) }
        override fun blockPermanently(sender: String, kind: TransferKind, minerKey: String, row: String) =
            note("blockPermanently") { inner.blockPermanently(sender, kind, minerKey, row) }
    }

    @Test
    fun `every store method call of a claim goes through the injected IO dispatcher`() = runTest {
        val counting = CountingDispatcher()
        val audited = AuditedStore(store, counting)
        store = audited
        ioDispatcher = counting
        signedBlob = paySigned
        val vm = vm()
        vm.start(key)
        vm.confirm()
        assertTrue(vm.uiState.value.state is ClaimState.Done)
        assertEquals("calls outside the dispatcher: ${audited.outside}", emptyList<String>(), audited.outside)
        // the prior-fee lookup, the first write, the clear after the claim; nothing else
        assertEquals(mapOf("get" to 1, "putNew" to 1, "remove" to 1), audited.calls.toMap())
        assertEquals("every call is one dispatch", 3, counting.dispatched)
    }

    // --- cancellation after the payment may be on the network: the store writes still complete -------------------------------

    @Test
    fun `a job cancelled right after submit returns still records the id of an unidentified fee payment`() = runTest {
        signedBlob = null
        decodedTxId = null
        submitReturns = listOf("RETURNEDTX")
        cancelAfterSubmit = true
        vm().also { it.start(key); it.confirm() }
        val r = record()!!
        assertEquals("RETURNEDTX", r.txId)
        assertEquals(TransferState.PRE_SUBMIT, r.state)
    }

    @Test
    fun `a job cancelled during the downgrade still rewrites the fee record and the next view model is blocked`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs()
        store = SharedPrefsPendingTransferStore(prefs)
        signedBlob = paySigned
        submitReturns = listOf("SOMETHINGELSE")
        cancelAfterSubmit = true
        onSubmit = { prefs.failCommits = 1 }
        vm().also { it.start(key); it.confirm() }
        val persisted = SharedPrefsPendingTransferStore(prefs)
        val r = (persisted.get(TEST_ADDRESS, TransferKind.CLAIM_FEE, key, "all") as PendingLookup.Found).record
        assertEquals(TransferState.POSSIBLY_BROADCAST, r.state)
        assertNull(r.txId)
        store = persisted
        val next = vm()
        next.start(key)
        next.confirm()
        assertEquals("TXN_PENDING", failed(next).code)
        assertEquals(1, pays())
    }

    @Test
    fun `a job cancelled when the fee downgrade write fails twice still blocks the key for this process`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs()
        store = SharedPrefsPendingTransferStore(prefs)
        signedBlob = paySigned
        submitReturns = listOf("SOMETHINGELSE")
        cancelAfterSubmit = true
        onSubmit = { prefs.failCommits = 2 }
        vm().also { it.start(key); it.confirm() }
        assertEquals(PendingLookup.Unreadable, store.get(TEST_ADDRESS, TransferKind.CLAIM_FEE, key, "all"))
    }
}
