package com.frynetworks.fryapp.ui.miners.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.data.dashboard.model.MyKeyDevice
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.ui.common.ErrorCopy
import com.frynetworks.fryapp.ui.common.Phase
import com.frynetworks.fryapp.ui.common.UiError
import com.frynetworks.fryapp.ui.common.toUiError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class KeyGroup(val family: MinerFamily, val devices: List<MyKeyDevice>)

data class MinerKeysUiState(
    val phase: Phase = Phase.Loading,
    val groups: List<KeyGroup> = emptyList(),
    val byodLicenses: List<String> = emptyList(),
    /** `<minerKey>|<portal>` ids whose credentials are currently revealed (auto-masked after 15 s). */
    val revealed: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: UiError? = null,
) {
    fun isRevealed(minerKey: String, portal: String): Boolean = revealId(minerKey, portal) in revealed

    companion object {
        fun revealId(minerKey: String, portal: String): String = "$minerKey|$portal"
    }
}

/** `GET /api/my-keys` grouped by family. Credential values are held in memory only and never logged. */
@HiltViewModel
class MinerKeysViewModel(
    private val miners: MinerRepository,
    private val session: SessionRepository,
    private val revealMillis: Long,
) : ViewModel() {

    @Inject
    constructor(miners: MinerRepository, session: SessionRepository) : this(miners, session, REVEAL_MILLIS)

    private val ui = MutableStateFlow(MinerKeysUiState())
    val uiState: StateFlow<MinerKeysUiState> = ui.asStateFlow()
    private val maskJobs = HashMap<String, Job>()

    init {
        load()
    }

    fun load() {
        if (ui.value.isLoading) return
        if (session.state.value !is SessionState.SignedIn) {
            ui.update { it.copy(phase = Phase.Error, error = UiError("UNAUTHORIZED", ErrorCopy.forCode("UNAUTHORIZED"))) }
            return
        }
        ui.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val response = miners.myKeys()
                val groups = response.devices
                    .groupBy { MinerFamily.fromMinerKey(it.minerKey.orEmpty()) }
                    .entries
                    .sortedBy { it.key.ordinal }
                    .map { (family, devices) -> KeyGroup(family, devices.sortedBy { it.nickname ?: it.minerKey.orEmpty() }) }
                ui.update {
                    it.copy(
                        isLoading = false,
                        groups = groups,
                        byodLicenses = response.byodLicenses,
                        phase = if (groups.isEmpty() && response.byodLicenses.isEmpty()) Phase.Empty else Phase.Content,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.update { it.copy(isLoading = false, error = e.toUiError(), phase = if (it.groups.isEmpty()) Phase.Error else Phase.Content) }
            }
        }
    }

    /** Reveal for [revealMillis], then mask again; tapping while revealed masks immediately. */
    fun toggleReveal(minerKey: String, portal: String) {
        val id = MinerKeysUiState.revealId(minerKey, portal)
        if (id in ui.value.revealed) {
            mask(id)
            return
        }
        ui.update { it.copy(revealed = it.revealed + id) }
        maskJobs[id]?.cancel()
        maskJobs[id] = viewModelScope.launch {
            delay(revealMillis)
            mask(id)
        }
    }

    private fun mask(id: String) {
        maskJobs.remove(id)?.cancel()
        ui.update { it.copy(revealed = it.revealed - id) }
    }

    override fun onCleared() {
        maskJobs.values.forEach { it.cancel() }
        maskJobs.clear()
    }

    companion object {
        const val REVEAL_MILLIS = 15_000L
    }
}
