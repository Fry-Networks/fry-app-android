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
import com.frynetworks.fryapp.data.dashboard.repo.StakeRepository
import com.frynetworks.fryapp.data.dashboard.repo.StakeSubmitPayload
import com.frynetworks.fryapp.data.dashboard.repo.TxStatus
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.data.dashboard.repo.impl.TxResolverImpl
import com.frynetworks.fryapp.data.pending.PendingTransfer
import com.frynetworks.fryapp.data.pending.PendingTransferStore
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
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal

/** Where a phase deliberately stops forever, as a process killed at that instant would. */
enum class Hang {
    NONE, BEFORE_NETWORK, BEFORE_RECORD, BEFORE_REMOVE, BEFORE_ENVELOPE,

    /** Not a stop: the claim envelope answers REWARD_ALREADY_CLAIMING (409), as the dashboard does while another claim is in flight. */
    ENVELOPE_ALREADY_CLAIMING,

    /** Not a stop: the job that called submit is cancelled the moment submit has reached the chain and returns. */
    CANCEL_AFTER_SUBMIT,

    /** Not a stop: submit reaches the chain but answers ANOTHER txid than the signed bytes give, and the calling job is cancelled as it returns. */
    MISMATCH_AND_CANCEL,
}

/**
 * The whole fake world of one scenario, kept in the debug app's own filesDir so it outlives every process kill and `install -r`:
 * the counter of submits that reached the "network", which transactions are on the "chain", what the "dashboard" recorded and
 * the round each source reports. Nothing here talks to a real network.
 */
class DeviceLedger(private val file: File) {
    class State {
        var submits = 0
        var signs = 0
        var algodRound = 1_000L
        var idx1Round = 1_000L
        var idx2Round = 1_000L
        var recordCalls = 0
        var lastUpdateTime = 0L
        val chain: MutableMap<String, Long> = LinkedHashMap()
        val recorded: MutableSet<String> = LinkedHashSet()
        val log: MutableList<String> = ArrayList()
    }

    private val gson = Gson()

    @Synchronized
    fun read(): State = if (file.exists()) gson.fromJson(file.readText(), State::class.java) else State()

    @Synchronized
    fun <T> update(block: (State) -> T): T {
        val state = read()
        val result = block(state)
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(gson.toJson(state).toByteArray())
            out.fd.sync()
        }
        check(tmp.renameTo(file)) { "ledger rename failed" }
        return result
    }

    @Synchronized
    fun reset() {
        file.delete()
    }

    fun setRounds(algod: Long, idx1: Long, idx2: Long) = update {
        it.algodRound = algod
        it.idx1Round = idx1
        it.idx2Round = idx2
        it.log += "rounds algod=$algod idx1=$idx1 idx2=$idx2"
    }
}

/** Real signed blobs (algosdk 3.7.0, throwaway key, never broadcast), the same ones the JVM tests use. */
object Blobs {
    const val STAKE_1500 = "gqNzaWfEQOgumRWlPG+C/xnCYYeu0Parj8rqN31ruUF09EiPWnHQYd6zfXMqZ6iH3AJb1aqnsj3P4iN9f5jZhNWjZHCyDwKjdHhuiqRhYW10zgBMS0CkYXJjdsQg/RckOFqgx1tk+3jNYC+h2ZH96/drE8WO1wLqyDXp9hijZmVlzQPoomZ2zQPoo2dlbqxtYWlubmV0LXYxLjCiZ2jEIMBhxNj8Hb3e0tdgS+RWjj9tBBmHrDe95LYgtas5JIrfomx2zQXco3NuZMQg6kpsY+KcUgq+9VB7Ey7F+ZVHdq6+vnuSQh7qaRRG0iykdHlwZaVheGZlcqR4YWlkzp/Uxu0="
    const val STAKE_1500_ID = "SPWQWS7VMRTDHZWM2VTUGF6INVO3BXZYGSAW7G4U6FJAUDI4VOUQ"
    const val STAKE_5000 = "gqNzaWfEQMXiGsqdMWlSh+Cn1oQlfZIkElil8Ofksr/ZUmFcophGECVlMV29qqwBbVkKQdS3/2nyhVwdGzz1HueOr4MwkAyjdHhuiqRhYW10zgBMS0CkYXJjdsQg/RckOFqgx1tk+3jNYC+h2ZH96/drE8WO1wLqyDXp9hijZmVlzQPoomZ2zQPoo2dlbqxtYWlubmV0LXYxLjCiZ2jEIMBhxNj8Hb3e0tdgS+RWjj9tBBmHrDe95LYgtas5JIrfomx2zROIo3NuZMQg6kpsY+KcUgq+9VB7Ey7F+ZVHdq6+vnuSQh7qaRRG0iykdHlwZaVheGZlcqR4YWlkzp/Uxu0="
    const val STAKE_5000_ID = "SJF3LQCVVM4AWZF4WAW7UENC5Z2ATRJ56QIBX5SM2XA7QDFTCTQA"
    const val FEE = "gqNzaWfEQJ3tQu0YfcH7nHqxFeCRswiuwanJNK7WsUFubIbmoqnNo0JpuyJ9kOllpU0pLNaTKBfHdKP5Qqae3qFZ6QKStAOjdHhuiaNhbXTNA+ijZmVlzQPoomZ2zgPvhf2jZ2VurG1haW5uZXQtdjEuMKJnaMQgwGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit+ibHbOA++J5aNyY3bEIP0XJDhaoMdbZPt4zWAvodmR/ev3axPFjtcC6sg16fYYo3NuZMQg6kpsY+KcUgq+9VB7Ey7F+ZVHdq6+vnuSQh7qaRRG0iykdHlwZaNwYXk="
    const val FEE_ID = "3TMZOQPUKWBLEVC2CQZCDUFS2O2KTDD3MHNC5JGZ4UWCF442SFWA"
    const val FEE_LV = 66_030_053L
}

/** Everything a scenario phase needs, wired from the ledger. [reached] completes when a [hang] point is hit. */
class DeviceHarness(val context: Context, val hang: Hang) {
    val ledger = DeviceLedger(File(context.filesDir, "pending-ledger.json"))
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val reached = CompletableDeferred<Unit>()

    val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val clock = fixedClock(1_757_592_000_000L)

    private val backing = FakeAlgodRepository().also {
        it.markOptedIn(FryAsset.TFRY.id, 200_000_000L)
        it.markOptedIn(FryAsset.FNODE.id)
    }
    val miners = FakeMinerRepository().also {
        it.products = mapOf(
            key to Product(
                key = "FEM",
                reward = ProductReward(
                    stake = ProductStake(stakeOne = BigDecimal("5"), stakeTwo = BigDecimal("20"), register = BigDecimal("40"), node = BigDecimal("100")),
                    tokens = ProductTokens(stake = "tFRY", reward = "tFRY", register = "tFRY", node = "fNODE"),
                ),
            ),
        )
    }
    private val rewardsBacking = FakeRewardsRepository().also {
        it.priceTable = mapOf(FryAsset.TFRY.id to BigDecimal("0.5"))
        it.preview = ClaimPreviewResponse(success = true, preview = true, totals = listOf(ClaimTotalDto(FryAsset.FNODE.id.toString(), BigDecimal("4.25"))))
        it.envelope = ClaimEnvelopeResponse(success = true, txId = "CUSTODIALTX")
        it.custodialResults = listOf(ConfirmResultResponse(ok = true, txId = "CUSTODIALTX"))
    }
    private val stakesBacking = FakeStakeRepository()
    private val wallet = FakeWalletBridge()

    val algod: AlgodRepository = object : AlgodRepository by backing {
        override suspend fun txStatus(txId: String): TxStatus =
            ledger.read().chain[txId]?.let { TxStatus.Confirmed(it) } ?: TxStatus.Absent

        override suspend fun currentRound(): Long? = ledger.read().algodRound
        override suspend fun poolHasNoRecord(txId: String): Boolean = txId !in ledger.read().chain
        override suspend fun waitConfirmed(txId: String, rounds: Int) = Unit
    }

    /** Two indexers, one per URL, answering from the ledger: a JSON 404 naming the txid, or the transaction. */
    val indexer = object : IndexerApi {
        private val json = "application/json".toMediaType()
        override suspend fun get(url: String): Response<ResponseBody> {
            val state = ledger.read()
            val round = if (url.startsWith(IDX1)) state.idx1Round else state.idx2Round
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

    val resolver = TxResolverImpl(algod, indexer, listOf(IDX1, IDX2))

    val bridge: WalletBridge = object : WalletBridge by wallet {
        override suspend fun signTxns(groups: List<List<TxnToSign>>): List<List<String?>> {
            val fake = wallet.signTxns(groups)
            val only = groups.singleOrNull()?.singleOrNull() ?: return fake
            val blob = when {
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
            if (hang == Hang.BEFORE_NETWORK) {
                reached.complete(Unit)
                awaitCancellation()
            }
            val txId = requireNotNull(SignedTxnReader.parse(signedB64.single())?.txId) { "unreadable blob" }
            ledger.update {
                it.submits++
                it.chain[txId] = it.algodRound
                it.log += "submit#${it.submits} $txId"
            }
            if (hang == Hang.CANCEL_AFTER_SUBMIT) {
                kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                reached.complete(Unit)
            }
            if (hang == Hang.MISMATCH_AND_CANCEL) {
                kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                reached.complete(Unit)
                return listOf("MISMATCHEDTX")
            }
            return listOf(txId)
        }
    }

    val stakes: StakeRepository = object : StakeRepository by stakesBacking {
        override suspend fun submit(context: StakeContext, payload: StakeSubmitPayload): StakeSubmitResponse {
            if (hang == Hang.BEFORE_RECORD) {
                reached.complete(Unit)
                awaitCancellation()
            }
            // The dashboard keys a stake by its txid: recording the same one again changes nothing.
            ledger.update {
                it.recordCalls++
                it.recorded += payload.txId
                it.log += "dashboard-record ${payload.txId}"
            }
            return StakeSubmitResponse(success = true, txId = "RECORDEDTX")
        }
    }

    val rewards: RewardsRepository = object : RewardsRepository by rewardsBacking {
        override suspend fun claimEnvelope(minerKey: String, no: Int?): ClaimEnvelopeResponse {
            if (hang == Hang.BEFORE_ENVELOPE) {
                reached.complete(Unit)
                awaitCancellation()
            }
            if (hang == Hang.ENVELOPE_ALREADY_CLAIMING) {
                throw com.frynetworks.fryapp.network.dashboard.DashboardException("REWARD_ALREADY_CLAIMING", "in flight", httpStatus = 409)
            }
            return rewardsBacking.claimEnvelope(minerKey, no)
        }
    }

    /** The real store over its own SharedPreferences file; only remove() can be made to stop forever. */
    private val realStore = SharedPrefsPendingTransferStore(prefs)
    val store: PendingTransferStore = object : PendingTransferStore by realStore {
        override fun remove(sender: String, kind: TransferKind, minerKey: String, row: String): Boolean {
            if (hang == Hang.BEFORE_REMOVE) {
                reached.complete(Unit)
                Thread.sleep(Long.MAX_VALUE)
            }
            return realStore.remove(sender, kind, minerKey, row)
        }
    }

    /** Wallet reconnect answers the signed-in address, as a paired wallet of that account would. */
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
    )

    fun resetWorld() {
        ledger.reset()
        check(prefs.edit().clear().commit()) { "could not clear the test store" }
    }

    companion object {
        const val PREFS = "fry_pending_transfer_devtest"
        const val IDX1 = "https://idx1.devtest.invalid"
        const val IDX2 = "https://idx2.devtest.invalid"
        val REGISTRATION = StakeContext.Registration
    }
}
