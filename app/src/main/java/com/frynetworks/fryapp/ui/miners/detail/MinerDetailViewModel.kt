package com.frynetworks.fryapp.ui.miners.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import com.frynetworks.fryapp.data.dashboard.model.HardwareStatusEntry
import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.RewardSummary
import com.frynetworks.fryapp.data.dashboard.model.WithdrawableResponse
import com.frynetworks.fryapp.data.dashboard.repo.MinerListItem
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.data.dashboard.repo.StakeRepository
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.network.dashboard.ServerClock
import com.frynetworks.fryapp.ui.common.ErrorCopy
import com.frynetworks.fryapp.ui.common.Phase
import com.frynetworks.fryapp.ui.common.UiError
import com.frynetworks.fryapp.ui.common.toUiError
import com.frynetworks.fryapp.util.AlgorandAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DetailDialog {
    data object Rename : DetailDialog
    data object RewardWallet : DetailDialog
    data class Withdraw(val context: StakeContext) : DetailDialog
}

sealed interface DetailSheet {
    data class Claim(val no: Int?) : DetailSheet
    data class Stake(val context: StakeContext, val byod: Boolean) : DetailSheet
}

data class MinerDetailUiState(
    val minerKey: String,
    val family: MinerFamily,
    val phase: Phase = Phase.Loading,
    val signedIn: Boolean = false,
    val address: String? = null,
    val listItem: MinerListItem? = null,
    val detail: DeviceDetail? = null,
    val summary: RewardSummary? = null,
    val product: Product? = null,
    val hardware: HardwareStatusEntry? = null,
    val withdrawable: Map<String, WithdrawableResponse> = emptyMap(),
    /** Unlock instants (server clock) keyed by stake kind; see [MinerActionRules.lockTargets]. */
    val lockTargets: Map<String, Long> = emptyMap(),
    val actions: MinerActionGating = MinerActionGating(),
    val isRefreshing: Boolean = false,
    val error: UiError? = null,
    val dialog: DetailDialog? = null,
    val sheet: DetailSheet? = null,
    val busy: Boolean = false,
    val actionError: UiError? = null,
    val actionMessage: String? = null,
) {
    val displayName: String
        get() = detail?.nickname?.takeIf { it.isNotBlank() }
            ?: listItem?.displayName
            ?: MinerListItem.shortKey(minerKey)
    val isVirtual: Boolean get() = detail?.virtual ?: listItem?.remote?.virtual ?: false
    val isByod: Boolean get() = !detail?.byod.isNullOrBlank() || (listItem?.remote?.byod ?: false)
}

private data class Local(
    val product: Product? = null,
    val hardware: HardwareStatusEntry? = null,
    val withdrawable: Map<String, WithdrawableResponse> = emptyMap(),
    val isRefreshing: Boolean = false,
    val loaded: Boolean = false,
    val error: UiError? = null,
    val dialog: DetailDialog? = null,
    val sheet: DetailSheet? = null,
    val busy: Boolean = false,
    val actionError: UiError? = null,
    val actionMessage: String? = null,
)

@HiltViewModel
class MinerDetailViewModel(
    val minerKey: String,
    private val miners: MinerRepository,
    private val rewards: RewardsRepository,
    private val stakes: StakeRepository,
    private val session: SessionRepository,
    private val clock: ServerClock,
) : ViewModel() {

    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        miners: MinerRepository,
        rewards: RewardsRepository,
        stakes: StakeRepository,
        session: SessionRepository,
        clock: ServerClock,
    ) : this(checkNotNull(savedStateHandle.get<String>("minerKey")), miners, rewards, stakes, session, clock)

    private val family = MinerFamily.fromMinerKey(minerKey)
    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<MinerDetailUiState> = combine(
        miners.observeDetail(minerKey),
        rewards.observeSummary(minerKey),
        miners.observeMiners().map { list -> list.firstOrNull { it.minerKey == minerKey } },
        session.state,
        local,
    ) { detail, summary, item, sessionState, l ->
        val signedIn = sessionState is SessionState.SignedIn
        MinerDetailUiState(
            minerKey = minerKey,
            family = family,
            phase = when {
                detail != null || item != null -> Phase.Content
                l.error != null -> Phase.Error
                !l.loaded -> Phase.Loading
                else -> Phase.Empty
            },
            signedIn = signedIn,
            address = (sessionState as? SessionState.SignedIn)?.profile?.address,
            listItem = item,
            detail = detail,
            summary = summary ?: item?.rewards,
            product = l.product,
            hardware = l.hardware,
            withdrawable = l.withdrawable,
            lockTargets = MinerActionRules.lockTargets(detail, l.withdrawable, clock.nowMillis()),
            actions = MinerActionRules.compute(
                signedIn = signedIn,
                detail = detail,
                summary = summary ?: item?.rewards,
                family = family,
                isNodeFlag = item?.remote?.node ?: false,
                product = l.product,
                withdrawable = l.withdrawable,
                nowMillis = clock.nowMillis(),
            ),
            isRefreshing = l.isRefreshing,
            error = l.error,
            dialog = l.dialog,
            sheet = l.sheet,
            busy = l.busy,
            actionError = l.actionError,
            actionMessage = l.actionMessage,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        MinerDetailUiState(minerKey = minerKey, family = family, signedIn = session.state.value is SessionState.SignedIn),
    )

    init {
        refresh()
    }

    /** Server-clock "now" for countdowns rendered by the screen. */
    fun now(): Long = clock.nowMillis()

    fun refresh() {
        if (local.value.isRefreshing) return
        if (session.state.value !is SessionState.SignedIn) {
            local.update { it.copy(loaded = true, error = UiError("UNAUTHORIZED", ErrorCopy.forCode("UNAUTHORIZED"))) }
            return
        }
        local.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            val detail = try {
                miners.refreshDetail(minerKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(isRefreshing = false, loaded = true, error = e.toUiError()) }
                return@launch
            }
            runCatching { rewards.refreshSummary(minerKey) }
            val product = runCatching { miners.product(minerKey) }.getOrNull()
            val hardware = if (!detail.virtual && family.prefix in MinerFamily.hardwareMacPrefixes) {
                runCatching { miners.hardwareStatus(listOf(minerKey))[minerKey] }.getOrNull()
            } else {
                null
            }
            val withdrawable = loadWithdrawable(detail)
            local.update {
                it.copy(isRefreshing = false, loaded = true, product = product, hardware = hardware, withdrawable = withdrawable)
            }
        }
    }

    private suspend fun loadWithdrawable(detail: DeviceDetail): Map<String, WithdrawableResponse> {
        val address = session.signedInAddress ?: return emptyMap()
        val out = LinkedHashMap<String, WithdrawableResponse>()
        for (context in MinerActionRules.existingStakes(detail)) {
            runCatching { stakes.withdrawable(address, minerKey, context) }.getOrNull()?.let { out[context.key()] = it }
        }
        return out
    }

    // --- dialogs / sheets -------------------------------------------------------------------

    fun openDialog(dialog: DetailDialog) = local.update { it.copy(dialog = dialog, actionError = null) }
    fun closeDialog() = local.update { it.copy(dialog = null, actionError = null) }

    fun openClaim(no: Int? = null) {
        if (!uiState.value.signedIn) return
        local.update { it.copy(sheet = DetailSheet.Claim(no)) }
    }

    fun openStake(context: StakeContext) {
        if (!uiState.value.signedIn) return
        local.update { it.copy(sheet = DetailSheet.Stake(context, uiState.value.isByod)) }
    }

    fun closeSheet() = local.update { it.copy(sheet = null) }

    fun consumeActionMessage() = local.update { it.copy(actionMessage = null) }

    // --- session-only actions ---------------------------------------------------------------

    fun rename(nickname: String) {
        val name = nickname.trim()
        if (name.isEmpty()) {
            local.update { it.copy(actionError = UiError("INVALID_NAME", "Enter a name for this miner.")) }
            return
        }
        runAction("Miner renamed.") { miners.rename(minerKey, name) }
    }

    fun setRewardWallet(address: String) {
        val wallet = address.trim()
        if (!AlgorandAddress.isValid(wallet)) {
            local.update { it.copy(actionError = UiError("INVALID_ADDRESS", ErrorCopy.forCode("INVALID_ADDRESS"))) }
            return
        }
        runAction("Reward wallet updated.") { miners.setRewardWallet(minerKey, wallet) }
    }

    fun withdraw(context: StakeContext) {
        val address = session.signedInAddress ?: return
        runAction("Withdrawal submitted.") {
            val result = stakes.withdraw(context, address, minerKey)
            result.txId?.let { local.update { l -> l.copy(actionMessage = "Withdrawal submitted (tx ${it.take(8)}…).") } }
        }
    }

    private fun runAction(successMessage: String, block: suspend () -> Unit) {
        if (local.value.busy) return
        if (session.state.value !is SessionState.SignedIn) return
        local.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                block()
                runCatching { miners.refreshDetail(minerKey) }
                local.update { it.copy(busy = false, dialog = null, actionMessage = it.actionMessage ?: successMessage) }
                refreshAfterAction()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(busy = false, actionError = e.toUiError()) }
            }
        }
    }

    private suspend fun refreshAfterAction() {
        val detail = runCatching { miners.refreshDetail(minerKey) }.getOrNull() ?: return
        val withdrawable = loadWithdrawable(detail)
        local.update { it.copy(withdrawable = withdrawable) }
    }

    /** Called by the claim/stake sheets once they reach Done so the cards reflect the new state. */
    fun onSheetCompleted() {
        viewModelScope.launch {
            runCatching { rewards.refreshSummary(minerKey) }
            refreshAfterAction()
        }
    }
}
