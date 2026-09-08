package com.frynetworks.fryapp.ui.device

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.data.Device
import com.frynetworks.fryapp.data.DeviceRepository
import com.frynetworks.fryapp.data.UpdateCheckResult
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
class DeviceDetailViewModel @Inject constructor(
    private val repository: DeviceRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val minerKey: String = checkNotNull(savedStateHandle["minerKey"])

    val device: StateFlow<Device?> = repository.observeDevices()
        .map { list -> list.firstOrNull { it.minerKey == minerKey } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _updateState = MutableStateFlow<UpdateCheckResult?>(null)
    val updateState = _updateState.asStateFlow()

    fun checkForUpdate() {
        val current = device.value ?: return
        viewModelScope.launch {
            _updateState.value = repository.checkForUpdate(current)
        }
    }
}
