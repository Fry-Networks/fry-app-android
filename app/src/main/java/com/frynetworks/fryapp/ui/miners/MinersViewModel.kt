package com.frynetworks.fryapp.ui.miners

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.data.dashboard.model.AssetTotalsResponse
import com.frynetworks.fryapp.data.dashboard.repo.FleetSummary
import com.frynetworks.fryapp.data.dashboard.repo.MinerListItem
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.data.dashboard.repo.MinerStatus
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.network.dashboard.ServerClock
import com.frynetworks.fryapp.network.dashboard.SessionEvent
import com.frynetworks.fryapp.network.dashboard.SessionEventBus
import com.frynetworks.fryapp.ui.common.ErrorCopy
import com.frynetworks.fryapp.ui.common.Phase
import com.frynetworks.fryapp.ui.common.UiError
import com.frynetworks.fryapp.ui.common.toUiError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/** Sort menu entries (`miners_sort_<name lowercase>`). */
enum class MinerSort(val label: String) { STATUS("Needs attention"), FAMILY("Family"), NAME("Name"), CLAIMABLE("Claimable") }

data class MinersUiState(
    val signedIn: Boolean = false,
    val phase: Phase = Phase.Loading,
    /** Filtered + sorted rows. */
    val items: List<MinerListItem> = emptyList(),
    /** Unfiltered row count (for the "x of y" line and the Empty phase). */
    val totalCount: Int = 0,
    val totals: AssetTotalsResponse? = null,
    val fleet: FleetSummary? = null,
    val query: String = "",
    val statusFilter: MinerStatus? = null,
    val familyFilter: MinerFamily? = null,
    /** Families present in the unfiltered list, for the family chips. */
    val availableFamilies: List<MinerFamily> = emptyList(),
    val sort: MinerSort = MinerSort.STATUS,
    val isRefreshing: Boolean = false,
    val error: UiError? = null,
    /** One-shot snackbar text (session expired, device mismatch); cleared by [MinersViewModel.consumeMessage]. */
    val message: String? = null,
)

private data class Local(
    val query: String = "",
    val statusFilter: MinerStatus? = null,
    val familyFilter: MinerFamily? = null,
    val sort: MinerSort = MinerSort.STATUS,
    val isRefreshing: Boolean = false,
    val loaded: Boolean = false,
    val lastRefreshAt: Long? = null,
    val error: UiError? = null,
    val message: String? = null,
)

/**
 * Pure list shaping shared by the ViewModel and its tests: search across key / display name /
 * product name, single-select status + family filters, then the chosen sort.
 */
object MinerListShaping {
    fun apply(
        all: List<MinerListItem>,
        query: String,
        status: MinerStatus?,
        family: MinerFamily?,
        sort: MinerSort,
    ): List<MinerListItem> {
        val needle = query.trim()
        val filtered = all.asSequence()
            .filter { status == null || it.status == status }
            .filter { family == null || it.family == family }
            .filter { needle.isEmpty() || matches(it, needle) }
            .toList()
        return when (sort) {
            MinerSort.STATUS -> filtered.sortedWith(compareBy({ it.status.rank }, { it.family.ordinal }, { it.displayName.lowercase() }))
            MinerSort.FAMILY -> filtered.sortedWith(compareBy({ it.family.ordinal }, { it.status.rank }, { it.displayName.lowercase() }))
            MinerSort.NAME -> filtered.sortedBy { it.displayName.lowercase() }
            MinerSort.CLAIMABLE -> filtered.sortedWith(compareByDescending<MinerListItem> { claimable(it) }.thenBy { it.displayName.lowercase() })
        }
    }

    fun claimable(item: MinerListItem): BigDecimal = item.rewards?.claimable ?: BigDecimal.ZERO

    private fun matches(item: MinerListItem, needle: String): Boolean =
        item.minerKey.contains(needle, ignoreCase = true) ||
            item.displayName.contains(needle, ignoreCase = true) ||
            (item.remote?.nickname?.contains(needle, ignoreCase = true) ?: false) ||
            (item.remote?.productName?.contains(needle, ignoreCase = true) ?: false) ||
            (item.local?.name?.contains(needle, ignoreCase = true) ?: false)
}

@HiltViewModel
class MinersViewModel @Inject constructor(
    private val miners: MinerRepository,
    private val rewards: RewardsRepository,
    private val session: SessionRepository,
    private val bus: SessionEventBus,
    private val clock: ServerClock,
) : ViewModel() {

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<MinersUiState> = combine(
        miners.observeMiners(),
        rewards.observeAssetTotals(),
        miners.observeFleetSummary(),
        session.state,
        local,
    ) { all, totals, fleet, sessionState, l ->
        val signedIn = sessionState is SessionState.SignedIn
        val items = MinerListShaping.apply(all, l.query, l.statusFilter, l.familyFilter, l.sort)
        MinersUiState(
            signedIn = signedIn,
            phase = phaseFor(signedIn, all.size, l),
            items = items,
            totalCount = all.size,
            totals = totals,
            fleet = fleet,
            query = l.query,
            statusFilter = l.statusFilter,
            familyFilter = l.familyFilter,
            availableFamilies = all.map { it.family }.distinct().sortedBy { it.ordinal },
            sort = l.sort,
            isRefreshing = l.isRefreshing,
            error = l.error,
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MinersUiState(signedIn = session.state.value is SessionState.SignedIn))

    init {
        viewModelScope.launch { bus.events.collect(::onSessionEvent) }
        viewModelScope.launch {
            session.state.collect { state ->
                if (state is SessionState.SignedIn && local.value.lastRefreshAt == null) refresh()
                if (state is SessionState.SignedOut) local.update { it.copy(loaded = false, lastRefreshAt = null, error = null) }
            }
        }
    }

    private fun phaseFor(signedIn: Boolean, allCount: Int, l: Local): Phase = when {
        !signedIn -> Phase.Content
        allCount > 0 -> Phase.Content
        l.error != null -> Phase.Error
        !l.loaded -> Phase.Loading
        else -> Phase.Empty
    }

    fun refresh() {
        if (local.value.isRefreshing) return
        if (session.state.value !is SessionState.SignedIn) return
        local.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            try {
                miners.refresh()
                local.update { it.copy(isRefreshing = false, loaded = true, lastRefreshAt = clock.nowMillis()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(isRefreshing = false, loaded = true, error = e.toUiError()) }
            }
        }
    }

    /** Screen resume hook: refresh when the last successful refresh is older than 5 minutes. */
    fun onResume() {
        val last = local.value.lastRefreshAt
        if (last == null || clock.nowMillis() - last >= STALE_AFTER_MILLIS) refresh()
    }

    fun setQuery(query: String) = local.update { it.copy(query = query) }

    /** Tap a selected chip again to clear it. */
    fun toggleStatusFilter(status: MinerStatus) =
        local.update { it.copy(statusFilter = if (it.statusFilter == status) null else status) }

    fun toggleFamilyFilter(family: MinerFamily) =
        local.update { it.copy(familyFilter = if (it.familyFilter == family) null else family) }

    fun setSort(sort: MinerSort) = local.update { it.copy(sort = sort) }

    fun dismissError() = local.update { it.copy(error = null) }

    fun consumeMessage() = local.update { it.copy(message = null) }

    private suspend fun onSessionEvent(event: SessionEvent) {
        val message = when (event) {
            SessionEvent.Expired -> ErrorCopy.forCode("UNAUTHORIZED")
            SessionEvent.DeviceMismatch -> ErrorCopy.forCode("DEVICE_MISMATCH")
            SessionEvent.SignedOut -> return
        }
        // Fall back to the inline sign-in: forget the local session and the dashboard caches.
        session.clear()
        runCatching { miners.clearCache() }
        runCatching { rewards.clearCache() }
        local.update { it.copy(message = message, loaded = false, lastRefreshAt = null, error = null, isRefreshing = false) }
    }

    companion object {
        const val STALE_AFTER_MILLIS = 5 * 60 * 1000L
    }
}
