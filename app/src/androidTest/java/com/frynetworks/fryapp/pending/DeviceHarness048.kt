package com.frynetworks.fryapp.pending

import android.content.Context
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.data.dashboard.api.IndexerApi
import com.frynetworks.fryapp.data.dashboard.model.ClaimEnvelopeResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimPreviewResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimTotalDto
import com.frynetworks.fryapp.data.dashboard.model.ConfirmResultResponse
import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.ProductReward
import com.frynetworks.fryapp.data.dashboard.model.ProductStake
import com.frynetworks.fryapp.data.dashboard.model.ProductTokens
import com.frynetworks.fryapp.data.dashboard.model.StakeSubmitResponse
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.data.dashboard.repo.StakeRepository
import com.frynetworks.fryapp.data.dashboard.repo.StakeSubmitPayload
import com.frynetworks.fryapp.data.dashboard.repo.TxStatus
import com.frynetworks.fryapp.data.dashboard.repo.impl.TxResolverImpl
import com.frynetworks.fryapp.data.pending.PendingTransferStore
import com.frynetworks.fryapp.data.pending.SharedPrefsClaimFeeBindingStore
import com.frynetworks.fryapp.data.pending.SharedPrefsPendingTransferStore
import com.frynetworks.fryapp.data.pending.TransferKind
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.fakes.FakeAlgodRepository
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.FakeRewardsRepository
import com.frynetworks.fryapp.fakes.FakeStakeRepository
import com.frynetworks.fryapp.fakes.FakeWalletBridge
import com.frynetworks.fryapp.fakes.TestSession
import com.frynetworks.fryapp.fakes.fixedClock
import com.frynetworks.fryapp.ui.miners.claim.ClaimViewModel
import com.frynetworks.fryapp.ui.miners.stake.StakeViewModel
import com.frynetworks.fryapp.util.SignedTxnReader
import com.frynetworks.fryapp.wallet.TxnToSign
import com.frynetworks.fryapp.wallet.WalletBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.util.Base64

/** Where a 0.4.8 phase deliberately stops forever, or which fake world edge it bends. */
enum class Hang048 {
    NONE,

    /** The first submit never reaches the chain (the process is killed first). */
    BEFORE_NETWORK,

    /** Not a stop: the job that called submit is cancelled the moment submit has reached the chain and returns. */
    CANCEL_AFTER_SUBMIT,

    /** The dashboard's stake record call never returns. */
    BEFORE_RECORD,

    /** Not a stop: every removal of a fee record fails, as a full or failing disk would. */
    REMOVE_FAILS,

    /** Not a stop: the custodial confirm never says ok, so a claim ends in CONFIRM_PENDING. */
    CONFIRM_NEVER,
}

/** A real-framed signed ASA opt-in blob (`{sig, txn}` msgpack, zero signature, never broadcast): its id, fv and lv are read from its own bytes. */
object OptInBlobs048 {
    const val FV = 1_000L
    const val LV = 1_500L

    fun signed(assetId: Long): String {
        val who = ByteArray(32) { 7 }
        val txn = ByteArrayOutputStream().apply {
            write(0x80 or 6)
            str("type"); str("axfer")
            str("snd"); bin(who)
            str("arcv"); bin(who)
            str("xaid"); uint(assetId)
            str("fv"); uint(FV)
            str("lv"); uint(LV)
        }.toByteArray()
        val out = ByteArrayOutputStream().apply {
            write(0x80 or 2)
            str("sig"); bin(ByteArray(64))
            str("txn"); write(txn)
        }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    fun txId(assetId: Long): String = checkNotNull(SignedTxnReader.parse(signed(assetId))).txId

    private fun ByteArrayOutputStream.str(s: String) {
        write(0xa0 or s.length)
        write(s.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.bin(b: ByteArray) {
        write(0xc4)
        write(b.size)
        write(b)
    }

    private fun ByteArrayOutputStream.uint(v: Long) {
        when {
            v < 128 -> write(v.toInt())
            v <= 0xff -> { write(0xcc); write(v.toInt()) }
            v <= 0xffff -> { write(0xcd); write((v shr 8).toInt()); write(v.toInt()) }
            else -> { write(0xce); for (s in intArrayOf(24, 16, 8, 0)) write((v shr s).toInt()) }
        }
    }
}

/**
 * The fake world of one 0.4.8 scenario phase, in the debug app's own filesDir like [DeviceHarness] (whose classes [DeviceLedger] and [Blobs]
 * it reuses unchanged). Differences: two miners, a claim-fee binding store of its own, opt-in transactions that really sit on the fake chain
 * (the wallet is opted in once one has landed, optionally one round later), and the [Hang048] edges. [reached] completes at a hang point.
 */
class DeviceHarness048(val context: Context, val hang: Hang048, private val optInManaged: Boolean = false, private val optInLag: Boolean = false) {
    val ledger = DeviceLedger(File(context.filesDir, "pending-ledger.json"))
    val prefs = context.getSharedPreferences(DeviceHarness.PREFS, Context.MODE_PRIVATE)
    val bindingPrefs = context.getSharedPreferences(BINDING_PREFS, Context.MODE_PRIVATE)
    val reached = CompletableDeferred<Unit>()

    val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    val otherKey = "FEM-ZYXWVUTSRQPONMLKJIHGFEDCBA543210"
    private val clock = fixedClock(1_757_592_000_000L)
    private val managedAssets = listOf(FryAsset.TFRY, FryAsset.FNODE)

    private val backing = FakeAlgodRepository().also {
        if (!optInManaged) {
            it.markOptedIn(FryAsset.TFRY.id, 200_000_000L)
            it.markOptedIn(FryAsset.FNODE.id)
        }
    }
    val miners = FakeMinerRepository().also {
        val product = Product(
            key = "FEM",
            reward = ProductReward(
                stake = ProductStake(stakeOne = BigDecimal("5"), stakeTwo = BigDecimal("20"), register = BigDecimal("40"), node = BigDecimal("100")),
                tokens = ProductTokens(stake = "tFRY", reward = "tFRY", register = "tFRY", node = "fNODE"),
            ),
        )
        it.products = mapOf(key to product, otherKey to product)
    }
    private val rewardsBacking = FakeRewardsRepository().also {
        it.priceTable = mapOf(FryAsset.TFRY.id to BigDecimal("0.5"), FryAsset.FNODE.id to BigDecimal("0.5"))
        it.preview = ClaimPreviewResponse(success = true, preview = true, totals = listOf(ClaimTotalDto(FryAsset.FNODE.id.toString(), BigDecimal("4.25"))))
        it.envelope = ClaimEnvelopeResponse(success = true, txId = "CUSTODIALTX")
        it.custodialResults = listOf(ConfirmResultResponse(ok = hang != Hang048.CONFIRM_NEVER, txId = "CUSTODIALTX"))
    }
    private val stakesBacking = FakeStakeRepository()
    private val wallet = FakeWalletBridge()

    /** Whether the node shows the wallet opted in to [assetId]: its opt-in transaction is on the fake chain (one round earlier than now when lagging). */
    private fun optedIn(assetId: Long): Boolean {
        val s = ledger.read()
        val landed = s.chain[OptInBlobs048.txId(assetId)] ?: return false
        return !optInLag || s.algodRound > landed
    }

    val algod: AlgodRepository = object : AlgodRepository by backing {
        override suspend fun txStatus(txId: String): TxStatus =
            ledger.read().chain[txId]?.let { TxStatus.Confirmed(it) } ?: TxStatus.Absent

        override suspend fun currentRound(): Long? = ledger.read().algodRound
        override suspend fun poolHasNoRecord(txId: String): Boolean = txId !in ledger.read().chain
        override suspend fun waitConfirmed(txId: String, rounds: Int) = kotlinx.coroutines.yield()

        override suspend fun assetBalanceMicro(address: String, assetId: Long): Long? =
            if (optInManaged && managedAssets.any { it.id == assetId }) (if (optedIn(assetId)) 200_000_000L else null) else backing.assetBalanceMicro(address, assetId)

        // `by backing` forwards isOptedIn to the backing fake, whose own assetBalanceMicro would not see the chain: say it from this object's balance.
        override suspend fun isOptedIn(address: String, assetId: Long): Boolean = assetBalanceMicro(address, assetId) != null
    }

    val indexer = object : IndexerApi {
        private val json = "application/json".toMediaType()
        override suspend fun get(url: String): Response<ResponseBody> {
            val state = ledger.read()
            val round = if (url.startsWith(DeviceHarness.IDX1)) state.idx1Round else state.idx2Round
            if (url.endsWith("/health")) {
                return Response.success(
                    """{"db-available":true,"is-migrating":false,"message":"$round","round":$round,"version":"3.10.0-ndly"}""".toResponseBody(json),
                )
            }
            if (url.contains("/v2/accounts/")) return Response.error(500, """{"message":"no account search in the device test"}""".toResponseBody(json))
            val txId = url.substringAfterLast('/')
            val at = state.chain[txId]
                ?: return Response.error(404, """{"message":"no transaction found for transaction id: $txId"}""".toResponseBody(json))
            return Response.success("""{"current-round":$round,"transaction":{"id":"$txId","confirmed-round":$at}}""".toResponseBody(json))
        }
    }

    val resolver = TxResolverImpl(algod, indexer, listOf(DeviceHarness.IDX1, DeviceHarness.IDX2))

    private val optInBlobs = managedAssets.associate { OptInBlobs048.signed(it.id) to it.id }

    val bridge: WalletBridge = object : WalletBridge by wallet {
        override suspend fun signTxns(groups: List<List<TxnToSign>>): List<List<String?>> {
            val fake = wallet.signTxns(groups)
            val only = groups.singleOrNull()?.singleOrNull() ?: return fake
            val blob = when {
                only.txnB64.startsWith("optin|") -> {
                    ledger.update { it.signs++ }
                    OptInBlobs048.signed(only.txnB64.substringBefore('#').substringAfterLast('|').toLong())
                }
                only.txnB64.startsWith("axfer|") -> {
                    val n = ledger.update { it.signs++; it.signs }
                    if (n % 2 == 1) Blobs.STAKE_1500 else Blobs.STAKE_5000
                }
                only.txnB64.startsWith("pay|") -> {
                    ledger.update { it.signs++ }
                    Blobs.FEE
                }
                else -> return fake
            }
            return listOf(listOf(blob))
        }

        override suspend fun submit(signedB64: List<String>, waitRounds: Int): List<String> {
            if (hang == Hang048.BEFORE_NETWORK) {
                reached.complete(Unit)
                awaitCancellation()
            }
            val txId = requireNotNull(SignedTxnReader.parse(signedB64.single())?.txId) { "unreadable blob" }
            val isOptIn = signedB64.single() in optInBlobs
            ledger.update {
                // In an opt-in scenario `submits` counts opt-ins only: the stake sheet goes on to a stake transfer after its opt-in lands.
                if (!optInManaged || isOptIn) it.submits++
                it.chain[txId] = it.algodRound
                it.log += (if (isOptIn) "optin-submit#" else "submit#") + "${it.submits} $txId"
            }
            if (hang == Hang048.CANCEL_AFTER_SUBMIT) {
                kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                reached.complete(Unit)
            }
            return listOf(txId)
        }
    }

    val stakes: StakeRepository = object : StakeRepository by stakesBacking {
        override suspend fun submit(context: StakeContext, payload: StakeSubmitPayload): StakeSubmitResponse {
            if (hang == Hang048.BEFORE_RECORD) {
                reached.complete(Unit)
                awaitCancellation()
            }
            ledger.update {
                it.recordCalls++
                it.recorded += payload.txId
                it.log += "dashboard-record ${payload.txId}"
            }
            return StakeSubmitResponse(success = true, txId = "RECORDEDTX")
        }
    }

    val rewards: RewardsRepository = rewardsBacking

    private val realStore = SharedPrefsPendingTransferStore(prefs)
    val store: PendingTransferStore = object : PendingTransferStore by realStore {
        override fun remove(sender: String, kind: TransferKind, minerKey: String, row: String): Boolean =
            if (hang == Hang048.REMOVE_FAILS) false else realStore.remove(sender, kind, minerKey, row)
    }
    val bindings = SharedPrefsClaimFeeBindingStore(bindingPrefs)

    fun session(address: String): SessionRepository {
        wallet.reconnectAddress = address
        return TestSession.signedIn(address)
    }

    fun stakeVm(session: SessionRepository) = StakeViewModel(
        miners, rewards, stakes, algod, bridge, session, clock,
        openUri = {}, inhibitor = null, store = store, resolver = resolver, ioDispatcher = Dispatchers.IO,
    )

    fun claimVm(session: SessionRepository) = ClaimViewModel(
        rewards, algod, miners, bridge, session, clock,
        openUri = {}, pollDelayMillis = 0, optInRetryDelayMillis = 0, store = store, resolver = resolver, ioDispatcher = Dispatchers.IO,
        bindings = bindings, bindingDispatcher = Dispatchers.IO,
    )

    fun resetWorld() {
        ledger.reset()
        check(prefs.edit().clear().commit()) { "could not clear the test store" }
        check(bindingPrefs.edit().clear().commit()) { "could not clear the test binding store" }
    }

    companion object {
        const val BINDING_PREFS = "fry_claim_fee_binding_devtest"
    }
}
