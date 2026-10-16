package com.frynetworks.fryapp.ui.miners.claim

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.data.dashboard.model.ClaimEnvelopeResponse
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.data.dashboard.repo.FailClosedTxResolver
import com.frynetworks.fryapp.data.dashboard.repo.StakeRepository
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
import com.frynetworks.fryapp.domain.ClaimEvent
import com.frynetworks.fryapp.domain.ClaimState
import com.frynetworks.fryapp.domain.ClaimTotal
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.domain.reduce
import com.frynetworks.fryapp.network.dashboard.ServerClock
import com.frynetworks.fryapp.ui.common.ErrorCopy
import com.frynetworks.fryapp.ui.common.TimeFormat
import com.frynetworks.fryapp.ui.common.errorCode
import com.frynetworks.fryapp.update.InstallInhibitor
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import javax.inject.Inject

/** Rows of the sheet's stage list (`claim_stage_<n>`, 1-based). */
enum class ClaimStage(val label: String) {
    WALLET("Wallet connected"),
    OPT_IN("Reward asset opt-in"),
    PREVIEW("Preview rewards"),
    BALANCE("ALGO balance check"),
    FEE("Network fee (0.001 ALGO)"),
    ENVELOPE("Claim prepared"),
    SIGN("Sign the claim"),
    CONFIRM("Confirm with the dashboard"),
    DONE("Rewards claimed"),
}

enum class StageStatus { DONE, CURRENT, PENDING, FAILED }

data class ClaimUiState(
    val minerKey: String? = null,
    val no: Int? = null,
    val asset: FryAsset? = null,
    val state: ClaimState = ClaimState.Idle,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    /** The bridge has no wallet session for the signed-in address: show `claim_reconnect`. */
    val needsReconnect: Boolean = false,
    /** Native "You are about to sign" summary, set before the wallet app opens. */
    val pendingSummary: TxnSummary? = null,
    val lastWalletUri: String? = null,
    /** Stage index (into [ClaimStage]) the flow was on when it failed. */
    val failedStage: Int? = null,
) {
    val canConfirm: Boolean get() = state is ClaimState.Preview
    val canCancel: Boolean
        get() = state !is ClaimState.PayingFee && state !is ClaimState.AwaitingSignature && state !is ClaimState.Confirming
    val isBusy: Boolean
        get() = connecting || state is ClaimState.CheckingOptIn || state is ClaimState.OptingIn || state is ClaimState.CheckingBalance ||
            state is ClaimState.PayingFee || state is ClaimState.RequestingEnvelope || state is ClaimState.AwaitingSignature ||
            state is ClaimState.Confirming
    val awaitingWallet: Boolean
        get() = state is ClaimState.OptingIn || state is ClaimState.PayingFee || state is ClaimState.AwaitingSignature
    val errorCopy: String?
        get() = (state as? ClaimState.Failed)?.let { ErrorCopy.forCode(it.code, it.message) }
    val currentStage: Int get() = failedStage ?: stageIndexOf(state, needsReconnect || connecting || !connected)

    fun stages(): List<Pair<ClaimStage, StageStatus>> {
        val current = currentStage
        val failed = state is ClaimState.Failed
        val done = state is ClaimState.Done
        return ClaimStage.entries.mapIndexed { index, stage ->
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
        fun stageIndexOf(state: ClaimState, walletPending: Boolean): Int = when (state) {
            ClaimState.Idle -> if (walletPending) ClaimStage.WALLET.ordinal else ClaimStage.OPT_IN.ordinal
            is ClaimState.CheckingOptIn, is ClaimState.OptInRequired, is ClaimState.OptingIn -> ClaimStage.OPT_IN.ordinal
            is ClaimState.Preview -> ClaimStage.PREVIEW.ordinal
            ClaimState.CheckingBalance -> ClaimStage.BALANCE.ordinal
            ClaimState.PayingFee -> ClaimStage.FEE.ordinal
            ClaimState.RequestingEnvelope -> ClaimStage.ENVELOPE.ordinal
            is ClaimState.AwaitingSignature -> ClaimStage.SIGN.ordinal
            ClaimState.Confirming -> ClaimStage.CONFIRM.ordinal
            is ClaimState.Done -> ClaimStage.DONE.ordinal
            is ClaimState.Failed -> ClaimStage.WALLET.ordinal
        }
    }
}

/**
 * Drives [com.frynetworks.fryapp.domain.ClaimFlow]'s pure reducer with the real side effects
 * (blueprint 5.7): opt-in check -> optional opt-in -> preview -> balance -> fee payment ->
 * envelope -> custodial poll or group signature -> confirm. Every wallet signature first
 * surfaces the decoded transaction as [ClaimUiState.pendingSummary].
 */
@HiltViewModel
class ClaimViewModel(
    private val rewards: RewardsRepository,
    private val algod: AlgodRepository,
    private val miners: MinerRepository,
    private val bridge: WalletBridge,
    private val session: SessionRepository,
    private val clock: ServerClock,
    private val openUri: (String) -> Unit,
    private val pollDelayMillis: Long = CUSTODIAL_POLL_DELAY_MILLIS,
    private val optInRetryDelayMillis: Long = OPT_IN_RETRY_DELAY_MILLIS,
    /** Held from the first signature to the dashboard's confirm: an app update then would orphan an on-chain payment. */
    private val inhibitor: InstallInhibitor? = null,
    /** Durable record of the fee payment that may be on the network; survives process death, logout and account switches. */
    private val store: PendingTransferStore = InMemoryPendingTransferStore(),
    /** What became of a recorded fee payment; the default can never say it is gone, so the fee is never paid twice. */
    private val resolver: TxResolver = FailClosedTxResolver,
    /** Where the store (disk) is touched; production passes IO, the default runs inline. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) : ViewModel() {

    @Inject
    constructor(
        rewards: RewardsRepository,
        algod: AlgodRepository,
        miners: MinerRepository,
        bridge: WalletBridge,
        session: SessionRepository,
        clock: ServerClock,
        launcher: ExternalUriLauncher,
        inhibitor: InstallInhibitor,
        store: PendingTransferStore,
        resolver: TxResolver,
    ) : this(
        rewards, algod, miners, bridge, session, clock,
        openUri = { uri -> launcher.open(uri, null) }, inhibitor = inhibitor, store = store, resolver = resolver, ioDispatcher = Dispatchers.IO,
    )

    private val ui = MutableStateFlow(ClaimUiState())
    val uiState: StateFlow<ClaimUiState> = ui.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            bridge.events.collect { event ->
                if (event is BridgeEvent.OpenUri) ui.update { it.copy(lastWalletUri = event.uri) }
            }
        }
    }

    fun now(): Long = clock.nowMillis()

    /** Opens the flow for [minerKey] (optionally a single reward row [no]); restarts if already open. */
    fun start(minerKey: String, no: Int? = null) {
        job?.cancel()
        val asset = MinerFamily.fromMinerKey(minerKey).rewardAsset
        ui.value = ClaimUiState(minerKey = minerKey, no = no, asset = asset, lastWalletUri = ui.value.lastWalletUri)
        job = viewModelScope.launch { if (ensureConnected(reconnectOnly = true)) preflight() }
    }

    /**
     * Re-opens the flow for a recreated sheet: a claim already paying its fee or preparing its envelope for the
     * same miner and row keeps running, so the fee is not paid twice; anything else starts over.
     */
    fun resume(minerKey: String, no: Int?) {
        val current = ui.value
        val running = job?.isActive == true && current.minerKey == minerKey && current.no == no
        if (running && (!current.canCancel || current.state is ClaimState.RequestingEnvelope)) return
        start(minerKey, no)
    }

    /** `claim_reconnect`: runs a full wallet connect; a different address than the session is WALLET_MISMATCH. */
    fun reconnectWallet() {
        if (ui.value.connecting) return
        job?.cancel()
        job = viewModelScope.launch { if (ensureConnected(reconnectOnly = false)) preflight() }
    }

    /** `claim_optin`. */
    fun optIn() {
        if (ui.value.state !is ClaimState.OptInRequired) return
        job?.cancel()
        job = viewModelScope.launch { holdingInstalls { runOptIn() } }
    }

    /** `claim_confirm` on the Preview step. */
    fun confirm() {
        if (!ui.value.canConfirm) return
        job?.cancel()
        job = viewModelScope.launch { holdingInstalls { runClaim() } }
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

    /** `claim_cancel`: disabled during PayingFee/AwaitingSignature/Confirming (Claim.tsx:631 parity). */
    fun cancel() {
        if (!ui.value.canCancel) return
        job?.cancel()
        dispatch(ClaimEvent.Cancel)
        ui.update { it.copy(pendingSummary = null, needsReconnect = false, connecting = false, failedStage = null) }
    }

    /** `claim_retry` from a recoverable failure. */
    fun retry() {
        val key = ui.value.minerKey ?: return
        start(key, ui.value.no)
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
            ui.update { it.copy(connecting = false, needsReconnect = true, connected = false) }
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

    private suspend fun preflight() {
        val asset = ui.value.asset ?: return
        val address = address() ?: return
        dispatch(ClaimEvent.Start(asset.id))
        val opted = try {
            algod.isOptedIn(address, asset.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failDirect(e)
            return
        }
        dispatch(ClaimEvent.OptInChecked(opted, asset.id))
        if (opted) loadPreview()
    }

    private suspend fun loadPreview() {
        val key = ui.value.minerKey ?: return
        try {
            val preview = rewards.claimPreview(key, ui.value.no)
            val totals = preview.totals.mapNotNull { total ->
                total.assetId?.toLongOrNull()?.let { ClaimTotal(it, total.amount ?: BigDecimal.ZERO) }
            }
            dispatch(ClaimEvent.PreviewLoaded(totals))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dispatch(ClaimEvent.PreviewFailed(e.errorCode(), e.message ?: ""))
        }
    }

    private suspend fun runOptIn() {
        val asset = ui.value.asset ?: return
        val address = address() ?: return
        dispatch(ClaimEvent.OptInSubmitted)
        try {
            val txn = bridge.buildOptIn(address, asset.id)
            val signed = signSingle(txn)
            bridge.submit(listOf(signed), WAIT_ROUNDS)
            var opted = algod.isOptedIn(address, asset.id)
            if (!opted) {
                delay(optInRetryDelayMillis)
                opted = algod.isOptedIn(address, asset.id)
            }
            dispatch(ClaimEvent.OptInChecked(opted, asset.id))
            if (opted) loadPreview()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BridgeException) {
            clearSummary()
            if (e.code == BridgeErrorCode.USER_REJECTED) dispatch(ClaimEvent.SignRejected) else failDirect(e)
        } catch (e: Exception) {
            clearSummary()
            failDirect(e)
        }
    }

    private suspend fun runClaim() {
        val key = ui.value.minerKey ?: return
        val address = address() ?: return
        dispatch(ClaimEvent.PreviewConfirmed)
        val balance = try {
            algod.algoBalanceMicro(address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failDirect(e)
            return
        }
        dispatch(ClaimEvent.BalanceChecked(balance))
        if (ui.value.state !is ClaimState.PayingFee) return

        // A fee payment recorded for this claim (before a crash, a retry, a logout, an update) is continued, never paid again.
        val row = rowCode()
        val target = FeeTarget(address, key, row)
        var feeTxId: String? = null
        when (val prior = priorFee(address, key, row)) {
            PriorFee.None -> Unit
            is PriorFee.Paid -> feeTxId = prior.txId
            is PriorFee.Blocked -> {
                failDirect(CODE_TXN_PENDING, prior.message)
                return
            }
        }
        if (feeTxId == null) {
            var recorded: PendingTransfer? = null
            try {
                val txn = bridge.buildPayment(address, StakeRepository.CLAIM_FEE_WALLET, StakeRepository.CLAIM_FEE_MICROALGO, null)
                val builtRound = safely { algod.currentRound() }
                val summary = runCatching { bridge.decodeTxn(txn) }.getOrNull()
                val signed = signSingle(txn)
                // The id and rounds come from the signed bytes; only when they cannot be read is the bridge's decode of the unsigned
                // transaction used for the id (with no last valid round, so it never allows a second payment); with neither the
                // record is POSSIBLY_BROADCAST and always blocks.
                val parsed = SignedTxnReader.parse(signed)
                val knownId = parsed?.txId ?: summary?.txId?.takeIf { it.isNotBlank() }
                val toSend = PendingTransfer(
                    kind = TransferKind.CLAIM_FEE,
                    sender = address,
                    minerKey = key,
                    row = row,
                    assetId = 0L,
                    amount = StakeRepository.CLAIM_FEE_MICROALGO.toString(),
                    amountMicro = StakeRepository.CLAIM_FEE_MICROALGO,
                    receiver = StakeRepository.CLAIM_FEE_WALLET,
                    txId = knownId,
                    firstValid = parsed?.firstValid,
                    lastValid = parsed?.lastValid,
                    builtRound = builtRound,
                    state = if (knownId == null) TransferState.POSSIBLY_BROADCAST else TransferState.PRE_SUBMIT,
                    createdAt = clock.nowMillis(),
                )
                // On disk before the network can see it: a record that cannot be written means nothing is sent.
                if (!ioWrite { store.putNew(toSend) }) throw RecordNotSaved()
                recorded = toSend
                val returned = bridge.submit(listOf(signed), WAIT_ROUNDS).firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: throw BridgeException(BridgeErrorCode.UNKNOWN, "The bridge returned no transaction id")
                if (knownId != null && returned != knownId) throw TxIdMismatch()
                if (knownId == null && !ioWrite { store.put(toSend.copy(txId = returned, state = TransferState.PRE_SUBMIT)) }) {
                    Log.w(LOG_TAG, "could not record the id of a submitted claim fee payment") // the record stays unidentified and blocks
                }
                feeTxId = returned
            } catch (e: CancellationException) {
                throw e
            } catch (e: RecordNotSaved) {
                clearSummary()
                failDirect(CODE_RECORD_FAILED, RECORD_FAILED_MESSAGE)
                return
            } catch (e: TxIdMismatch) {
                // Neither id can be trusted: keep the record, without an id, so only the account search can resolve it.
                recorded?.let { downgradeToUnidentified(it) }
                failDirect(CODE_TXN_PENDING, UNKNOWN_FEE_MESSAGE)
                return
            } catch (e: BridgeException) {
                clearSummary()
                val r = recorded
                if (e.code == BridgeErrorCode.USER_REJECTED && r == null) {
                    dispatch(ClaimEvent.FeeRejected)
                } else if (r != null && r.txId == null) {
                    failDirect(CODE_TXN_PENDING, UNKNOWN_FEE_MESSAGE)
                } else {
                    failDirect(e)
                }
                return
            } catch (e: Exception) {
                clearSummary()
                val r = recorded
                if (r != null && r.txId == null) failDirect(CODE_TXN_PENDING, UNKNOWN_FEE_MESSAGE) else failDirect(e)
                return
            }
        }
        dispatch(ClaimEvent.FeePaid(feeTxId))

        val envelope = try {
            rewards.claimEnvelope(key, ui.value.no)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dispatch(ClaimEvent.EnvelopeFailed(e.errorCode(), e.message ?: ""))
            return
        }
        dispatch(
            ClaimEvent.EnvelopeReceived(
                mode = envelope.mode ?: if (envelope.txId != null) "custodial" else "",
                groupId = envelope.groupId,
                unsignedUserLeg = envelope.unsignedUserLeg,
                unsignedServerLegs = envelope.unsignedServerLegs,
                txIdIfCustodial = envelope.txId,
                expiresAtMillis = clock.nowMillis() + CLAIM_GROUP_TTL_MILLIS,
            ),
        )
        when (val state = ui.value.state) {
            is ClaimState.AwaitingSignature -> signGroup(state.groupId, envelope, target)
            ClaimState.Confirming -> pollCustodial(envelope.txId ?: return, target)
            else -> return
        }
    }

    private suspend fun signGroup(groupId: String, envelope: ClaimEnvelopeResponse, target: FeeTarget) {
        val userLeg = envelope.unsignedUserLeg
        if (userLeg.isNullOrBlank()) {
            failDirect("INVALID_ENVELOPE", ErrorCopy.forCode("INVALID_ENVELOPE"))
            return
        }
        val group = listOf(TxnToSign(userLeg, sign = true)) + envelope.unsignedServerLegs.map { TxnToSign(it, sign = false) }
        val signed = try {
            showSummary(userLeg)
            val result = bridge.signTxns(listOf(group))
            clearSummary()
            result.firstOrNull()?.firstOrNull()
                ?: throw BridgeException(BridgeErrorCode.USER_REJECTED, "Wallet returned no signature")
        } catch (e: CancellationException) {
            throw e
        } catch (e: BridgeException) {
            clearSummary()
            if (e.code == BridgeErrorCode.USER_REJECTED) dispatch(ClaimEvent.SignRejected) else failDirect(e)
            return
        } catch (e: IllegalStateException) {
            // A reply of the wrong shape (cancellation is rethrown above).
            clearSummary()
            failDirect(e)
            return
        }
        dispatch(ClaimEvent.Signed(signed))
        try {
            val result = rewards.claimConfirm(groupId, signed)
            dispatch(ClaimEvent.Confirmed(result.txId ?: "", TimeFormat.parseMillis(result.claimedAt)))
            onDone(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dispatch(ClaimEvent.ConfirmFailed(e.errorCode(), e.message ?: ""))
        }
    }

    /** Custodial path: poll `confirm {txId}` up to six times (Claim.tsx:539-598 parity). */
    private suspend fun pollCustodial(txId: String, target: FeeTarget) {
        repeat(CUSTODIAL_POLL_ATTEMPTS) { attempt ->
            val result = try {
                rewards.claimConfirmCustodial(txId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dispatch(ClaimEvent.ConfirmFailed(e.errorCode(), e.message ?: ""))
                    return
            }
            if (result.ok == true || result.success == true) {
                dispatch(ClaimEvent.Confirmed(result.txId ?: txId, TimeFormat.parseMillis(result.claimedAt)))
                onDone(target)
                return
            }
            if (attempt < CUSTODIAL_POLL_ATTEMPTS - 1) delay(pollDelayMillis)
        }
        dispatch(ClaimEvent.ConfirmFailed("CONFIRM_PENDING", "${ErrorCopy.forCode("CONFIRM_PENDING")} Transaction: $txId"))
    }

    /** The claim is complete: this fee payment is spent. The sender, miner and row are those captured when the claim started. */
    private suspend fun onDone(target: FeeTarget) {
        clearFee(target)
        runCatching { rewards.refreshSummary(target.key) }
        runCatching { miners.refreshDetail(target.key) }
    }

    /**
     * A confirmed fee record is cleared only when the claim succeeded ([onDone]) or the fee transaction is proven dead. The server never
     * sees the fee's txid, so reusing a paid fee gains nothing, while clearing it early could burn fees without limit.
     * Up to three tries; a failure is logged and the record stays (it can only make a later claim reuse a paid fee).
     */
    private suspend fun clearFee(target: FeeTarget): Boolean {
        val cleared = ioWrite { (1..CLEAR_ATTEMPTS).any { store.remove(target.sender, TransferKind.CLAIM_FEE, target.key, target.row) } }
        if (cleared) return true
        Log.w(LOG_TAG, "could not clear the claim fee record after $CLEAR_ATTEMPTS tries")
        return false
    }

    /** The fee payment's rewrite after a txid mismatch (retried once) or, failing that, a block for this process. */
    private suspend fun downgradeToUnidentified(record: PendingTransfer) {
        // One uncancellable block (withContext throws on exit once its job is cancelled, so separate blocks would cut the sequence short).
        // Blocked in memory first, so a cancellation or a failed write can never leave this key open to a rebuild.
        val rewritten = ioWrite {
            store.blockPermanently(record.sender, record.kind, record.minerKey, record.row)
            val unidentified = record.copy(txId = null, state = TransferState.POSSIBLY_BROADCAST)
            store.put(unidentified) || store.put(unidentified)
        }
        if (!rewritten) Log.e(LOG_TAG, "could not rewrite a pending claim fee record after a txid mismatch: blocked for this process")
    }

    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { block() }

    /** A store write once the payment may be on the network: a cancelled job still completes it. */
    private suspend fun <T> ioWrite(block: () -> T): T = withContext(NonCancellable + ioDispatcher) { block() }

    /** Who a claim attempt pays for, captured when it starts so a logout or account switch cannot redirect its clean-up. */
    private data class FeeTarget(val sender: String, val key: String, val row: String)

    private suspend fun signSingle(txnB64: String): String {
        showSummary(txnB64)
        val signed = bridge.signTxns(listOf(listOf(TxnToSign(txnB64, sign = true))))
        clearSummary()
        return signed.firstOrNull()?.firstOrNull()
            ?: throw BridgeException(BridgeErrorCode.USER_REJECTED, "Wallet returned no signature")
    }

    private suspend fun showSummary(txnB64: String) {
        val summary = runCatching { bridge.decodeTxn(txnB64) }.getOrNull()
        ui.update { it.copy(pendingSummary = summary) }
    }

    private fun clearSummary() = ui.update { it.copy(pendingSummary = null) }

    /** The reward row a fee record belongs to: its number, or `all` for the whole miner. */
    private fun rowCode(): String = ui.value.no?.toString() ?: "all"

    private sealed interface PriorFee {
        /** Nothing recorded, or the recorded payment was proven dead and cleared: the fee is to be paid. */
        data object None : PriorFee

        data class Paid(val txId: String) : PriorFee

        data class Blocked(val message: String) : PriorFee
    }

    private suspend fun priorFee(address: String, key: String, row: String): PriorFee {
        val record = when (val found = io { store.get(address, TransferKind.CLAIM_FEE, key, row) }) {
            PendingLookup.None -> return PriorFee.None
            PendingLookup.Unreadable -> return PriorFee.Blocked(UNKNOWN_FEE_MESSAGE)
            is PendingLookup.Found -> found.record
        }
        val txId = record.txId ?: return unidentifiedFee(record)
        val pool = safely { algod.txStatus(txId) } ?: TxStatus.Unknown
        return when (pool) {
            is TxStatus.Confirmed -> PriorFee.Paid(txId)
            TxStatus.InPool -> try {
                algod.waitConfirmed(txId, WAIT_ROUNDS)
                PriorFee.Paid(txId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PriorFee.Blocked(PENDING_FEE_MESSAGE)
            }
            is TxStatus.Rejected -> PriorFee.Blocked(PENDING_FEE_MESSAGE)
            TxStatus.Absent, TxStatus.Unknown -> when (verdict(record, txId)) {
                is TxResolution.Confirmed -> PriorFee.Paid(txId)
                // Proven dead: the record goes, and only if it really went may the fee be paid again.
                TxResolution.Absent -> if (clearFee(FeeTarget(address, key, row))) PriorFee.None else PriorFee.Blocked(PENDING_FEE_MESSAGE)
                TxResolution.Unknown -> PriorFee.Blocked(PENDING_FEE_MESSAGE)
            }
        }
    }

    /** The resolver's answer, with Absent refused when the last valid round is unknown and anything about another txid ignored. */
    private suspend fun verdict(record: PendingTransfer, txId: String): TxResolution {
        val answer = safely { resolver.resolve(txId, record.lastValid) } ?: TxResolution.Unknown
        return when {
            answer == TxResolution.Absent && record.lastValid == null -> TxResolution.Unknown
            answer is TxResolution.Confirmed && answer.txId != txId -> TxResolution.Unknown
            else -> answer
        }
    }

    /** A payment handed to submit with no id to resume from: only the account search can identify it; never cleared automatically. */
    private suspend fun unidentifiedFee(record: PendingTransfer): PriorFee {
        val found = safely {
            resolver.findTransfer(record.sender, record.receiver, record.assetId, record.amountMicro, record.firstValid ?: record.builtRound)
        }
        if (found !is TxResolution.Confirmed) return PriorFee.Blocked(UNKNOWN_FEE_MESSAGE)
        val identified = record.copy(txId = found.txId, state = TransferState.PRE_SUBMIT)
        if (!ioWrite { store.put(identified) }) Log.w(LOG_TAG, "could not record the id found for a pending claim fee payment") // the unidentified record stays and blocks
        return PriorFee.Paid(found.txId)
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun dispatch(event: ClaimEvent) = ui.update { current ->
        val next = reduce(current.state, event)
        val failedStage = when {
            next is ClaimState.Failed && current.state !is ClaimState.Failed ->
                ClaimUiState.stageIndexOf(current.state, current.needsReconnect || current.connecting || !current.connected)
            next is ClaimState.Failed -> current.failedStage
            else -> null
        }
        current.copy(state = next, failedStage = failedStage)
    }

    /** Failures the reducer has no event for (pre-flow, bridge/network errors mid-step). */
    private fun failDirect(code: String, message: String?) = ui.update {
        it.copy(
            state = ClaimState.Failed(code, message ?: "", recoverable = true),
            failedStage = it.failedStage ?: ClaimUiState.stageIndexOf(it.state, it.needsReconnect || it.connecting || !it.connected),
            pendingSummary = null,
        )
    }

    private fun failDirect(e: Exception) = failDirect(e.errorCode(), e.message)

    /** The fee record could not be written, so the payment must not be submitted. */
    private class RecordNotSaved : RuntimeException()

    /** The bridge's submit answered with another transaction id than the one the signed bytes give. */
    private class TxIdMismatch : RuntimeException()

    companion object {
        private const val CODE_TXN_PENDING = "TXN_PENDING"
        private const val CODE_RECORD_FAILED = "TXN_RECORD_FAILED"
        private const val PENDING_FEE_MESSAGE = "The claim fee payment may still be processing. Try again shortly."
        private const val UNKNOWN_FEE_MESSAGE =
            "A previous claim fee payment may have been sent and is still pending. Do not try again: contact support so it can be checked."
        private const val LOG_TAG = "ClaimViewModel"
        private const val CLEAR_ATTEMPTS = 3

        private const val RECORD_FAILED_MESSAGE = "The claim could not be prepared safely on this phone, so nothing was paid. Try again."
        const val CLAIM_GROUP_TTL_MILLIS = 300_000L
        const val CUSTODIAL_POLL_ATTEMPTS = 6
        const val CUSTODIAL_POLL_DELAY_MILLIS = 5_000L
        const val OPT_IN_RETRY_DELAY_MILLIS = 3_000L
        const val WAIT_ROUNDS = 4
        const val EXPLORER_TX_URL = "https://explorer.perawallet.app/tx/"
    }
}
