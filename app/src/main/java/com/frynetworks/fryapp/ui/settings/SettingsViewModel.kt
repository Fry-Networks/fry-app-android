package com.frynetworks.fryapp.ui.settings

import androidx.lifecycle.ViewModel
import com.frynetworks.fryapp.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _wallet = MutableStateFlow(settingsRepository.getDefaultWallet())
    val wallet = _wallet.asStateFlow()

    fun setWallet(value: String) {
        _wallet.value = value
        settingsRepository.setDefaultWallet(value)
    }
}
