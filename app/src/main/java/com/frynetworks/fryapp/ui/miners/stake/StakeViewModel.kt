package com.frynetworks.fryapp.ui.miners.stake

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.MinerListItem
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.data.dashboard.repo.StakeRepository
import com.frynetworks.fryapp.data.dashboard.repo.StakeSubmitPayload
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

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
    ) : this(miners, rewards, stakes, algod, bridge, session, clock, openUri = { uri -> launcher.open(uri, null) })

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
        job?.cancel()
        ui.value = StakeUiState(minerKey = minerKey, context = context, byod = byod, loading = true, lastWalletUri = ui.value.lastWalletUri)
        job = viewModelScope.launch { if (ensureConnected(reconnectOnly = true)) loadAmount() }
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
        job = viewModelScope.launch { runBalances() }
    }

    /** `stake_optin`. */
    fun optIn() {
        if (ui.value.state !is StakeState.OptInRequired) return
        job?.cancel()
        job = viewModelScope.launch { runOptIn() }
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
        val key = ui.value.minerKey ?: return
        val context = ui.value.context ?: return
        start(key, context, ui.value.byod)
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
        val address = address() ?: return

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
        val txId = try {
            val txn = bridge.buildAssetTransfer(address, StakeRepository.STAKE_WALLET, plan.asset.id, amountMicro, note)
            val signed = signSingle(txn)
            bridge.submit(listOf(signed), WAIT_ROUNDS).firstOrNull()
                ?: throw BridgeException(BridgeErrorCode.UNKNOWN, "The bridge returned no transaction id")
        } catch (e: CancellationException) {
            throw e
        } catch (e: BridgeException) {
            clearSummary()
            if (e.code == BridgeErrorCode.USER_REJECTED) dispatch(StakeEvent.SignRejected) else dispatch(StakeEvent.SubmitFailed(e.code.name, e.message ?: ""))
            return
        } catch (e: Exception) {
            clearSummary()
            dispatch(StakeEvent.SubmitFailed(e.errorCode(), e.message ?: ""))
            return
        }
        dispatch(StakeEvent.Submitted(txId))

        try {
            algod.waitConfirmed(txId, WAIT_ROUNDS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dispatch(StakeEvent.SubmitFailed(e.errorCode(), e.message ?: ""))
            return
        }
        dispatch(StakeEvent.TxnConfirmed)

        try {
            stakes.verifyTxn(address, txId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dispatch(StakeEvent.VerifyFailed(e.errorCode(), e.message ?: ""))
            return
        }
        dispatch(StakeEvent.Verified)

        try {
            val result = stakes.submit(
                context,
                StakeSubmitPayload(address = address, minerKey = key, txId = txId, amount = BigDecimal.valueOf(plan.amount), assetId = plan.asset.id),
            )
            dispatch(StakeEvent.Recorded(result.txId ?: txId, result.waived))
            runCatching { miners.refreshDetail(key) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dispatch(StakeEvent.RecordFailed(e.errorCode(), e.message ?: ""))
        }
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

        fun microFor(tokens: Long): Long = tokens * MICRO_PER_TOKEN

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
