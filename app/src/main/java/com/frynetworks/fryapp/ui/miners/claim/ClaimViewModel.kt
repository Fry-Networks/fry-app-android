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
import com.frynetworks.fryapp.data.pending.BindingLookup
import com.frynetworks.fryapp.data.pending.ClaimFeeBinding
import com.frynetworks.fryapp.data.pending.ClaimFeeBindingStore
import com.frynetworks.fryapp.data.pending.InMemoryClaimFeeBindingStore
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
    /** Which claim each paid fee belongs to (kept apart from [store] so the fee record's own writes are unchanged). */
    private val bindings: ClaimFeeBindingStore = InMemoryClaimFeeBindingStore(),
    /** Where [bindings] (disk) is touched; production passes IO, the default runs inline. */
    private val bindingDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
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
        bindings: ClaimFeeBindingStore,
    ) : this(
        rewards, algod, miners, bridge, session, clock,
        openUri = { uri -> launcher.open(uri, null) }, inhibitor = inhibitor, store = store, resolver = resolver, ioDispatcher = Dispatchers.IO,
        bindings = bindings, bindingDispatcher = Dispatchers.IO,
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
        var recorded: PendingTransfer? = null
        try {
            // An opt-in that may already be on the network is settled first, never sent a second time.
            when (val prior = priorOptIn(address, asset.id)) {
                is OptInPrior.Blocked -> {
                    failDirect(CODE_TXN_PENDING, prior.message)
                    return
                }
                OptInPrior.Opted -> {
                    dispatch(ClaimEvent.OptInChecked(true, asset.id))
                    loadPreview()
                    return
                }
                OptInPrior.Build -> Unit
            }
            val txn = bridge.buildOptIn(address, asset.id)
            val builtRound = safely { algod.currentRound() }
            val signed = signSingle(txn)
            // Same record as the claim fee and the stake: the id and rounds come from the signed bytes; only when they cannot be read is the
            // bridge's decode used (with no last valid round); with neither the record is POSSIBLY_BROADCAST and always blocks.
            val parsed = SignedTxnReader.parse(signed)
            val knownId = parsed?.txId ?: safely { bridge.decodeTxn(txn) }?.txId?.takeIf { it.isNotBlank() }
            val toSend = PendingTransfer(
                kind = TransferKind.ASA_OPTIN, sender = address, minerKey = OPTIN_MINER, row = asset.id.toString(), assetId = asset.id,
                amount = "0", amountMicro = 0L, receiver = address, txId = knownId, firstValid = parsed?.firstValid, lastValid = parsed?.lastValid,
                builtRound = builtRound, state = if (knownId == null) TransferState.POSSIBLY_BROADCAST else TransferState.PRE_SUBMIT,
                createdAt = clock.nowMillis(),
            )
            if (!ioWrite { store.putNew(toSend) }) throw RecordNotSaved()
            recorded = toSend
            val returned = bridge.submit(listOf(signed), WAIT_ROUNDS).firstOrNull()?.takeIf { it.isNotBlank() }
            if (knownId != null && returned != null && returned != knownId) throw TxIdMismatch()
            if (knownId == null && returned != null && !ioWrite { store.put(toSend.copy(txId = returned, state = TransferState.PRE_SUBMIT)) }) {
                Log.w(LOG_TAG, "could not record the id of a submitted opt-in") // the record stays unidentified and blocks
            }
            var opted = algod.isOptedIn(address, asset.id)
            if (!opted) {
                delay(optInRetryDelayMillis)
                opted = algod.isOptedIn(address, asset.id)
            }
            if (opted) clearOptIn(toSend)
            dispatch(ClaimEvent.OptInChecked(opted, asset.id))
            if (opted) loadPreview()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RecordNotSaved) {
            clearSummary()
            failDirect(CODE_RECORD_FAILED, OPTIN_RECORD_FAILED_MESSAGE)
        } catch (e: TxIdMismatch) {
            // Neither id can be trusted: keep the record, without an id, so only the account search can resolve it.
            recorded?.let { downgradeToUnidentified(it) }
            failDirect(CODE_TXN_PENDING, UNKNOWN_OPTIN_MESSAGE)
        } catch (e: BridgeException) {
            clearSummary()
            val r = recorded
            if (e.code == BridgeErrorCode.USER_REJECTED && r == null) {
                dispatch(ClaimEvent.SignRejected)
            } else if (r != null && r.txId == null) {
                failDirect(CODE_TXN_PENDING, UNKNOWN_OPTIN_MESSAGE)
            } else {
                failDirect(e)
            }
        } catch (e: Exception) {
            clearSummary()
            val r = recorded
            if (r != null && r.txId == null) failDirect(CODE_TXN_PENDING, UNKNOWN_OPTIN_MESSAGE) else failDirect(e)
        }
    }

    private sealed interface OptInPrior {
        /** Nothing recorded, or the recorded opt-in was proven dead and cleared: one is to be built. */
        data object Build : OptInPrior

        /** The wallet is opted in (the recorded opt-in landed, or something else did it): nothing to send. */
        data object Opted : OptInPrior

        data class Blocked(val message: String) : OptInPrior
    }

    /** Settles a recorded opt-in: opted in (or confirmed) is cleared and continued, proven dead is cleared and rebuilt, anything else blocks. */
    private suspend fun priorOptIn(address: String, assetId: Long): OptInPrior {
        val record = when (val found = io { store.get(address, TransferKind.ASA_OPTIN, OPTIN_MINER, assetId.toString()) }) {
            PendingLookup.None -> return OptInPrior.Build
            PendingLookup.Unreadable -> return OptInPrior.Blocked(UNKNOWN_OPTIN_MESSAGE)
            is PendingLookup.Found -> found.record
        }
        // Opted in is the one thing an opt-in is for, whatever became of the recorded transaction.
        if (safely { algod.isOptedIn(address, assetId) } == true) return settledOptIn(record, true)
        val txId = record.txId ?: return unidentifiedOptIn(record)
        val pool = safely { algod.txStatus(txId) } ?: TxStatus.Unknown
        return when (pool) {
            is TxStatus.Confirmed -> settledOptIn(record, false)
            TxStatus.InPool -> try {
                algod.waitConfirmed(txId, WAIT_ROUNDS)
                settledOptIn(record, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OptInPrior.Blocked(PENDING_OPTIN_MESSAGE)
            }
            is TxStatus.Rejected -> OptInPrior.Blocked(PENDING_OPTIN_MESSAGE)
            TxStatus.Absent, TxStatus.Unknown -> when (verdict(record, txId)) {
                is TxResolution.Confirmed -> settledOptIn(record, false)
                TxResolution.Absent -> if (clearOptIn(record)) OptInPrior.Build else OptInPrior.Blocked(PENDING_OPTIN_MESSAGE)
                TxResolution.Unknown -> OptInPrior.Blocked(PENDING_OPTIN_MESSAGE)
            }
        }
    }

    /** An opt-in handed to submit with no id to resume from: only the account search can identify it; never cleared automatically. */
    private suspend fun unidentifiedOptIn(record: PendingTransfer): OptInPrior {
        val found = safely { resolver.findTransfer(record.sender, record.receiver, record.assetId, record.amountMicro, record.firstValid ?: record.builtRound) }
        if (found !is TxResolution.Confirmed) return OptInPrior.Blocked(UNKNOWN_OPTIN_MESSAGE)
        val identified = record.copy(txId = found.txId, state = TransferState.PRE_SUBMIT)
        if (!ioWrite { store.put(identified) }) Log.w(LOG_TAG, "could not record the id found for a pending opt-in") // the unidentified record stays and blocks
        return settledOptIn(identified, false)
    }

    /** The recorded opt-in is on the chain: its record goes, and only if it really went does the flow carry on (opted in, else a new one). */
    private suspend fun settledOptIn(record: PendingTransfer, optedIn: Boolean): OptInPrior {
        if (!clearOptIn(record)) return OptInPrior.Blocked(PENDING_OPTIN_MESSAGE)
        val opted = optedIn || safely { algod.isOptedIn(record.sender, record.assetId) } == true
        return if (opted) OptInPrior.Opted else OptInPrior.Build
    }

    private suspend fun clearOptIn(record: PendingTransfer): Boolean {
        val cleared = ioWrite { (1..CLEAR_ATTEMPTS).any { store.remove(record.sender, TransferKind.ASA_OPTIN, record.minerKey, record.row) } }
        if (!cleared) Log.w(LOG_TAG, "could not clear the opt-in record after $CLEAR_ATTEMPTS tries")
        return cleared
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
        var priorBinding: ClaimFeeBinding? = null
        var rebind = false
        when (val prior = priorFee(address, key, row)) {
            PriorFee.None -> Unit
            is PriorFee.Paid -> {
                feeTxId = prior.txId
                priorBinding = prior.binding
                rebind = prior.rebind
            }
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
            is ClaimState.AwaitingSignature -> {
                // The fee is tied to this claim on disk before anything is signed.
                if (!bindClaim(target, userPaysBinding(envelope, priorBinding, rebind))) return
                signGroup(state.groupId, envelope, target)
            }
            ClaimState.Confirming -> {
                val txId = envelope.txId ?: return
                val rebound = priorBinding?.rebound == true || rebind
                if (!bindClaim(target, ClaimFeeBinding(ClaimFeeBinding.CUSTODIAL, txId, envelope.groupId, null, rebound = rebound))) return
                pollCustodial(txId, target)
            }
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
            unbind(target)
            if (e.code == BridgeErrorCode.USER_REJECTED) dispatch(ClaimEvent.SignRejected) else failDirect(e)
            return
        } catch (e: IllegalStateException) {
            // A reply of the wrong shape (cancellation is rethrown above).
            clearSummary()
            unbind(target)
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
        // Spent first, so that even a fee record that cannot be removed is never reused by a later claim.
        if (!bindWrite { markConsumed(target) }) Log.w(LOG_TAG, "could not mark the claim fee as consumed")
        if (clearFee(target)) {
            if (!bindWrite { bindings.remove(target.sender, target.key, target.row) }) Log.w(LOG_TAG, "could not remove the claim fee binding")
        }
        runCatching { rewards.refreshSummary(target.key) }
        runCatching { miners.refreshDetail(target.key) }
    }

    private fun markConsumed(target: FeeTarget): Boolean {
        val found = bindings.get(target.sender, target.key, target.row) as? BindingLookup.Found ?: return true
        return bindings.put(target.sender, target.key, target.row, found.binding.copy(consumed = true))
    }

    /** Ties the paid fee to the claim whose envelope was just received; false (and the claim failed) when that cannot be written. */
    private suspend fun bindClaim(target: FeeTarget, binding: ClaimFeeBinding): Boolean {
        if (bindWrite { bindings.put(target.sender, target.key, target.row, binding) }) return true
        failDirect(CODE_RECORD_FAILED, RECORD_FAILED_MESSAGE)
        return false
    }

    /** The user leg's id and last valid round come from its own bytes; only when they cannot be read is the bridge's decode used (without a round). */
    private suspend fun userPaysBinding(envelope: ClaimEnvelopeResponse, prior: ClaimFeeBinding?, rebind: Boolean): ClaimFeeBinding {
        val leg = envelope.unsignedUserLeg.orEmpty()
        val parsed = SignedTxnReader.parse(leg)
        val id = parsed?.txId ?: safely { bridge.decodeTxn(leg) }?.txId?.takeIf { it.isNotBlank() }
        return ClaimFeeBinding(ClaimFeeBinding.USER_PAYS, id, envelope.groupId, parsed?.lastValid, rebound = prior?.rebound == true || rebind)
    }

    /**
     * The group was never signed and the dashboard's confirm was never called, so the leg can never land: the fee is the same claim's again.
     * The rebound flag is kept. A failed write leaves the binding, which then only resolves by the claim's own outcome.
     */
    private suspend fun unbind(target: FeeTarget) {
        val written = bindWrite {
            val found = bindings.get(target.sender, target.key, target.row) as? BindingLookup.Found
            when {
                found == null -> true
                found.binding.rebound -> bindings.put(target.sender, target.key, target.row, ClaimFeeBinding(ClaimFeeBinding.UNBOUND, null, null, null, rebound = true))
                else -> bindings.remove(target.sender, target.key, target.row)
            }
        }
        if (!written) Log.w(LOG_TAG, "could not unbind the claim fee after an unsigned envelope")
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

    private suspend fun <T> bindIo(block: () -> T): T = withContext(bindingDispatcher) { block() }

    private suspend fun <T> bindWrite(block: () -> T): T = withContext(NonCancellable + bindingDispatcher) { block() }

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

        /** [rebind]: the fee's earlier claim never landed, so the same payment is used for a new envelope (once per record). */
        data class Paid(val txId: String, val binding: ClaimFeeBinding? = null, val rebind: Boolean = false) : PriorFee

        data class Blocked(val message: String) : PriorFee
    }

    private suspend fun priorFee(address: String, key: String, row: String): PriorFee {
        val target = FeeTarget(address, key, row)
        val record = when (val found = io { store.get(address, TransferKind.CLAIM_FEE, key, row) }) {
            PendingLookup.None -> return orphanBinding(target)
            PendingLookup.Unreadable -> return PriorFee.Blocked(UNKNOWN_FEE_MESSAGE)
            is PendingLookup.Found -> found.record
        }
        if (record.feeVersion < PendingTransfer.FEE_VERSION) {
            // Written by 0.4.7: nothing says which claim it paid for, so a confirmed one is never reused (only a dead one is cleared).
            val fee = feeTransaction(record, address, key, row)
            return if (fee is PriorFee.Paid) PriorFee.Blocked(UNKNOWN_FEE_MESSAGE) else fee
        }
        return when (val bound = bindIo { bindings.get(address, key, row) }) {
            BindingLookup.Unreadable -> PriorFee.Blocked(PENDING_FEE_MESSAGE)
            BindingLookup.None -> feeTransaction(record, address, key, row)
            is BindingLookup.Found -> when {
                bound.binding.consumed -> removeSpent(target)
                bound.binding.mode == ClaimFeeBinding.UNBOUND -> feeTransaction(record, address, key, row).withBinding(bound.binding)
                else -> boundFee(record, bound.binding, target)
            }
        }
    }

    private fun PriorFee.withBinding(binding: ClaimFeeBinding): PriorFee = if (this is PriorFee.Paid) copy(binding = binding) else this

    /** A binding with no fee record is left over from a fee that was cleared: it is deleted, and if that fails nothing proceeds. */
    private suspend fun orphanBinding(target: FeeTarget): PriorFee {
        if (bindIo { bindings.get(target.sender, target.key, target.row) } == BindingLookup.None) return PriorFee.None
        val removed = bindWrite { bindings.remove(target.sender, target.key, target.row) }
        return if (removed) PriorFee.None else PriorFee.Blocked(PENDING_FEE_MESSAGE)
    }

    /** The fee record then its binding are removed; either failing blocks (a binding left alone is an orphan the next claim deletes). */
    private suspend fun removeSpent(target: FeeTarget): PriorFee {
        if (!clearFee(target)) return PriorFee.Blocked(PENDING_FEE_MESSAGE)
        return if (bindWrite { bindings.remove(target.sender, target.key, target.row) }) PriorFee.None else PriorFee.Blocked(PENDING_FEE_MESSAGE)
    }

    private enum class ClaimOutcome { CONFIRMED, ABSENT, UNKNOWN }

    /**
     * A fee bound to a claim: consumed when that claim completed; moved once to a new envelope only when the claim is proven never to have
     * landed; anything else blocks and leaves the record as it is.
     */
    private suspend fun boundFee(record: PendingTransfer, binding: ClaimFeeBinding, target: FeeTarget): PriorFee {
        val claimId = binding.claimTxId ?: return PriorFee.Blocked(PENDING_FEE_MESSAGE)
        if (binding.mode == ClaimFeeBinding.CUSTODIAL) {
            // The dashboard reports no terminal failure for a custodial claim, so it is never moved.
            return if (custodialConfirmed(claimId)) removeSpent(target) else PriorFee.Blocked(PENDING_FEE_MESSAGE)
        }
        val feeTxId = record.txId ?: return PriorFee.Blocked(PENDING_FEE_MESSAGE)
        return when (claimOutcome(claimId, binding.claimLastValid)) {
            ClaimOutcome.CONFIRMED -> removeSpent(target)
            ClaimOutcome.ABSENT -> if (binding.rebound) PriorFee.Blocked(PENDING_FEE_MESSAGE) else PriorFee.Paid(feeTxId, binding, rebind = true)
            ClaimOutcome.UNKNOWN -> PriorFee.Blocked(PENDING_FEE_MESSAGE)
        }
    }

    private suspend fun custodialConfirmed(claimId: String): Boolean {
        val polled = safely { rewards.claimConfirmCustodial(claimId) }
        if (polled != null && (polled.ok == true || polled.success == true)) return true
        val answer = safely { resolver.resolve(claimId, null) }
        return answer is TxResolution.Confirmed && answer.txId == claimId
    }

    /** What became of the claim's user leg; Absent only with a known last valid round and the pool silent about it. */
    private suspend fun claimOutcome(claimId: String, lastValid: Long?): ClaimOutcome {
        val pool = safely { algod.txStatus(claimId) } ?: TxStatus.Unknown
        if (pool is TxStatus.Confirmed) return ClaimOutcome.CONFIRMED
        if (pool == TxStatus.InPool || pool is TxStatus.Rejected) return ClaimOutcome.UNKNOWN
        val answer = safely { resolver.resolve(claimId, lastValid) } ?: TxResolution.Unknown
        return when {
            answer is TxResolution.Confirmed && answer.txId == claimId -> ClaimOutcome.CONFIRMED
            answer == TxResolution.Absent && lastValid != null -> ClaimOutcome.ABSENT
            else -> ClaimOutcome.UNKNOWN
        }
    }

    /** The fee payment itself: confirmed (or becoming so) is paid, proven dead is cleared, anything else blocks. */
    private suspend fun feeTransaction(record: PendingTransfer, address: String, key: String, row: String): PriorFee {
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
        private const val OPTIN_MINER = "-"
        private const val PENDING_OPTIN_MESSAGE = "The opt-in may still be processing. Try again shortly."
        private const val UNKNOWN_OPTIN_MESSAGE =
            "A previous opt-in may have been sent and is still pending. Do not try again: contact support so it can be checked."
        private const val OPTIN_RECORD_FAILED_MESSAGE = "The opt-in could not be prepared safely on this phone, so nothing was sent. Try again."

        private const val RECORD_FAILED_MESSAGE = "The claim could not be prepared safely on this phone, so nothing was paid. Try again."
        const val CLAIM_GROUP_TTL_MILLIS = 300_000L
        const val CUSTODIAL_POLL_ATTEMPTS = 6
        const val CUSTODIAL_POLL_DELAY_MILLIS = 5_000L
        const val OPT_IN_RETRY_DELAY_MILLIS = 3_000L
        const val WAIT_ROUNDS = 4
        const val EXPLORER_TX_URL = "https://explorer.perawallet.app/tx/"
    }
}
