package com.frynetworks.fryapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.auth.FingerprintBinder
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.auth.SignOutUseCase
import com.frynetworks.fryapp.data.SettingsRepository
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Account-card state for the signed-in dashboard session (null-safe when signed out). */
data class AccountUiState(
    val busy: Boolean = false,
    /** One-shot message after sign-out / re-bind; cleared by [SettingsViewModel.consumeAccountMessage]. */
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    session: SessionRepository,
    private val signOut: SignOutUseCase,
    private val binder: FingerprintBinder,
    private val miners: MinerRepository,
    private val rewards: RewardsRepository,
) : ViewModel() {

    private val _wallet = MutableStateFlow(settingsRepository.getDefaultWallet())
    val wallet = _wallet.asStateFlow()

    val session: StateFlow<SessionState> = session.state

    private val _account = MutableStateFlow(AccountUiState())
    val account: StateFlow<AccountUiState> = _account.asStateFlow()

    fun setWallet(value: String) {
        _wallet.value = value
        settingsRepository.setDefaultWallet(value)
    }

    /** `settings_signout`: server sign-out (best effort), wallet disconnect, local wipe, cache drop. */
    fun signOutAccount() {
        if (_account.value.busy) return
        _account.update { it.copy(busy = true) }
        viewModelScope.launch {
            signOut.signOut()
            runCatching { miners.clearCache() }
            runCatching { rewards.clearCache() }
            _account.update { it.copy(busy = false, message = "Signed out.") }
        }
    }

    /** `settings_rebind_fingerprint`: re-captures the device fingerprint into the dashboard session. */
    fun rebindDevice() {
        if (_account.value.busy) return
        _account.update { it.copy(busy = true) }
        viewModelScope.launch {
            val bound = binder.rebind()
            _account.update {
                it.copy(busy = false, message = if (bound) "Device re-bound to your session." else "Could not re-bind this device. Try signing in again.")
            }
        }
    }

    fun consumeAccountMessage() = _account.update { it.copy(message = null) }
}
