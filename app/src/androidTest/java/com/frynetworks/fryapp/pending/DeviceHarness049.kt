package com.frynetworks.fryapp.pending

import android.content.Context
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.data.dashboard.api.IndexerApi
import com.frynetworks.fryapp.data.dashboard.model.ClaimEnvelopeResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimPreviewResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimTotalDto
import com.frynetworks.fryapp.data.dashboard.model.ConfirmResultResponse
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.FeeAwareRewardsRepository
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.data.dashboard.repo.TxStatus
import com.frynetworks.fryapp.data.dashboard.repo.impl.TxResolverImpl
import com.frynetworks.fryapp.data.pending.SharedPrefsClaimFeeBindingStore
import com.frynetworks.fryapp.data.pending.SharedPrefsPendingTransferStore
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.fakes.FakeAlgodRepository
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.FakeRewardsRepository
import com.frynetworks.fryapp.fakes.FakeWalletBridge
import com.frynetworks.fryapp.fakes.TestSession
import com.frynetworks.fryapp.fakes.fixedClock
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.ui.miners.claim.ClaimViewModel
import com.frynetworks.fryapp.util.SignedTxnReader
import com.frynetworks.fryapp.wallet.TxnToSign
import com.frynetworks.fryapp.wallet.WalletBridge
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.io.File
import java.math.BigDecimal

/** The one fake-world edge a 0.4.9 phase bends. */
enum class Edge049 {
    /** The dashboard refuses the claim envelope with 402 FEE_INVALID. */
    FEE_INVALID,

    /** The custodial confirm answers CLAIM_EXPIRED with `terminal: true`, every time. */
    CUSTODIAL_TERMINAL,
}

/**
 * The fake world of one 0.4.9 scenario phase: a custodial claim over the 0.4.8 world's fake chain, [DeviceLedger] and fee blobs
 * ([FeeBlobs048], reused unchanged), in the debug app's own filesDir. Every envelope request logs the fee txid it carried
 * (`envelope fee=<id>`), so "the same fee is resent, never a second one" is read from the ledger.
 */
class DeviceHarness049(val context: Context, val edge: Edge049) {
    val ledger = DeviceLedger(File(context.filesDir, "pending-ledger.json"))
    val prefs = context.getSharedPreferences(DeviceHarness.PREFS, Context.MODE_PRIVATE)
    val bindingPrefs = context.getSharedPreferences(DeviceHarness048.BINDING_PREFS, Context.MODE_PRIVATE)

    val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val clock = fixedClock(1_757_592_000_000L)

    private val backing = FakeAlgodRepository().also {
        it.markOptedIn(FryAsset.TFRY.id, 200_000_000L)
        it.markOptedIn(FryAsset.FNODE.id)
    }
    private val miners = FakeMinerRepository()
    private val rewardsBacking = FakeRewardsRepository().also {
        it.preview = ClaimPreviewResponse(success = true, preview = true, totals = listOf(ClaimTotalDto(FryAsset.FNODE.id.toString(), BigDecimal("4.25"))))
        it.envelope = ClaimEnvelopeResponse(success = true, txId = "CUSTODIALTX")
        when (edge) {
            Edge049.FEE_INVALID -> it.envelopeError = DashboardException("FEE_INVALID", "fee refused", httpStatus = 402)
            Edge049.CUSTODIAL_TERMINAL ->
                it.custodialResults = listOf(ConfirmResultResponse(ok = null, success = false, code = "CLAIM_EXPIRED", terminal = true))
        }
    }
    private val wallet = FakeWalletBridge()

    val algod: AlgodRepository = object : AlgodRepository by backing {
        override suspend fun txStatus(txId: String): TxStatus =
            ledger.read().chain[txId]?.let { TxStatus.Confirmed(it) } ?: TxStatus.Absent

        override suspend fun currentRound(): Long? = ledger.read().algodRound
        override suspend fun poolHasNoRecord(txId: String): Boolean = txId !in ledger.read().chain
        override suspend fun waitConfirmed(txId: String, rounds: Int) = kotlinx.coroutines.yield()
    }

    private val indexer = object : IndexerApi {
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

    private val resolver = TxResolverImpl(algod, indexer, listOf(DeviceHarness.IDX1, DeviceHarness.IDX2))

    private val bridge: WalletBridge = object : WalletBridge by wallet {
        override suspend fun signTxns(groups: List<List<TxnToSign>>): List<List<String?>> {
            val fake = wallet.signTxns(groups)
            val only = groups.singleOrNull()?.singleOrNull() ?: return fake
            if (!only.txnB64.startsWith("pay|")) return fake
            val n = ledger.update { it.signs++; it.signs }
            return listOf(listOf(FeeBlobs048.signed(n)))
        }

        override suspend fun submit(signedB64: List<String>, waitRounds: Int): List<String> {
            val txId = requireNotNull(SignedTxnReader.parse(signedB64.single())?.txId) { "unreadable blob" }
            ledger.update {
                it.submits++
                it.chain[txId] = it.algodRound
                it.log += "submit#${it.submits} $txId"
            }
            return listOf(txId)
        }
    }

    private val rewards: RewardsRepository = object : FeeAwareRewardsRepository, RewardsRepository by rewardsBacking {
        override suspend fun claimEnvelope(minerKey: String, no: Int?, feeTxId: String?): ClaimEnvelopeResponse {
            ledger.update { it.log += "envelope fee=$feeTxId" }
            return rewardsBacking.claimEnvelope(minerKey, no)
        }
    }

    private val realStore = SharedPrefsPendingTransferStore(prefs)
    val store = realStore
    val bindings = SharedPrefsClaimFeeBindingStore(bindingPrefs)

    fun session(address: String): SessionRepository {
        wallet.reconnectAddress = address
        return TestSession.signedIn(address)
    }

    fun claimVm(session: SessionRepository) = ClaimViewModel(
        rewards, algod, miners, bridge, session, clock,
        openUri = {}, pollDelayMillis = 0, optInRetryDelayMillis = 0, store = store, resolver = resolver, ioDispatcher = Dispatchers.IO,
        bindings = bindings, bindingDispatcher = Dispatchers.IO,
    )

    /** The fee txids the envelope requests carried, in order. */
    fun envelopeFees(): List<String> = ledger.read().log.filter { it.startsWith("envelope fee=") }.map { it.removePrefix("envelope fee=") }

    fun resetWorld() {
        ledger.reset()
        check(prefs.edit().clear().commit()) { "could not clear the test store" }
        check(bindingPrefs.edit().clear().commit()) { "could not clear the test binding store" }
    }
}
