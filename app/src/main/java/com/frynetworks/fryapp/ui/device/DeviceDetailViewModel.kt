package com.frynetworks.fryapp.ui.device

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.data.Device
import com.frynetworks.fryapp.data.DeviceRepository
import com.frynetworks.fryapp.data.UpdateCheckResult
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeviceDetailViewModel(
    private val minerKey: String,
    private val repository: DeviceRepository,
    miners: MinerRepository,
    session: SessionRepository,
    nowMillis: () -> Long,
) : ViewModel() {

    @Inject
    constructor(
        repository: DeviceRepository,
        miners: MinerRepository,
        session: SessionRepository,
        savedStateHandle: SavedStateHandle,
    ) : this(checkNotNull(savedStateHandle.get<String>("minerKey")), repository, miners, session, System::currentTimeMillis)

    val device: StateFlow<Device?> = repository.observeDevices()
        .map { list -> list.firstOrNull { it.minerKey == minerKey } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * AP5: the dashboard's live status (`POST /api/devices/{key}` with the session wallet), polled
     * only while collected, i.e. while the screen is visible; nothing runs when it is hidden.
     */
    val liveStatus: StateFlow<LiveStatus> = LiveStatusPoller(session.state, { miners.refreshDetail(minerKey) }, nowMillis)
        .updates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), LiveStatus.Checking)

    private val _updateState = MutableStateFlow<UpdateCheckResult?>(null)
    val updateState = _updateState.asStateFlow()

    fun checkForUpdate() {
        val current = device.value ?: return
        viewModelScope.launch {
            _updateState.value = repository.checkForUpdate(current)
        }
    }
}
