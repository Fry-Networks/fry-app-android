package com.frynetworks.fryapp.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.data.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs the Home tab's device-count badge in [FryNavHost]. Kept separate from
 * [com.frynetworks.fryapp.ui.home.HomeViewModel] because the bottom nav bar lives above the
 * NavHost, outside any single screen's own ViewModel scope.
 */
@HiltViewModel
class NavBadgeViewModel @Inject constructor(
    repository: DeviceRepository,
) : ViewModel() {
    val deviceCount: StateFlow<Int> = repository.observeDevices()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
