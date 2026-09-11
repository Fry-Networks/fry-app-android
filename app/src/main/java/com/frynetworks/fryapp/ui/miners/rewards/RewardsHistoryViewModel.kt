package com.frynetworks.fryapp.ui.miners.rewards

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.data.dashboard.model.RewardItem
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.ui.common.ErrorCopy
import com.frynetworks.fryapp.ui.common.Phase
import com.frynetworks.fryapp.ui.common.UiError
import com.frynetworks.fryapp.ui.common.toUiError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RewardsHistoryUiState(
    val minerKey: String,
    val asset: FryAsset,
    val phase: Phase = Phase.Loading,
    val page: Int = 1,
    val totalPages: Int = 1,
    val items: List<RewardItem> = emptyList(),
    val weeklyCount: Int? = null,
    val dailyCount: Int? = null,
    val totalCount: Int? = null,
    val isLoading: Boolean = false,
    val error: UiError? = null,
    /** Reward row (`no`) whose claim sheet is open; null when closed. */
    val claimNo: Int? = null,
    val signedIn: Boolean = false,
) {
    val hasPrev: Boolean get() = page > 1 && !isLoading
    val hasNext: Boolean get() = page < totalPages && !isLoading
}

@HiltViewModel
class RewardsHistoryViewModel(
    val minerKey: String,
    private val rewards: RewardsRepository,
    private val session: SessionRepository,
) : ViewModel() {

    @Inject
    constructor(savedStateHandle: SavedStateHandle, rewards: RewardsRepository, session: SessionRepository) :
        this(checkNotNull(savedStateHandle.get<String>("minerKey")), rewards, session)

    private val ui = MutableStateFlow(
        RewardsHistoryUiState(
            minerKey = minerKey,
            asset = MinerFamily.fromMinerKey(minerKey).rewardAsset,
            signedIn = session.state.value is SessionState.SignedIn,
        ),
    )
    val uiState: StateFlow<RewardsHistoryUiState> = ui.asStateFlow()

    init {
        load(1)
    }

    fun load(page: Int) {
        if (ui.value.isLoading) return
        if (session.state.value !is SessionState.SignedIn) {
            ui.update { it.copy(phase = Phase.Error, error = UiError("UNAUTHORIZED", ErrorCopy.forCode("UNAUTHORIZED")), signedIn = false) }
            return
        }
        ui.update { it.copy(isLoading = true, error = null, signedIn = true) }
        viewModelScope.launch {
            try {
                val response = rewards.historyPage(minerKey, page)
                ui.update {
                    it.copy(
                        isLoading = false,
                        page = page,
                        totalPages = (response.totalPages ?: 1).coerceAtLeast(1),
                        items = response.items,
                        weeklyCount = response.weeklyCount,
                        dailyCount = response.dailyCount,
                        totalCount = response.totalCount,
                        phase = if (response.items.isEmpty()) Phase.Empty else Phase.Content,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.update { it.copy(isLoading = false, error = e.toUiError(), phase = if (it.items.isEmpty()) Phase.Error else Phase.Content) }
            }
        }
    }

    fun next() {
        if (ui.value.hasNext) load(ui.value.page + 1)
    }

    fun prev() {
        if (ui.value.hasPrev) load(ui.value.page - 1)
    }

    fun retry() = load(ui.value.page)

    fun openClaim(no: Int) {
        if (ui.value.signedIn) ui.update { it.copy(claimNo = no) }
    }

    fun closeClaim() = ui.update { it.copy(claimNo = null) }

    /** Reloads the current page after a per-row claim completes. */
    fun onClaimCompleted() = load(ui.value.page)
}
