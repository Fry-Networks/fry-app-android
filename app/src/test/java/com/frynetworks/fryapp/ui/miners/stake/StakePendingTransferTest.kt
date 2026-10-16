package com.frynetworks.fryapp.ui.miners.stake

import com.frynetworks.fryapp.auth.SessionProfile
import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.ProductReward
import com.frynetworks.fryapp.data.dashboard.model.ProductStake
import com.frynetworks.fryapp.data.dashboard.model.ProductTokens
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
import com.frynetworks.fryapp.network.dashboard.DashboardException
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
 * The stake transfer is recorded durably BEFORE submit and is cleared only when the dashboard has recorded the stake or the
 * resolver proves the transfer dead. Whatever happens in between (process death, retry, logout, another account), no second
 * transfer is built. The signed blob below is a real algosdk 3.7.0 asset transfer (see SignedTxnReaderTest), so its txid and
 * last valid round come from its own bytes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StakePendingTransferTest {

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val otherKey = "FEM-ZYXWVUTSRQPONMLKJIHGFEDCBA543210"
    private val vectorSigned = "gqNzaWfEQOVQ4a1yhbpYw/M3dbBmTh1Z0OLVpNTgbPVY5/W1d0KvuQ+03G0dkC+DtjLBG1J2UHHlj/D1AAboMwvOCf9vIwOjdHhui6RhYW10zgBMS0CkYXJjdsQg/RckOFqgx1tk+3jNYC+h2ZH96/drE8WO1wLqyDXp9hijZmVlzQPoomZ2zgPvhf2jZ2VurG1haW5uZXQtdjEuMKJnaMQgwGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit+ibHbOA++J5aRub3RlxG57ImEiOiJzdGFrZSIsIm4iOiJ4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHgifaNzbmTEIOpKbGPinFIKvvVQexMuxfmVR3auvr57kkIe6mkURtIspHR5cGWlYXhmZXKkeGFpZM6f1Mbt"
    private val vectorTxId = "GCT7RIV64JK5FV3UGVB6Q4EKMV6KMKINCNCG6JHAQ4PICRRYIRZQ"
    private val vectorLastValid = 66_030_053L
    private val vectorFirstValid = 66_029_053L
    private val otherAddress = "B".repeat(58)

    private val miners = FakeMinerRepository()
    private val rewards = FakeRewardsRepository()
    private val stakes = FakeStakeRepository()
    private val fake = FakeWalletBridge()
    private val backing = FakeAlgodRepository()
    private var store: PendingTransferStore = InMemoryPendingTransferStore()
    private var session = TestSession.signedIn()

    private var status: TxStatus = TxStatus.Unknown
    private var round: Long? = 1_000L
    private var signedBlob: String? = null // null = the fake bridge's own (undecodable) blob
    private var decodedTxId: String? = null
    private var throwOnSubmit: Throwable? = null
    private var submitReturns: List<String>? = null
    private var onSubmit: () -> Unit = {}
    private var cancelAfterSubmit = false

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
        override suspend fun currentRound(): Long? = round
    }

    private val bridge: WalletBridge = object : WalletBridge by fake {
        override suspend fun decodeTxn(txnB64: String): TxnSummary {
            val summary = fake.decodeTxn(txnB64)
            return if (summary.type == "axfer") summary.copy(txId = decodedTxId) else summary
        }

        override suspend fun signTxns(groups: List<List<TxnToSign>>): List<List<String?>> {
            val result = fake.signTxns(groups)
            val blob = signedBlob ?: return result
            return result.map { g -> g.map { if (it != null) blob else null } }
        }

        override suspend fun submit(signedB64: List<String>, waitRounds: Int): List<String> {
            val result = submitInner(signedB64, waitRounds)
            if (cancelAfterSubmit) kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            return result
        }

        private suspend fun submitInner(signedB64: List<String>, waitRounds: Int): List<String> {
            onSubmit()
            throwOnSubmit?.let {
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
        fake.submitTxIds = listOf(vectorTxId)
        miners.products = mapOf(
            key to Product(
                key = "FEM",
                reward = ProductReward(
                    stake = ProductStake(stakeOne = BigDecimal("5"), stakeTwo = BigDecimal("20"), register = BigDecimal("40"), node = BigDecimal("100")),
                    tokens = ProductTokens(stake = "tFRY", reward = "tFRY", register = "tFRY", node = "fNODE"),
                ),
            ),
        )
        miners.products = miners.products + (otherKey to miners.products.getValue(key))
        rewards.priceTable = mapOf(FryAsset.TFRY.id to BigDecimal("0.5"))
        backing.markOptedIn(FryAsset.TFRY.id, 200_000_000L)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined

    private fun vm(s: com.frynetworks.fryapp.auth.SessionRepository = session) =
        StakeViewModel(
            miners, rewards, stakes, algod, bridge, s, fixedClock(1_757_592_000_000L),
            openUri = {}, store = store, resolver = resolver, ioDispatcher = ioDispatcher,
        )

    private fun begin(vm: StakeViewModel = vm()): StakeViewModel {
        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()
        return vm
    }

    private fun transfers() = fake.built.count { it.startsWith("axfer|") }

    private fun failed(vm: StakeViewModel): StakeState.Failed {
        val state = vm.uiState.value.state
        assertTrue("expected Failed but was $state", state is StakeState.Failed)
        return state as StakeState.Failed
    }

    private fun assertDone(vm: StakeViewModel) = assertEquals(StakeState.Done("RECORDEDTX", waived = false), vm.uiState.value.state)

    /** What a new process would read from [prefs]: a downgraded record is blocked in memory in this one, so [record] cannot see it. */
    private fun persistedRecord(prefs: PendingTransferStoreTest.FakePrefs) =
        (SharedPrefsPendingTransferStore(prefs).find(TEST_ADDRESS, TransferKind.STAKE) as? PendingLookup.Found)?.record

    private fun record(sender: String = TEST_ADDRESS) = (store.find(sender, TransferKind.STAKE) as? PendingLookup.Found)?.record

    private fun failSubmitOnce(real: Boolean = true) {
        if (real) signedBlob = vectorSigned
        throwOnSubmit = IOException("connection reset")
    }

    // --- recorded before submit -------------------------------------------------------------------------------------------

    @Test
    fun `the record is in the store, with the real txid and rounds, before submit is called`() = runTest {
        signedBlob = vectorSigned
        var seen: com.frynetworks.fryapp.data.pending.PendingTransfer? = null
        onSubmit = { seen = record() }
        begin()
        val r = seen
        assertTrue("no record at submit time", r != null)
        assertEquals(vectorTxId, r!!.txId)
        assertEquals(vectorFirstValid, r.firstValid)
        assertEquals(vectorLastValid, r.lastValid)
        assertEquals(1_000L, r.builtRound)
        assertEquals(TransferState.PRE_SUBMIT, r.state)
        assertEquals(TransferKind.STAKE, r.kind)
        assertEquals(TEST_ADDRESS, r.sender)
        assertEquals(StakeRepository.STAKE_WALLET, r.receiver)
        assertEquals(FryAsset.TFRY.id, r.assetId)
    }

    @Test
    fun `the stored last valid round is the decoded one, never an estimate from the build round`() = runTest {
        failSubmitOnce()
        begin()
        assertEquals(vectorLastValid, record()!!.lastValid) // an estimate would be 1000 + 1001
    }

    @Test
    fun `a failed commit of the record means nothing is submitted`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs().also { it.commitResult = false }
        store = SharedPrefsPendingTransferStore(prefs)
        signedBlob = vectorSigned
        val vm = begin()
        assertTrue(fake.submitted.isEmpty())
        assertEquals("TXN_RECORD_FAILED", failed(vm).code)
        assertEquals(0, prefs.applyCalls)
    }

    @Test
    fun `a record with an unknown schema version blocks every stake and builds nothing`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs()
        store = SharedPrefsPendingTransferStore(prefs)
        failSubmitOnce()
        begin()
        throwOnSubmit = null
        val k = prefs.map.keys.single()
        prefs.map[k] = (prefs.map[k] as String).replace("\"schemaVersion\":1", "\"schemaVersion\":9")

        val vm2 = begin(vm())
        assertEquals("TXN_PENDING", failed(vm2).code)
        vm2.retry()
        assertEquals("TXN_PENDING", failed(vm2).code)
        assertEquals(1, transfers())
        assertEquals(1, fake.submitted.size)
    }

    // --- txid cross-check -------------------------------------------------------------------------------------------------

    @Test
    fun `a submit that returns another txid than the signed bytes give blocks and keeps the transfer unresolved`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs()
        store = SharedPrefsPendingTransferStore(prefs)
        signedBlob = vectorSigned
        submitReturns = listOf("SOMETHINGELSE")
        val vm = begin()
        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(TransferState.POSSIBLY_BROADCAST, persistedRecord(prefs)!!.state)
        resolver.verdict = TxResolution.Absent
        vm.retry()
        assertEquals(1, transfers())
        assertTrue(persistedRecord(prefs) != null)
    }

    @Test
    fun `the bridge's decoded txid is checked against the submit result too when the bytes cannot be read`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs()
        store = SharedPrefsPendingTransferStore(prefs)
        decodedTxId = "DECODEDTX"
        submitReturns = listOf("ANOTHERTX")
        val vm = begin()
        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(TransferState.POSSIBLY_BROADCAST, persistedRecord(prefs)!!.state)
        assertEquals(1, transfers())
        vm.retry()
        assertEquals(1, transfers())
    }

    @Test
    fun `an unreadable blob falls back to the decoded txid with no last valid round, and Absent is then ignored`() = runTest {
        decodedTxId = "DECODEDTX"
        failSubmitOnce(real = false)
        val vm = begin()
        val r = record()!!
        assertEquals("DECODEDTX", r.txId)
        assertNull(r.lastValid)
        assertEquals(TransferState.PRE_SUBMIT, r.state)

        throwOnSubmit = null
        resolver.verdict = TxResolution.Absent
        status = TxStatus.Absent
        vm.retry()
        assertEquals(1, transfers())
        assertTrue(record() != null)
        assertEquals("DECODEDTX" to null, resolver.resolveCalls.last())
    }

    // --- process death, logout, other accounts ----------------------------------------------------------------------------

    @Test
    fun `a new view model over the same store resumes after process death with no second submit`() = runTest {
        failSubmitOnce()
        begin()
        throwOnSubmit = null
        status = TxStatus.Confirmed(66_029_500)

        val reborn = vm()
        reborn.start(key, StakeContext.Registration, byod = false)

        assertDone(reborn)
        assertEquals(1, transfers())
        assertEquals(1, fake.submitted.size)
        assertEquals(1, stakes.submitCalls.size)
        assertEquals(vectorTxId, stakes.submitCalls.single().second.txId)
        assertNull(record())
    }

    @Test
    fun `logging out and back in as the same sender finds the record and resumes with no second submit`() = runTest {
        failSubmitOnce()
        begin()
        throwOnSubmit = null
        session.clear()
        session.markSignedIn(SessionProfile(TEST_ADDRESS, vendor = WalletVendor.PERA), fingerprintBound = true)
        assertTrue(record() != null)
        status = TxStatus.Confirmed(66_029_500)

        val again = vm()
        again.start(key, StakeContext.Registration, byod = false)

        assertDone(again)
        assertEquals(1, transfers())
        assertEquals(1, fake.submitted.size)
    }

    @Test
    fun `a record of one sender does not block another sender`() = runTest {
        failSubmitOnce()
        begin()
        throwOnSubmit = null
        signedBlob = null

        fake.reconnectAddress = otherAddress
        val other = vm(TestSession.signedIn(address = otherAddress))
        begin(other)

        assertDone(other)
        assertEquals(2, transfers())
        assertTrue(record(TEST_ADDRESS) != null)
    }

    @Test
    fun `the session clearing mid-flow leaves the record intact`() = runTest {
        failSubmitOnce()
        val vm = begin()
        throwOnSubmit = null
        session.clear()
        vm.retry()
        assertTrue(record() != null)
        assertEquals(1, transfers())
    }

    // --- retry never builds unless Absent ---------------------------------------------------------------------------------

    @Test
    fun `retry with a record and an unknown verdict never builds a new transfer`() = runTest {
        failSubmitOnce()
        val vm = begin()
        throwOnSubmit = null
        stakes.verifyError = DashboardException("TXN_VERIFY_FAILED", "not found") // an unknown status the dashboard cannot verify stays pending
        for (s in listOf(TxStatus.Absent, TxStatus.Unknown, TxStatus.Rejected("x"))) {
            status = s
            vm.retry()
            vm.start(key, StakeContext.Registration, byod = false)
            vm.confirm()
        }
        assertEquals(1, transfers())
        assertTrue(record() != null)
    }

    @Test
    fun `an Absent verdict clears the record and only then allows a new transfer`() = runTest {
        failSubmitOnce()
        val vm = begin()
        throwOnSubmit = null
        status = TxStatus.Absent
        resolver.verdict = TxResolution.Absent

        vm.retry()

        assertNull(record())
        assertTrue("${vm.uiState.value.state}", vm.uiState.value.state is StakeState.Ready)
        assertEquals(1, transfers())
        assertEquals(vectorTxId to vectorLastValid, resolver.resolveCalls.last())
        vm.confirm()
        assertEquals(2, transfers())
    }

    @Test
    fun `an Absent verdict whose record cannot be cleared builds nothing`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs()
        store = SharedPrefsPendingTransferStore(prefs)
        failSubmitOnce()
        val vm = begin()
        throwOnSubmit = null
        resolver.verdict = TxResolution.Absent
        prefs.commitResult = false

        vm.retry()

        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(1, transfers())
        assertTrue(record() != null)
    }

    @Test
    fun `a verdict that the indexers have it resumes from the transaction when algod does not know it`() = runTest {
        failSubmitOnce()
        val vm = begin()
        throwOnSubmit = null
        status = TxStatus.Unknown
        resolver.verdict = TxResolution.Confirmed(vectorTxId, 66_029_600)

        vm.retry()

        assertDone(vm)
        assertEquals(1, transfers())
        assertEquals(1, fake.submitted.size)
        assertNull(record())
    }

    // --- the record outlives every failure until the dashboard has recorded the stake -----------------------------------------

    @Test
    fun `a failure while waiting for confirmation keeps the record`() = runTest {
        signedBlob = vectorSigned
        backing.waitError = DashboardException("TXN_NOT_CONFIRMED", "slow")
        val vm = begin()
        assertEquals(vectorTxId, failed(vm).txId)
        assertEquals(vectorTxId, record()!!.txId)
    }

    @Test
    fun `a failure while the dashboard records the stake keeps the record`() = runTest {
        signedBlob = vectorSigned
        stakes.submitError = DashboardException("SERVER", "down")
        val vm = begin()
        assertTrue(failed(vm).txId != null)
        assertTrue(record() != null)
    }

    @Test
    fun `a recorded stake clears the record`() = runTest {
        signedBlob = vectorSigned
        val vm = begin()
        assertDone(vm)
        assertNull(record())
        assertEquals(1, transfers())
    }

    // --- a transfer whose id could not be learned -------------------------------------------------------------------------

    @Test
    fun `an unreadable blob and no decoded id is possibly broadcast, blocks, and is never cleared by Absent`() = runTest {
        failSubmitOnce(real = false)
        val vm = begin()
        val r = record()!!
        assertEquals(TransferState.POSSIBLY_BROADCAST, r.state)
        assertNull(r.txId)
        throwOnSubmit = null
        resolver.verdict = TxResolution.Absent
        status = TxStatus.Absent
        vm.retry()
        assertEquals("TXN_PENDING", failed(vm).code)
        assertNull(failed(vm).txId)
        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()
        assertEquals(1, transfers())
        assertEquals(TransferState.POSSIBLY_BROADCAST, record()!!.state)
    }

    @Test
    fun `a possibly broadcast transfer is resolved by the account search, and resumes from the id it finds`() = runTest {
        failSubmitOnce(real = false)
        val vm = begin()
        val r = record()!!
        throwOnSubmit = null
        resolver.findVerdict = TxResolution.Confirmed("FOUNDTX", null)
        status = TxStatus.Confirmed(66_029_500)

        vm.retry()

        assertDone(vm)
        assertEquals(listOf<Any?>(TEST_ADDRESS, StakeRepository.STAKE_WALLET, FryAsset.TFRY.id, r.amountMicro, 1_000L), resolver.findCalls.single())
        assertEquals("FOUNDTX", stakes.submitCalls.single().second.txId)
        assertEquals(1, transfers())
        assertNull(record())
    }

    @Test
    fun `a possibly broadcast transfer the search cannot find stays blocked`() = runTest {
        failSubmitOnce(real = false)
        val vm = begin()
        throwOnSubmit = null
        resolver.findVerdict = TxResolution.Unknown
        vm.retry()
        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(1, transfers())
        assertTrue(record() != null)
    }

    // --- round 2: atomic first write, contact support, blank ids, hard block, IO dispatcher, clear retries ----------------------------

    /** A store in which a competing record for the same sender appears between the view model's check and its write. */
    private class RacingStore(private val inner: PendingTransferStore, private val rival: com.frynetworks.fryapp.data.pending.PendingTransfer) :
        PendingTransferStore by inner {
        override fun putNew(record: com.frynetworks.fryapp.data.pending.PendingTransfer): Boolean {
            inner.put(rival)
            return inner.putNew(record)
        }
    }

    @Test
    fun `a record that appears between the check and the write is never overwritten and nothing is submitted`() = runTest {
        val rival = com.frynetworks.fryapp.data.pending.PendingTransfer(
            kind = TransferKind.STAKE, sender = TEST_ADDRESS, minerKey = key, row = "registration", assetId = FryAsset.TFRY.id,
            amount = "80", amountMicro = 80_000_000, receiver = StakeRepository.STAKE_WALLET, txId = "RIVALTX", firstValid = 1, lastValid = 2,
            builtRound = 1, state = TransferState.PRE_SUBMIT, createdAt = 1L,
        )
        store = RacingStore(InMemoryPendingTransferStore(), rival)
        signedBlob = vectorSigned
        val vm = begin()
        assertTrue(fake.submitted.isEmpty())
        assertEquals("RIVALTX", record()!!.txId)
        assertEquals("TXN_RECORD_FAILED", failed(vm).code)
    }

    @Test
    fun `a transfer that may have been sent says it is still pending and to contact support`() = runTest {
        failSubmitOnce(real = false)
        val vm = begin()
        val message = failed(vm).message
        assertTrue(message, message.contains("still pending", ignoreCase = true) && message.contains("contact support", ignoreCase = true))
        vm.retry()
        assertTrue(failed(vm).message.contains("contact support", ignoreCase = true))
    }

    @Test
    fun `a blank txid from submit is no id, so a transfer without a readable id stays possibly broadcast`() = runTest {
        for (blank in listOf("", "   ")) {
            store = InMemoryPendingTransferStore()
            fake.built.clear()
            submitReturns = listOf(blank)
            val vm = begin(vm())
            val r = record()!!
            assertEquals(TransferState.POSSIBLY_BROADCAST, r.state)
            assertNull(r.txId)
            assertTrue(failed(vm).txId == null)
            assertEquals("TXN_PENDING", failed(vm).code)
        }
    }

    @Test
    fun `a blank txid from submit never becomes the recorded id when the bytes gave one`() = runTest {
        signedBlob = vectorSigned
        submitReturns = listOf("")
        val vm = begin()
        assertEquals(vectorTxId, record()!!.txId)
        assertTrue(vm.uiState.value.state !is StakeState.Done)
        assertTrue(vm.uiState.value.state !is StakeState.Submitting)
    }

    @Test
    fun `a blank decoded txid is no id`() = runTest {
        decodedTxId = ""
        failSubmitOnce(real = false)
        begin()
        assertNull(record()!!.txId)
        assertEquals(TransferState.POSSIBLY_BROADCAST, record()!!.state)
    }

    @Test
    fun `when the mismatch downgrade cannot be written the key is blocked for the process and nothing is rebuilt`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs()
        store = SharedPrefsPendingTransferStore(prefs)
        signedBlob = vectorSigned
        submitReturns = listOf("SOMETHINGELSE")
        onSubmit = { prefs.commitResult = false } // the record is on disk; from now on writes fail
        val vm = begin()
        prefs.commitResult = true
        assertEquals("TXN_PENDING", failed(vm).code)
        assertEquals(vectorTxId, (prefs.map.values.single() as String).let { Regex("\"txId\":\"([A-Z0-9]+)\"").find(it)!!.groupValues[1] })

        resolver.verdict = TxResolution.Absent
        status = TxStatus.Absent
        vm.retry()
        vm.start(key, StakeContext.Registration, byod = false)
        vm.confirm()
        assertEquals(1, transfers())

        val reborn = vm()
        reborn.start(key, StakeContext.Registration, byod = false)
        reborn.confirm()
        assertEquals(1, transfers())
        assertEquals(1, fake.submitted.size)
        assertTrue(failed(reborn).message.contains("contact support", ignoreCase = true))
    }

    @Test
    fun `the downgrade write is retried once and a second success is enough`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs()
        store = SharedPrefsPendingTransferStore(prefs)
        signedBlob = vectorSigned
        submitReturns = listOf("SOMETHINGELSE")
        onSubmit = { prefs.failCommits = 1 } // the first downgrade write fails, the retry succeeds
        begin()
        assertEquals(TransferState.POSSIBLY_BROADCAST, persistedRecord(prefs)!!.state)
        assertNull(persistedRecord(prefs)!!.txId)
        assertEquals(1, transfers())
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
    fun `every store method call of a stake goes through the injected IO dispatcher`() = runTest {
        val counting = CountingDispatcher()
        val audited = AuditedStore(store, counting)
        store = audited
        ioDispatcher = counting
        signedBlob = vectorSigned
        val vm = begin()
        assertDone(vm)
        assertEquals("calls outside the dispatcher: ${audited.outside}", emptyList<String>(), audited.outside)
        // open-time lookup + gate lookup, the first write, the clear after Recorded; nothing else
        assertEquals(mapOf("find" to 2, "putNew" to 1, "remove" to 1), audited.calls.toMap())
        assertEquals("every call is one dispatch", 4, counting.dispatched)
    }

    // --- cancellation after the transfer may be on the network: the store writes still complete ------------------------------

    @Test
    fun `a job cancelled right after submit returns still records the id of an unidentified transfer`() = runTest {
        signedBlob = null
        decodedTxId = null
        submitReturns = listOf("RETURNEDTX")
        cancelAfterSubmit = true
        begin()
        val r = record()!!
        assertEquals("RETURNEDTX", r.txId)
        assertEquals(TransferState.PRE_SUBMIT, r.state)
        assertEquals(1, transfers())
    }

    @Test
    fun `a job cancelled during the downgrade still rewrites the record and the next view model is blocked`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs()
        store = SharedPrefsPendingTransferStore(prefs)
        signedBlob = vectorSigned
        submitReturns = listOf("SOMETHINGELSE")
        cancelAfterSubmit = true
        onSubmit = { prefs.failCommits = 1 } // the first downgrade write fails too
        begin()
        val persisted = SharedPrefsPendingTransferStore(prefs) // what the next process reads
        val r = (persisted.find(TEST_ADDRESS, TransferKind.STAKE) as PendingLookup.Found).record
        assertEquals(TransferState.POSSIBLY_BROADCAST, r.state)
        assertNull(r.txId)
        store = persisted
        val next = vm()
        next.start(key, StakeContext.Registration, byod = false)
        assertEquals("TXN_PENDING", failed(next).code)
        assertEquals(1, transfers())
    }

    @Test
    fun `a job cancelled when the downgrade write fails twice still blocks the key for this process`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs()
        store = SharedPrefsPendingTransferStore(prefs)
        signedBlob = vectorSigned
        submitReturns = listOf("SOMETHINGELSE")
        cancelAfterSubmit = true
        onSubmit = { prefs.failCommits = 2 }
        begin()
        assertEquals(PendingLookup.Unreadable, store.find(TEST_ADDRESS, TransferKind.STAKE))
    }

    @Test
    fun `a failed clear after the dashboard recorded the stake is retried up to three times`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs()
        store = SharedPrefsPendingTransferStore(prefs)
        signedBlob = vectorSigned
        onSubmit = { prefs.failCommits = 2 } // the next two commits (the clear's first two tries) fail
        val vm = begin()
        assertDone(vm)
        assertNull(record())
    }

    @Test
    fun `a clear that fails every time leaves the record and still shows the recorded stake`() = runTest {
        val prefs = PendingTransferStoreTest.FakePrefs()
        store = SharedPrefsPendingTransferStore(prefs)
        signedBlob = vectorSigned
        onSubmit = { prefs.failCommits = 3 }
        val vm = begin()
        assertDone(vm)
        assertTrue(record() != null)
    }
}
