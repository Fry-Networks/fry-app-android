package com.frynetworks.fryapp.ui.miners.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.data.dashboard.model.ActivityEvent
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.network.dashboard.ServerClock
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

data class ActivityUiState(
    val phase: Phase = Phase.Loading,
    val events: List<ActivityEvent> = emptyList(),
    val isLoading: Boolean = false,
    val error: UiError? = null,
)

/** `POST /api/activity/recent` — at most [MAX_EVENTS] rows. */
@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val rewards: RewardsRepository,
    private val session: SessionRepository,
    private val clock: ServerClock,
) : ViewModel() {

    private val ui = MutableStateFlow(ActivityUiState())
    val uiState: StateFlow<ActivityUiState> = ui.asStateFlow()

    init {
        load()
    }

    fun now(): Long = clock.nowMillis()

    fun load() {
        if (ui.value.isLoading) return
        if (session.state.value !is SessionState.SignedIn) {
            ui.update { it.copy(phase = Phase.Error, error = UiError("UNAUTHORIZED", ErrorCopy.forCode("UNAUTHORIZED"))) }
            return
        }
        ui.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val events = rewards.activity().take(MAX_EVENTS)
                ui.update { it.copy(isLoading = false, events = events, phase = if (events.isEmpty()) Phase.Empty else Phase.Content) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.update { it.copy(isLoading = false, error = e.toUiError(), phase = if (it.events.isEmpty()) Phase.Error else Phase.Content) }
            }
        }
    }

    companion object {
        const val MAX_EVENTS = 12
    }
}
