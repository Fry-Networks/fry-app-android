package com.frynetworks.fryapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.data.DeviceRepository
import com.frynetworks.fryapp.data.SettingsRepository
import com.frynetworks.fryapp.util.DeviceSection
import com.frynetworks.fryapp.util.groupDevicesByMinerType
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
class HomeViewModel @Inject constructor(
    private val repository: DeviceRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val sections: StateFlow<List<DeviceSection>> = repository.observeDevices()
        .map { groupDevicesByMinerType(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    /** Pull-to-refresh: forces a fresh one-shot read of the device table (see
     *  [DeviceRepository.refreshOnce]) rather than relying solely on the live Flow. */
    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                repository.refreshOnce()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** Null means no default wallet is configured — the caller should route to Settings
     *  instead of firing a broken claim URL. */
    fun claimUrlOrNull(): String? = ClaimLink.build(settingsRepository.getDefaultWallet())
}
