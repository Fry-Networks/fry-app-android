package com.frynetworks.fryapp.ui.miners.claim

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.data.dashboard.model.ClaimEnvelopeResponse
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.data.dashboard.repo.StakeRepository
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    ) : this(rewards, algod, miners, bridge, session, clock, openUri = { uri -> launcher.open(uri, null) })

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
        job = viewModelScope.launch { runOptIn() }
    }

    /** `claim_confirm` on the Preview step. */
    fun confirm() {
        if (!ui.value.canConfirm) return
        job?.cancel()
        job = viewModelScope.launch { runClaim() }
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

        try {
            val txn = bridge.buildPayment(address, StakeRepository.CLAIM_FEE_WALLET, StakeRepository.CLAIM_FEE_MICROALGO, null)
            val signed = signSingle(txn)
            val txIds = bridge.submit(listOf(signed), WAIT_ROUNDS)
            dispatch(ClaimEvent.FeePaid(txIds.firstOrNull() ?: ""))
        } catch (e: CancellationException) {
            throw e
        } catch (e: BridgeException) {
            clearSummary()
            if (e.code == BridgeErrorCode.USER_REJECTED) dispatch(ClaimEvent.FeeRejected) else failDirect(e)
            return
        } catch (e: Exception) {
            clearSummary()
            failDirect(e)
            return
        }

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
            is ClaimState.AwaitingSignature -> signGroup(state.groupId, envelope)
            ClaimState.Confirming -> pollCustodial(envelope.txId ?: return)
            else -> return
        }
    }

    private suspend fun signGroup(groupId: String, envelope: ClaimEnvelopeResponse) {
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
        }
        dispatch(ClaimEvent.Signed(signed))
        try {
            val result = rewards.claimConfirm(groupId, signed)
            dispatch(ClaimEvent.Confirmed(result.txId ?: "", TimeFormat.parseMillis(result.claimedAt)))
            onDone()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dispatch(ClaimEvent.ConfirmFailed(e.errorCode(), e.message ?: ""))
        }
    }

    /** Custodial path: poll `confirm {txId}` up to six times (Claim.tsx:539-598 parity). */
    private suspend fun pollCustodial(txId: String) {
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
                onDone()
                return
            }
            if (attempt < CUSTODIAL_POLL_ATTEMPTS - 1) delay(pollDelayMillis)
        }
        dispatch(ClaimEvent.ConfirmFailed("CONFIRM_PENDING", "${ErrorCopy.forCode("CONFIRM_PENDING")} Transaction: $txId"))
    }

    private suspend fun onDone() {
        val key = ui.value.minerKey ?: return
        runCatching { rewards.refreshSummary(key) }
        runCatching { miners.refreshDetail(key) }
    }

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

    companion object {
        const val CLAIM_GROUP_TTL_MILLIS = 300_000L
        const val CUSTODIAL_POLL_ATTEMPTS = 6
        const val CUSTODIAL_POLL_DELAY_MILLIS = 5_000L
        const val OPT_IN_RETRY_DELAY_MILLIS = 3_000L
        const val WAIT_ROUNDS = 4
        const val EXPLORER_TX_URL = "https://explorer.perawallet.app/tx/"
    }
}
