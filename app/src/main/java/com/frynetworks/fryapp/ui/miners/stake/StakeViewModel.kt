package com.frynetworks.fryapp.ui.miners.stake

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionState
import android.util.Log
import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.MinerListItem
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.data.dashboard.repo.StakeRepository
import com.frynetworks.fryapp.data.dashboard.repo.StakeSubmitPayload
import com.frynetworks.fryapp.data.dashboard.repo.FailClosedTxResolver
import com.frynetworks.fryapp.data.dashboard.repo.TxResolution
import com.frynetworks.fryapp.data.dashboard.repo.TxResolver
import com.frynetworks.fryapp.data.dashboard.repo.TxStatus
import com.frynetworks.fryapp.data.pending.InMemoryPendingTransferStore
import com.frynetworks.fryapp.data.pending.PendingLookup
import com.frynetworks.fryapp.data.pending.PendingTransfer
import com.frynetworks.fryapp.data.pending.PendingTransferStore
import com.frynetworks.fryapp.data.pending.TransferKind
import com.frynetworks.fryapp.data.pending.TransferState
import com.frynetworks.fryapp.util.SignedTxnReader
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.domain.StakeEvent
import com.frynetworks.fryapp.domain.StakeMath
import com.frynetworks.fryapp.domain.StakeState
import com.frynetworks.fryapp.domain.StakeTier
import com.frynetworks.fryapp.domain.reduce
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.network.dashboard.JsJson
import com.frynetworks.fryapp.network.dashboard.ServerClock
import com.frynetworks.fryapp.ui.common.ErrorCopy
import com.frynetworks.fryapp.ui.common.errorCode
import com.frynetworks.fryapp.update.InstallInhibitor
import com.frynetworks.fryapp.ui.miners.claim.StageStatus
import com.frynetworks.fryapp.wallet.BridgeErrorCode
import com.frynetworks.fryapp.wallet.BridgeEvent
import com.frynetworks.fryapp.wallet.BridgeException
import com.frynetworks.fryapp.wallet.TxnSummary
import com.frynetworks.fryapp.wallet.TxnToSign
import com.frynetworks.fryapp.wallet.WalletBridge
import com.frynetworks.fryapp.wallet.WalletVendor
import com.frynetworks.fryapp.wallet.bridge.ExternalUriLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** The durable record of a stake transfer that may be on the network, with the context it was made for. */
private data class PendingBroadcast(val transfer: PendingTransfer, val context: StakeContext) {
    /** null for a transfer handed to submit with no id to resume from (state POSSIBLY_BROADCAST). */
    val txId: String? get() = transfer.txId
    val minerKey: String get() = transfer.minerKey
    val address: String get() = transfer.sender
    val amount: BigDecimal get() = BigDecimal(transfer.amount)

    /** Last round the transfer can still land in, decoded from the signed bytes; null = unknown, which never allows a rebuild. */
    val lastValid: Long? get() = transfer.lastValid
}

private sealed interface Pending {
    data object None : Pending

    /** A record exists that cannot be understood: blocks like any other. */
    data object Unreadable : Pending

    data class Found(val sent: PendingBroadcast) : Pending
}

private const val CODE_TXN_PENDING = "TXN_PENDING"
private const val CODE_RECORD_FAILED = "TXN_RECORD_FAILED"
private const val UNKNOWN_BROADCAST_MESSAGE =
    "A previous stake transfer may have been sent and is still pending. Do not try again: contact support so it can be checked."
private const val LOG_TAG = "StakeViewModel"
private const val CLEAR_ATTEMPTS = 3
private const val RECORD_FAILED_MESSAGE = "The stake could not be prepared safely on this phone, so nothing was sent. Try again."

/** The record could not be written, so the transfer must not be submitted. */
private class RecordNotSaved : RuntimeException()

/** The bridge's submit answered with another transaction id than the one the signed bytes give. */
private class TxIdMismatch : RuntimeException()

/** Rows of the sheet's stage list (`stake_stage_<n>`, 1-based). */
enum class StakeStage(val label: String) {
    WALLET("Wallet connected"),
    AMOUNT("Stake amount"),
    BALANCES("Balances and opt-in"),
    PRECHECK("Dashboard precheck"),
    SIGN("Sign the stake transfer"),
    SUBMIT("Confirm on-chain"),
    VERIFY("Verify with the dashboard"),
    RECORD("Record the stake"),
    DONE("Stake recorded"),
}

data class StakeUiState(
    val minerKey: String? = null,
    val context: StakeContext? = null,
    val byod: Boolean = false,
    /** null while the product/price are loading (before [StakeState.Loading] exists). */
    val state: StakeState? = null,
    val loading: Boolean = false,
    /** The amount/asset the user confirmed, kept once the reducer has moved past Ready. */
    val plan: StakeState.Ready? = null,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val needsReconnect: Boolean = false,
    val pendingSummary: TxnSummary? = null,
    val lastWalletUri: String? = null,
    val failedStage: Int? = null,
) {
    val canConfirm: Boolean get() = state is StakeState.Ready
    val canCancel: Boolean
        get() = state == null || state is StakeState.Loading || state is StakeState.Ready || state is StakeState.OptInRequired ||
            state is StakeState.Failed || state is StakeState.Done
    val awaitingWallet: Boolean get() = state is StakeState.AwaitingSignature || (state is StakeState.OptInRequired && pendingSummary != null)
    val errorCopy: String?
        get() = (state as? StakeState.Failed)?.let { ErrorCopy.forCode(it.code, it.message) }
    val currentStage: Int get() = failedStage ?: stageIndexOf(state, loading, needsReconnect || connecting || !connected)

    fun stages(): List<Pair<StakeStage, StageStatus>> {
        val current = currentStage
        val failed = state is StakeState.Failed
        val done = state is StakeState.Done
        return StakeStage.entries.mapIndexed { index, stage ->
            val status = when {
                done -> StageStatus.DONE
                index < current -> StageStatus.DONE
                index == current -> if (failed) StageStatus.FAILED else StageStatus.CURRENT
                else -> StageStatus.PENDING
            }
            stage to status
        }
    }

    companion object {
        fun stageIndexOf(state: StakeState?, loading: Boolean, walletPending: Boolean): Int = when (state) {
            null -> if (walletPending && !loading) StakeStage.WALLET.ordinal else StakeStage.AMOUNT.ordinal
            is StakeState.Loading, is StakeState.Ready -> StakeStage.AMOUNT.ordinal
            StakeState.CheckingBalances, is StakeState.OptInRequired -> StakeStage.BALANCES.ordinal
            StakeState.Precheck -> StakeStage.PRECHECK.ordinal
            StakeState.AwaitingSignature -> StakeStage.SIGN.ordinal
            is StakeState.Submitting -> StakeStage.SUBMIT.ordinal
            StakeState.Verifying -> StakeStage.VERIFY.ordinal
            StakeState.Recording -> StakeStage.RECORD.ordinal
            is StakeState.Done -> StakeStage.DONE.ordinal
            is StakeState.Failed -> StakeStage.WALLET.ordinal
        }
    }
}

/**
 * Drives [com.frynetworks.fryapp.domain.StakeFlow]'s reducer with the real side effects
 * (blueprint 5.8): product + price -> amount -> balances/opt-in -> precheck -> ASA transfer
 * to the stake wallet -> wait for confirmation -> verify-txn -> record with the dashboard.
 */
@HiltViewModel
class StakeViewModel(
    private val miners: MinerRepository,
    private val rewards: RewardsRepository,
    private val stakes: StakeRepository,
    private val algod: AlgodRepository,
    private val bridge: WalletBridge,
    private val session: SessionRepository,
    private val clock: ServerClock,
    private val openUri: (String) -> Unit,
    /** Held from the first signature to the dashboard record: an app update then would orphan an on-chain transfer. */
    private val inhibitor: InstallInhibitor? = null,
    /** Durable record of a transfer that may be on the network; survives process death, logout and account switches. */
    private val store: PendingTransferStore = InMemoryPendingTransferStore(),
    /** What became of a recorded transfer; the default can never say it is gone, so it never allows a rebuild. */
    private val resolver: TxResolver = FailClosedTxResolver,
    /** Where the store (disk) is touched; production passes IO, the default runs inline. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) : ViewModel() {

    @Inject
    constructor(
        miners: MinerRepository,
        rewards: RewardsRepository,
        stakes: StakeRepository,
        algod: AlgodRepository,
        bridge: WalletBridge,
        session: SessionRepository,
        clock: ServerClock,
        launcher: ExternalUriLauncher,
        inhibitor: InstallInhibitor,
        store: PendingTransferStore,
        resolver: TxResolver,
    ) : this(
        miners, rewards, stakes, algod, bridge, session, clock,
        openUri = { uri -> launcher.open(uri, null) }, inhibitor = inhibitor, store = store, resolver = resolver, ioDispatcher = Dispatchers.IO,
    )

    private val ui = MutableStateFlow(StakeUiState())
    val uiState: StateFlow<StakeUiState> = ui.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            bridge.events.collect { event ->
                if (event is BridgeEvent.OpenUri) ui.update { it.copy(lastWalletUri = event.uri) }
            }
        }
    }

    fun start(minerKey: String, context: StakeContext, byod: Boolean) {
        if (job?.isActive == true && !ui.value.canCancel) return
        job?.cancel()
        ui.value = StakeUiState(minerKey = minerKey, context = context, byod = byod, loading = true, lastWalletUri = ui.value.lastWalletUri)
        job = viewModelScope.launch {
            if (resumeStored(minerKey, context)) return@launch
            if (ensureConnected(reconnectOnly = true)) loadAmount()
        }
    }

    /**
     * A transfer recorded for this sender (before a crash, a logout, an update) is continued from the sheet's first moment,
     * never rebuilt. True when it handled the open; false when there is no record or it was proven dead and cleared.
     */
    private suspend fun resumeStored(minerKey: String, context: StakeContext): Boolean {
        val address = address() ?: return false
        return when (val pending = pendingFor(address)) {
            Pending.None -> false
            Pending.Unreadable -> {
                blockedUnknown()
                true
            }
            is Pending.Found -> {
                val sent = pending.sent
                if (sent.minerKey != minerKey || sent.context != context) {
                    stillPending(sent, "Another stake transaction is still being processed. Retry to finish it first.")
                    true
                } else {
                    var outcome = Resumed.HANDLED
                    holdingInstalls { outcome = resume(sent) }
                    outcome == Resumed.HANDLED
                }
            }
        }
    }

    /** Verification only: switch between the `one` (1 day) and `two` (180 day) tiers. */
    fun selectTier(tier: StakeTier) {
        val key = ui.value.minerKey ?: return
        if (ui.value.context !is StakeContext.Verification) return
        start(key, StakeContext.Verification(tier), ui.value.byod)
    }

    fun reconnectWallet() {
        if (ui.value.connecting) return
        job?.cancel()
        job = viewModelScope.launch { if (ensureConnected(reconnectOnly = false)) loadAmount() }
    }

    /** `stake_confirm` on the Ready step. */
    fun confirm() {
        if (!ui.value.canConfirm) return
        job?.cancel()
        job = viewModelScope.launch { holdingInstalls { runBalances() } }
    }

    /** `stake_optin`. */
    fun optIn() {
        if (ui.value.state !is StakeState.OptInRequired) return
        job?.cancel()
        job = viewModelScope.launch { holdingInstalls { runOptIn() } }
    }

    /** A signed chain transaction may be in flight: no app update may replace the process meanwhile. */
    private suspend fun holdingInstalls(block: suspend () -> Unit) {
        inhibitor?.acquire()
        try {
            block()
        } finally {
            inhibitor?.release()
        }
    }

    fun cancel() {
        if (!ui.value.canCancel) return
        job?.cancel()
        ui.update {
            val next = when (val s = it.state) {
                null -> null
                else -> reduce(s, StakeEvent.Cancel)
            }
            it.copy(state = next, loading = false, pendingSummary = null, connecting = false, needsReconnect = false)
        }
    }

    fun retry() {
        job?.cancel()
        job = viewModelScope.launch {
            val address = address()
            when (val pending = if (address == null) Pending.None else pendingFor(address)) {
                Pending.Unreadable -> blockedUnknown()
                is Pending.Found -> holdingInstalls { if (resume(pending.sent) == Resumed.REBUILD) restartFresh() }
                Pending.None -> {
                    val key = ui.value.minerKey ?: return@launch
                    val context = ui.value.context ?: return@launch
                    start(key, context, ui.value.byod)
                }
            }
        }
    }

    fun openWallet() {
        ui.value.lastWalletUri?.let(openUri)
    }

    // --- pipeline ----------------------------------------------------------------------------

    private fun address(): String? = session.signedInAddress

    private suspend fun ensureConnected(reconnectOnly: Boolean): Boolean {
        val profile = (session.state.value as? SessionState.SignedIn)?.profile
        if (profile == null) {
            failDirect("UNAUTHORIZED", ErrorCopy.forCode("UNAUTHORIZED"))
            return false
        }
        val vendor = profile.vendor ?: WalletVendor.PERA
        ui.update { it.copy(connecting = true, needsReconnect = false) }
        val account = try {
            bridge.reconnect(vendor) ?: if (reconnectOnly) null else bridge.connect(vendor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BridgeException) {
            if (reconnectOnly) {
                null
            } else {
                ui.update { it.copy(connecting = false) }
                failDirect(e.code.name, e.message)
                return false
            }
        } catch (e: IllegalStateException) {
            // A reply of the wrong shape (cancellation is rethrown above).
            ui.update { it.copy(connecting = false) }
            failDirect(e)
            return false
        }
        if (account == null) {
            ui.update { it.copy(connecting = false, needsReconnect = true, connected = false, loading = false) }
            return false
        }
        if (account.address != profile.address) {
            ui.update { it.copy(connecting = false, connected = false) }
            failDirect("WALLET_MISMATCH", ErrorCopy.forCode("WALLET_MISMATCH"))
            return false
        }
        ui.update { it.copy(connecting = false, connected = true, needsReconnect = false) }
        return true
    }

    private suspend fun loadAmount() {
        val key = ui.value.minerKey ?: return
        val context = ui.value.context ?: return
        ui.update { it.copy(loading = true) }
        val product = try {
            miners.product(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failDirect(e)
            return
        }
        if (product == null) {
            failDirect("NO_PRODUCT", ErrorCopy.forCode("NO_PRODUCT"))
            return
        }
        val asset = assetFor(product, context, MinerFamily.fromMinerKey(key))
        val price = try {
            rewards.prices(listOf(asset.id))[asset.id]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failDirect(e)
            return
        }
        if (price == null) {
            failDirect("INVALID_PRICE", ErrorCopy.forCode("INVALID_PRICE"))
            return
        }
        ui.update { it.copy(loading = false, state = StakeState.Loading(product, price, context, ui.value.byod, asset)) }
        dispatch(StakeEvent.Start)
    }

    private suspend fun runBalances() {
        val plan = ui.value.state as? StakeState.Ready ?: return
        val address = address() ?: return
        dispatch(StakeEvent.AmountConfirmed)
        val (assetBalance, algoBalance) = try {
            algod.assetBalanceMicro(address, plan.asset.id) to algod.algoBalanceMicro(address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failDirect(e)
            return
        }
        val needed = microFor(plan.amount)
        dispatch(
            StakeEvent.BalancesChecked(
                // Not opted in means no balance yet; opt in first, then re-check (see runOptIn).
                hasAssetBalance = assetBalance == null || assetBalance >= needed,
                hasAlgoBuffer = algoBalance >= StakeRepository.MIN_ALGO_BUFFER_MICRO,
                optedIn = assetBalance != null,
                assetId = plan.asset.id,
            ),
        )
        if (ui.value.state is StakeState.Precheck) runPrecheckAndStake(plan)
    }

    private suspend fun runOptIn() {
        val plan = ui.value.plan ?: return
        val address = address() ?: return
        try {
            val txn = bridge.buildOptIn(address, plan.asset.id)
            val signed = signSingle(txn)
            bridge.submit(listOf(signed), WAIT_ROUNDS)
            val balance = algod.assetBalanceMicro(address, plan.asset.id)
            dispatch(StakeEvent.OptInCompleted(balance != null, plan.asset.id))
            if (ui.value.state !is StakeState.Precheck) return
            if ((balance ?: 0L) < microFor(plan.amount)) {
                dispatch(StakeEvent.PrecheckFailed("INSUFFICIENT_BALANCE", ErrorCopy.forCode("INSUFFICIENT_BALANCE")))
                return
            }
            runPrecheckAndStake(plan)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BridgeException) {
            clearSummary()
            if (e.code == BridgeErrorCode.USER_REJECTED) dispatch(StakeEvent.SignRejected) else failDirect(e)
        } catch (e: Exception) {
            clearSummary()
            failDirect(e)
        }
    }

    private suspend fun runPrecheckAndStake(plan: StakeState.Ready) {
        val key = ui.value.minerKey ?: return
        val context = ui.value.context ?: return
        val address = address() ?: run {
            dispatch(StakeEvent.PrecheckFailed("UNAUTHORIZED", ErrorCopy.forCode("UNAUTHORIZED")))
            return
        }

        // A transfer for this stake may already be on the network: continue it, never send a second one.
        when (val pending = pendingFor(address)) {
            Pending.None -> Unit
            Pending.Unreadable -> {
                // Something is recorded that cannot be understood: build nothing until the user has checked.
                dispatch(StakeEvent.PrecheckFailed(CODE_TXN_PENDING, UNKNOWN_BROADCAST_MESSAGE))
                return
            }
            is Pending.Found -> {
                val sent = pending.sent
                if (sent.minerKey != key || sent.context != context) {
                    // Another stake is unresolved: fail closed, show its txId, build nothing (Retry resumes it).
                    stillPending(sent, "Another stake transaction is still being processed. Retry to finish it first.")
                    return
                }
                if (resume(sent) == Resumed.HANDLED) return
            }
        }

        try {
            stakes.precheck(key, address, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DashboardException) {
            val retryAfter = if (e.code == "RATE_LIMIT_EXCEEDED") e.action?.trim()?.toIntOrNull() else null
            dispatch(StakeEvent.PrecheckFailed(e.code, e.message, retryAfter))
            return
        } catch (e: Exception) {
            dispatch(StakeEvent.PrecheckFailed(e.errorCode(), e.message ?: ""))
            return
        }
        dispatch(StakeEvent.PrecheckPassed)

        val amountMicro = microFor(plan.amount)
        // Stake.tsx:492-512 parity: human action label, whole-token amount and an ISO-8601
        // timestamp (the ASA transfer itself carries the micro amount). No server route reads it.
        val notePayload = StakeMath.stakeNote(
            action = actionFor(context),
            minerKeyShort = MinerListItem.shortKey(key),
            assetId = plan.asset.id,
            type = (context as? StakeContext.Verification)?.tier?.code,
            from = address,
            to = StakeRepository.STAKE_WALLET,
            amount = plan.amount,
            operation = operationFor(context),
            timestampMillis = clock.nowMillis(),
        )
        notePayload["timestamp"] = isoTimestamp(clock.nowMillis())
        val note = JsJson.stringify(notePayload)
        var recorded: PendingTransfer? = null
        val (transfer, txId) = try {
            val txn = bridge.buildAssetTransfer(address, StakeRepository.STAKE_WALLET, plan.asset.id, amountMicro, note)
            val builtRound = safely { algod.currentRound() }
            val summary = safely { bridge.decodeTxn(txn) }
            val signed = signSingle(txn)
            // The id and rounds come from the signed bytes themselves. Only when they cannot be read is the bridge's decode of
            // the unsigned transaction used for the id (and then without a last valid round, so it never allows a rebuild);
            // with neither, the record is POSSIBLY_BROADCAST and always blocks.
            val parsed = SignedTxnReader.parse(signed)
            val knownId = parsed?.txId ?: summary?.txId?.takeIf { it.isNotBlank() }
            val toSend = PendingTransfer(
                kind = TransferKind.STAKE,
                sender = address,
                minerKey = key,
                row = contextCode(context),
                assetId = plan.asset.id,
                amount = plan.amount.toPlainString(),
                amountMicro = amountMicro,
                receiver = StakeRepository.STAKE_WALLET,
                txId = knownId,
                firstValid = parsed?.firstValid,
                lastValid = parsed?.lastValid,
                builtRound = builtRound,
                state = if (knownId == null) TransferState.POSSIBLY_BROADCAST else TransferState.PRE_SUBMIT,
                createdAt = clock.nowMillis(),
            )
            // From here the transfer can reach the network whatever submit reports back: it is on disk first, and a record that
            // cannot be written means nothing is sent.
            if (!ioWrite { store.putNew(toSend) }) throw RecordNotSaved()
            recorded = toSend
            val returned = bridge.submit(listOf(signed), WAIT_ROUNDS).firstOrNull()?.takeIf { it.isNotBlank() }
                ?: throw BridgeException(BridgeErrorCode.UNKNOWN, "The bridge returned no transaction id")
            if (toSend.txId != null && returned != toSend.txId) throw TxIdMismatch()
            toSend to returned
        } catch (e: RecordNotSaved) {
            clearSummary()
            dispatch(StakeEvent.SubmitFailed(CODE_RECORD_FAILED, RECORD_FAILED_MESSAGE))
            return
        } catch (e: TxIdMismatch) {
            // Neither id can be trusted: keep the record, without an id, so only the account search can resolve it.
            recorded?.let { downgradeToUnidentified(it) }
            dispatch(StakeEvent.SubmitFailed(CODE_TXN_PENDING, UNKNOWN_BROADCAST_MESSAGE))
            return
        } catch (e: BridgeException) {
            clearSummary()
            val r = recorded
            if (e.code == BridgeErrorCode.USER_REJECTED && r == null) {
                dispatch(StakeEvent.SignRejected)
            } else if (r != null && r.txId == null) {
                dispatch(StakeEvent.SubmitFailed(CODE_TXN_PENDING, UNKNOWN_BROADCAST_MESSAGE))
            } else {
                dispatch(StakeEvent.SubmitFailed(e.code.name, e.message ?: "", r?.txId))
            }
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            clearSummary()
            val r = recorded
            if (r != null && r.txId == null) {
                dispatch(StakeEvent.SubmitFailed(CODE_TXN_PENDING, UNKNOWN_BROADCAST_MESSAGE))
            } else {
                dispatch(StakeEvent.SubmitFailed(e.errorCode(), e.message ?: "", r?.txId))
            }
            return
        }
        // submit gave the id: a transfer without one is identified by it from here on.
        val identified = if (transfer.txId == null) identify(transfer, txId) else transfer
        val sent = PendingBroadcast(identified, context)
        dispatch(StakeEvent.Submitted(txId))
        afterBroadcast(sent, Confirmation.WAIT)
    }

    /** Waits for confirmation (unless known), verifies with the dashboard and records the stake; every failure carries the txId. */
    private suspend fun afterBroadcast(sent: PendingBroadcast, confirmation: Confirmation) {
        val txId = sent.txId ?: return blockedUnknown()
        if (confirmation == Confirmation.WAIT) {
            try {
                algod.waitConfirmed(txId, WAIT_ROUNDS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dispatch(StakeEvent.SubmitFailed(e.errorCode(), e.message ?: "", txId))
                return
            }
        }
        dispatch(StakeEvent.TxnConfirmed)

        try {
            stakes.verifyTxn(sent.address, txId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not yet confirmed as far as this node can say: the dashboard not finding it either only means "keep waiting".
            if (confirmation == Confirmation.UNKNOWN) stillPending(sent) else dispatch(StakeEvent.VerifyFailed(e.errorCode(), e.message ?: "", txId))
            return
        }
        dispatch(StakeEvent.Verified)

        try {
            val result = stakes.submit(
                sent.context,
                StakeSubmitPayload(address = sent.address, minerKey = sent.minerKey, txId = txId, amount = sent.amount, assetId = sent.transfer.assetId),
            )
            // Cleared before the state moves on, so nothing can resume and record this stake a second time (a failed clear is retried, up to three tries, and logged).
            clear(sent)
            dispatch(StakeEvent.Recorded(result.txId ?: txId, result.waived))
            runCatching { miners.refreshDetail(sent.minerKey) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dispatch(StakeEvent.RecordFailed(e.errorCode(), e.message ?: "", txId))
        }
    }

    private enum class Resumed { HANDLED, REBUILD }

    /** How far the transfer is known to have got: still to wait for, confirmed by the node, or status unknown (pool entry aged out). */
    private enum class Confirmation { WAIT, CONFIRMED, UNKNOWN }

    /**
     * Continues a stake whose transfer may already be on the network, from its transaction id. A new transfer is
     * allowed ([Resumed.REBUILD]) only when the resolver proves the transaction dead and the record was cleared:
     * algod's pool and both indexers have never heard of it and every round is past its last valid round.
     * Anything unknown keeps the failure, with the txId, and builds nothing.
     */
    private suspend fun resume(sent: PendingBroadcast): Resumed {
        val txId = sent.txId ?: return resumeUnidentified(sent)
        val pool = safely { algod.txStatus(txId) } ?: TxStatus.Unknown
        when (pool) {
            is TxStatus.Confirmed -> {
                showSubmitting(sent)
                afterBroadcast(sent, Confirmation.CONFIRMED)
            }
            TxStatus.InPool -> {
                showSubmitting(sent)
                afterBroadcast(sent, Confirmation.WAIT)
            }
            is TxStatus.Rejected -> stillPending(sent)
            // The pool forgets a confirmed transaction after a while: the indexers know it, or know it is gone.
            TxStatus.Absent, TxStatus.Unknown -> when (verdict(sent, txId)) {
                is TxResolution.Confirmed -> {
                    showSubmitting(sent)
                    afterBroadcast(sent, Confirmation.CONFIRMED)
                }
                TxResolution.Absent -> {
                    if (clear(sent)) return Resumed.REBUILD
                    stillPending(sent)
                }
                // Ask the dashboard before giving up, but never build.
                TxResolution.Unknown -> if (pool == TxStatus.Absent) {
                    stillPending(sent)
                } else {
                    showSubmitting(sent)
                    afterBroadcast(sent, Confirmation.UNKNOWN)
                }
            }
        }
        return Resumed.HANDLED
    }

    /** The resolver's answer, with Absent refused when the last valid round is unknown and anything about another txid ignored. */
    private suspend fun verdict(sent: PendingBroadcast, txId: String): TxResolution {
        val answer = safely { resolver.resolve(txId, sent.lastValid) } ?: TxResolution.Unknown
        return when {
            answer == TxResolution.Absent && sent.lastValid == null -> TxResolution.Unknown
            answer is TxResolution.Confirmed && answer.txId != txId -> TxResolution.Unknown
            else -> answer
        }
    }

    /** A transfer handed to submit with no id to resume from: only the account search can identify it; it is never cleared automatically. */
    private suspend fun resumeUnidentified(sent: PendingBroadcast): Resumed {
        val t = sent.transfer
        val found = safely { resolver.findTransfer(t.sender, t.receiver, t.assetId, t.amountMicro, t.firstValid ?: t.builtRound) }
        if (found is TxResolution.Confirmed) {
            val identified = t.copy(txId = found.txId, state = TransferState.PRE_SUBMIT)
            if (!ioWrite { store.put(identified) }) Log.w(LOG_TAG, "could not record the id found for a pending stake transfer") // the unidentified record stays and still blocks
            val next = PendingBroadcast(identified, sent.context)
            showSubmitting(next)
            afterBroadcast(next, Confirmation.CONFIRMED)
        } else {
            blockedUnknown()
        }
        return Resumed.HANDLED
    }

    private fun showSubmitting(sent: PendingBroadcast) = ui.update {
        it.copy(state = StakeState.Submitting(sent.txId.orEmpty()), loading = false, failedStage = null, pendingSummary = null)
    }

    /** Nothing to resume from and nothing may be built: keep saying so. */
    private fun blockedUnknown() = ui.update {
        it.copy(
            state = StakeState.Failed(CODE_TXN_PENDING, UNKNOWN_BROADCAST_MESSAGE, recoverable = true),
            loading = false,
            failedStage = StakeStage.SUBMIT.ordinal,
        )
    }

    private fun stillPending(sent: PendingBroadcast, message: String = "The stake transaction may still be processed. Try again shortly.") = ui.update {
        it.copy(
            state = StakeState.Failed(CODE_TXN_PENDING, message, recoverable = true, txId = sent.txId),
            loading = false,
            failedStage = StakeStage.SUBMIT.ordinal,
            pendingSummary = null,
        )
    }

    /** After a provably dead transfer: the usual start-up, ending on a fresh amount step the user confirms again. */
    private suspend fun restartFresh() {
        val key = ui.value.minerKey ?: return
        val context = ui.value.context ?: return
        ui.value = StakeUiState(minerKey = key, context = context, byod = ui.value.byod, loading = true, lastWalletUri = ui.value.lastWalletUri)
        if (ensureConnected(reconnectOnly = true)) loadAmount()
    }

    /** The recorded transfer of [address], if any. */
    private suspend fun pendingFor(address: String): Pending = when (val found = io { store.find(address, TransferKind.STAKE) }) {
        PendingLookup.None -> Pending.None
        PendingLookup.Unreadable -> Pending.Unreadable
        is PendingLookup.Found -> contextFrom(found.record.row)?.let { Pending.Found(PendingBroadcast(found.record, it)) } ?: Pending.Unreadable
    }

    /** Removes the record once the dashboard has recorded the stake or the transfer is proven dead; false = it is still there. */
    private suspend fun clear(sent: PendingBroadcast): Boolean {
        val cleared = ioWrite { (1..CLEAR_ATTEMPTS).any { store.remove(sent.address, TransferKind.STAKE, sent.minerKey, sent.transfer.row) } }
        if (cleared) return true
        Log.w(LOG_TAG, "could not clear the pending stake record after $CLEAR_ATTEMPTS tries")
        return false
    }

    /** The submit result named another id than the signed bytes: the record loses its id (retried once) or, failing that, is blocked for this process. */
    private suspend fun downgradeToUnidentified(record: PendingTransfer) {
        // One uncancellable block (withContext throws on exit once its job is cancelled, so separate blocks would cut the sequence short).
        // Blocked in memory first, so a cancellation or a failed write can never leave this key open to a rebuild.
        val rewritten = ioWrite {
            store.blockPermanently(record.sender, record.kind, record.minerKey, record.row)
            val unidentified = record.copy(txId = null, state = TransferState.POSSIBLY_BROADCAST)
            store.put(unidentified) || store.put(unidentified)
        }
        if (!rewritten) Log.e(LOG_TAG, "could not rewrite a pending stake record after a txid mismatch: blocked for this process")
    }

    /** submit named the id of a transfer that had none: written down, or logged when that fails (the record then stays unidentified and blocks). */
    private suspend fun identify(transfer: PendingTransfer, txId: String): PendingTransfer {
        val identified = transfer.copy(txId = txId, state = TransferState.PRE_SUBMIT)
        if (!ioWrite { store.put(identified) }) Log.w(LOG_TAG, "could not record the id of a submitted stake transfer")
        return identified
    }

    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { block() }

    /** A store write once the transfer may be on the network: a cancelled job still completes it. */
    private suspend fun <T> ioWrite(block: () -> T): T = withContext(NonCancellable + ioDispatcher) { block() }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun signSingle(txnB64: String): String {
        val summary = runCatching { bridge.decodeTxn(txnB64) }.getOrNull()
        ui.update { it.copy(pendingSummary = summary) }
        val signed = bridge.signTxns(listOf(listOf(TxnToSign(txnB64, sign = true))))
        clearSummary()
        return signed.firstOrNull()?.firstOrNull()
            ?: throw BridgeException(BridgeErrorCode.USER_REJECTED, "Wallet returned no signature")
    }

    private fun clearSummary() = ui.update { it.copy(pendingSummary = null) }

    private fun dispatch(event: StakeEvent) = ui.update { current ->
        val state = current.state ?: return@update current
        val next = reduce(state, event)
        current.copy(
            state = next,
            plan = (next as? StakeState.Ready) ?: current.plan,
            failedStage = when {
                next is StakeState.Failed && state !is StakeState.Failed ->
                    StakeUiState.stageIndexOf(state, current.loading, current.needsReconnect || current.connecting || !current.connected)
                next is StakeState.Failed -> current.failedStage
                else -> null
            },
        )
    }

    private fun failDirect(code: String, message: String?) = ui.update {
        it.copy(
            state = StakeState.Failed(code, message ?: "", recoverable = true),
            loading = false,
            failedStage = it.failedStage ?: StakeUiState.stageIndexOf(it.state, it.loading, it.needsReconnect || it.connecting || !it.connected),
            pendingSummary = null,
        )
    }

    private fun failDirect(e: Exception) = failDirect(e.errorCode(), e.message)

    companion object {
        const val WAIT_ROUNDS = 4
        private const val MICRO_PER_TOKEN = 1_000_000L

        /** The stake context as stored in a [PendingTransfer]: `registration`, `node` or `verification:<tier>`. */
        fun contextCode(context: StakeContext): String = when (context) {
            StakeContext.Registration -> "registration"
            StakeContext.Node -> "node"
            is StakeContext.Verification -> "verification:${context.tier.code}"
        }

        fun contextFrom(code: String): StakeContext? = when {
            code == "registration" -> StakeContext.Registration
            code == "node" -> StakeContext.Node
            code.startsWith("verification:") -> StakeTier.entries.firstOrNull { it.code == code.removePrefix("verification:") }?.let { StakeContext.Verification(it) }
            else -> null
        }

        /** Display tokens -> on-chain micro units (6 decimals), e.g. `2.5` -> `2500000`. */
        fun microFor(tokens: BigDecimal): Long =
            tokens.multiply(BigDecimal.valueOf(MICRO_PER_TOKEN)).setScale(0, RoundingMode.HALF_UP).longValueExact()

        /** Stake-note `action` per context (Stake.tsx:493-498). */
        fun actionFor(context: StakeContext): String = when (context) {
            StakeContext.Registration -> "Registration Staking"
            StakeContext.Node -> "Node Staking"
            is StakeContext.Verification -> "Verification Staking"
        }

        /** Stake-note `operation` per context (Stake.tsx:505-510). */
        fun operationFor(context: StakeContext): String = when (context) {
            StakeContext.Registration -> "registration_staking"
            StakeContext.Node -> "node_staking"
            is StakeContext.Verification -> "verification_staking"
        }

        private val ISO_MILLIS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** Same shape as JavaScript `Date.prototype.toISOString()` (always millisecond precision, `Z`). */
        fun isoTimestamp(epochMillis: Long): String = ISO_MILLIS.format(Instant.ofEpochMilli(epochMillis))

        /** The ASA the product expects for this context (`reward.tokens.*`), else the family's reward asset. */
        fun assetFor(product: Product, context: StakeContext, family: MinerFamily): FryAsset {
            val symbol = when (context) {
                StakeContext.Registration -> product.reward?.tokens?.register
                StakeContext.Node -> product.reward?.tokens?.node
                is StakeContext.Verification -> product.reward?.tokens?.stake
            }
            return symbol?.let { s -> FryAsset.entries.firstOrNull { it.symbol.equals(s, ignoreCase = true) } } ?: family.rewardAsset
        }
    }
}
