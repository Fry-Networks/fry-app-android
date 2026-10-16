package com.frynetworks.fryapp.ui.miners.claim

import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.TxResolution
import com.frynetworks.fryapp.data.dashboard.repo.TxResolver
import com.frynetworks.fryapp.data.dashboard.repo.TxStatus
import com.frynetworks.fryapp.data.pending.InMemoryPendingTransferStore
import com.frynetworks.fryapp.data.pending.PendingLookup
import com.frynetworks.fryapp.data.pending.PendingTransfer
import com.frynetworks.fryapp.data.pending.PendingTransferStore
import com.frynetworks.fryapp.data.pending.TransferKind
import com.frynetworks.fryapp.data.pending.TransferState
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.fakes.FakeAlgodRepository
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.FakeRewardsRepository
import com.frynetworks.fryapp.fakes.FakeStakeRepository
import com.frynetworks.fryapp.fakes.FakeWalletBridge
import com.frynetworks.fryapp.fakes.OptInBlobs
import com.frynetworks.fryapp.fakes.TEST_ADDRESS
import com.frynetworks.fryapp.fakes.TestSession
import com.frynetworks.fryapp.fakes.fixedClock
import com.frynetworks.fryapp.wallet.TxnSummary
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import com.frynetworks.fryapp.data.dashboard.model.ClaimPreviewResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimTotalDto
import com.frynetworks.fryapp.domain.ClaimState
import java.math.BigDecimal

/**
 * The ASA opt-in is recorded durably BEFORE submit (kind ASA_OPTIN, shared by stake and claim) and is cleared only once the wallet is
 * opted in or the transfer is proven dead; whatever happens in between, no second opt-in is built while the first may be on the network.
 * Only the public surface of 0.4.7 is used (the kind is read by name), so these tests also run against 2091673.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaimOptInRecordTest {

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val asset = FryAsset.FNODE
    private val blob = OptInBlobs.signed(asset.id)
    private val blobId = OptInBlobs.txId(blob)

    private val miners = FakeMinerRepository()
    private val rewards = FakeRewardsRepository()
    private val stakes = FakeStakeRepository()
    private val fake = FakeWalletBridge()
    private val backing = FakeAlgodRepository()
    private val session = TestSession.signedIn()

    /** The opt-in records written, by name of kind, so this file compiles against 2091673. */
    private val inner = InMemoryPendingTransferStore()
    private val putNews = mutableListOf<PendingTransfer>()
    private val outside = mutableListOf<String>()
    private var failOptInPutNew = false
    private var dispatcherInside = 0
    private val store: PendingTransferStore = object : PendingTransferStore by inner {
        private fun note(name: String) { if (dispatcherInside == 0) outside += name }
        override fun get(sender: String, kind: TransferKind, minerKey: String, row: String) = inner.get(sender, kind, minerKey, row).also { note("get") }
        override fun put(record: PendingTransfer) = inner.put(record).also { note("put") }
        override fun remove(sender: String, kind: TransferKind, minerKey: String, row: String) = inner.remove(sender, kind, minerKey, row).also { note("remove") }
        override fun putNew(record: PendingTransfer): Boolean {
            note("putNew")
            if (record.kind.name == "ASA_OPTIN") {
                putNews += record
                if (failOptInPutNew) return false
            }
            return inner.putNew(record)
        }
    }
    private val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            dispatcherInside++
            try { block.run() } finally { dispatcherInside-- }
        }
    }

    private var statusOf: TxStatus = TxStatus.Unknown
    private var signedBlob: String? = blob
    private var decodedTxId: String? = null
    private var throwOnSubmit: Throwable? = null
    private var submitReturns: List<String>? = null
    private var landsOnSubmit = false
    private var onSubmit: () -> Unit = {}
    private var round: Long? = 1_000L
    private var optInSubmits = 0

    private class ScriptedResolver : TxResolver {
        var verdict: TxResolution = TxResolution.Unknown
        var findVerdict: TxResolution = TxResolution.Unknown
        val findCalls = mutableListOf<List<Any?>>()
        override suspend fun resolve(txId: String, lastValid: Long?): TxResolution = verdict
        override suspend fun findTransfer(sender: String, receiver: String, assetId: Long, amountMicro: Long, minRound: Long?): TxResolution {
            findCalls += listOf(sender, receiver, assetId, amountMicro, minRound)
            return findVerdict
        }
    }

    private val resolver = ScriptedResolver()
    private val algod = object : AlgodRepository by backing {
        override suspend fun txStatus(txId: String): TxStatus = statusOf
        override suspend fun currentRound(): Long? = round
    }

    private val bridge: WalletBridge = object : WalletBridge by fake {
        override suspend fun decodeTxn(txnB64: String): TxnSummary {
            val summary = fake.decodeTxn(txnB64)
            return if (summary.type == "axfer") summary.copy(txId = decodedTxId) else summary
        }

        override suspend fun signTxns(groups: List<List<TxnToSign>>): List<List<String?>> {
            val result = fake.signTxns(groups)
            val only = groups.singleOrNull()?.singleOrNull()
            val b = signedBlob
            return if (only != null && only.txnB64.startsWith("optin|") && b != null) listOf(listOf(b)) else result
        }

        override suspend fun submit(signedB64: List<String>, waitRounds: Int): List<String> {
            val isOptIn = signedB64.singleOrNull().let { it == blob || it?.startsWith("signed(optin|") == true }
            if (isOptIn) optInSubmits++
            onSubmit()
            fake.submitted += signedB64
            if (isOptIn && landsOnSubmit) {
                statusOf = TxStatus.Confirmed(1_200)
                backing.markOptedIn(asset.id, 200_000_000L)
            }
            throwOnSubmit?.let { if (isOptIn) throw it }
            return submitReturns ?: if (isOptIn) listOf(blobId) else listOf("SUBMITTEDTX")
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fake.reconnectAddress = TEST_ADDRESS
        rewards.preview = ClaimPreviewResponse(success = true, preview = true, totals = listOf(ClaimTotalDto(asset.id.toString(), BigDecimal("4.25"))))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(useDispatcher: Boolean = false) = ClaimViewModel(
        rewards, algod, miners, bridge, session, fixedClock(1_757_592_000_000L),
        openUri = {}, pollDelayMillis = 0, optInRetryDelayMillis = 0, store = store, resolver = resolver,
        ioDispatcher = if (useDispatcher) dispatcher else Dispatchers.Unconfined,
    )

    private fun optIns() = fake.built.count { it.startsWith("optin|") }

    private fun open(useDispatcher: Boolean = false): ClaimViewModel = vm(useDispatcher).also { it.start(key) }

    private fun tap(vm: ClaimViewModel): ClaimViewModel {
        vm.optIn()
        return vm
    }

    private fun failedCode(vm: ClaimViewModel): String =
        (vm.uiState.value.state as? ClaimState.Failed)?.code ?: throw AssertionError("expected Failed, was ${vm.uiState.value.state}")

    private fun progressed(vm: ClaimViewModel) = vm.uiState.value.state is ClaimState.Preview

    private fun optInRecords() = putNews.filter { it.kind.name == "ASA_OPTIN" }

    /** The opt-in becomes visible to the node, as it does once the transaction is confirmed. */
    private fun landed() {
        statusOf = TxStatus.Confirmed(1_200)
        backing.markOptedIn(asset.id, 200_000_000L)
    }

    // --- recorded before submit -------------------------------------------------------------------------------------------

    @Test
    fun `the opt-in record is in the store, with the real txid and rounds, before submit is called`() = runTest {
        var seen: List<PendingTransfer> = emptyList()
        onSubmit = { seen = optInRecords().toList() }
        tap(open())
        val r = seen.singleOrNull()
        assertTrue("no ASA_OPTIN record at submit time", r != null)
        assertEquals(blobId, r!!.txId)
        assertEquals(1_000L, r.firstValid)
        assertEquals(1_500L, r.lastValid)
        assertEquals(asset.id, r.assetId)
        assertEquals(TEST_ADDRESS, r.sender)
        assertEquals(TEST_ADDRESS, r.receiver)
        assertEquals(0L, r.amountMicro)
        assertEquals(TransferState.PRE_SUBMIT, r.state)
    }

    @Test
    fun `a failed commit of the opt-in record means nothing is submitted`() = runTest {
        failOptInPutNew = true
        val vm = tap(open())
        assertEquals(0, optInSubmits)
        assertEquals("TXN_RECORD_FAILED", failedCode(vm))
    }

    @Test
    fun `a successful opt-in submits once and clears its record`() = runTest {
        landsOnSubmit = true
        val vm = tap(open())
        assertEquals(1, optInSubmits)
        assertEquals(1, optInRecords().size)
        assertTrue(progressed(vm))
        assertTrue("the record is cleared", inner.get(TEST_ADDRESS, TransferKind.valueOf("ASA_OPTIN"), "-", asset.id.toString()) == PendingLookup.None)
    }

    // --- crash window 1: signed and recorded, process died before the network saw it ------------------------------------------

    @Test
    fun `after a crash before the network a new opt-in is built only once the transaction is provably dead`() = runTest {
        throwOnSubmit = IOException("connection reset")
        tap(open())
        assertEquals(1, optIns())
        throwOnSubmit = null

        resolver.verdict = TxResolution.Unknown
        val blocked = tap(open())
        assertEquals("TXN_PENDING", failedCode(blocked))
        assertEquals("nothing is built while the first may still land", 1, optIns())

        resolver.verdict = TxResolution.Absent
        statusOf = TxStatus.Absent
        landsOnSubmit = true
        val again = tap(open())
        assertEquals(2, optIns())
        assertEquals(2, optInSubmits)
        assertTrue(progressed(again))
    }

    // --- crash window 2: on the chain, the process died before the result was read ---------------------------------------------

    @Test
    fun `a confirmed opt-in is settled without a second submit`() = runTest {
        throwOnSubmit = IOException("connection reset")
        tap(open())
        assertEquals(1, optInSubmits)
        throwOnSubmit = null
        val vm = open()
        landed()
        tap(vm)
        assertEquals("no second opt-in", 1, optIns())
        assertEquals(1, optInSubmits)
        assertTrue(progressed(vm))
    }

    // --- crash window 3: handed to submit with no id to resume from -------------------------------------------------------------

    @Test
    fun `an opt-in with no readable id is possibly broadcast, blocks, and is resolved only by the account search`() = runTest {
        signedBlob = null
        throwOnSubmit = IOException("connection reset")
        tap(open())
        assertEquals(1, optIns())
        throwOnSubmit = null

        resolver.findVerdict = TxResolution.Unknown
        val blocked = tap(open())
        assertEquals("TXN_PENDING", failedCode(blocked))
        assertEquals(1, optIns())
        val call = resolver.findCalls.lastOrNull()
        assertTrue("the account search was asked", call != null)
        assertEquals(TEST_ADDRESS, call!![0])
        assertEquals("receiver is the sender", TEST_ADDRESS, call[1])
        assertEquals(asset.id, call[2])
        assertEquals(0L, call[3])

        resolver.findVerdict = TxResolution.Confirmed("FOUNDTX", null)
        val vm = open()
        landed()
        tap(vm)
        assertEquals("the found opt-in is not built again", 1, optIns())
        assertTrue(progressed(vm))
    }

    // --- the Absent boundary ------------------------------------------------------------------------------------------------------

    @Test
    fun `Absent is ignored when the last valid round is unknown`() = runTest {
        signedBlob = null
        decodedTxId = "DECODEDTX"
        throwOnSubmit = IOException("connection reset")
        tap(open())
        throwOnSubmit = null
        resolver.verdict = TxResolution.Absent
        statusOf = TxStatus.Absent
        val vm = tap(open())
        assertEquals("TXN_PENDING", failedCode(vm))
        assertEquals(1, optIns())
    }

    // --- an id the signed bytes do not give -------------------------------------------------------------------------------------

    @Test
    fun `a submit that returns another txid than the signed bytes give blocks the next opt-in`() = runTest {
        submitReturns = listOf("ANOTHERTX")
        val first = tap(open())
        assertEquals("TXN_PENDING", failedCode(first))
        submitReturns = null
        val vm = tap(open())
        assertEquals("TXN_PENDING", failedCode(vm))
        assertEquals(1, optIns())
    }

    // --- every store call goes through the injected dispatcher -------------------------------------------------------------------

    @Test
    fun `every store call of an opt-in goes through the injected IO dispatcher`() = runTest {
        landsOnSubmit = true
        tap(open(useDispatcher = true))
        assertEquals(1, optInRecords().size)
        assertEquals("calls outside the dispatcher: $outside", emptyList<String>(), outside)
    }
}
