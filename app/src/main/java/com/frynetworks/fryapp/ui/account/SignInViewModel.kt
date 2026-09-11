package com.frynetworks.fryapp.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.auth.NewUserProfile
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.auth.SignInResult
import com.frynetworks.fryapp.auth.SignInUseCase
import com.frynetworks.fryapp.auth.SignOutUseCase
import com.frynetworks.fryapp.wallet.BridgeEvent
import com.frynetworks.fryapp.wallet.WalletBridge
import com.frynetworks.fryapp.wallet.WalletVendor
import com.frynetworks.fryapp.wallet.bridge.ExternalUriLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignInError(val code: String, val message: String)

data class SignInUiState(
    val session: SessionState,
    val installed: Set<WalletVendor>,
    val busy: Boolean = false,
    val error: SignInError? = null,
    /** Set when the dashboard has never seen this wallet: collect email + name, then resubmit. */
    val needsProfileFor: String? = null,
    val pendingVendor: WalletVendor? = null,
    /** Last deep link the bridge asked us to open; lets the user re-open the wallet app. */
    val lastWalletUri: String? = null,
)

private data class LocalState(
    val busy: Boolean = false,
    val error: SignInError? = null,
    val needsProfileFor: String? = null,
    val pendingVendor: WalletVendor? = null,
    val lastWalletUri: String? = null,
)

@HiltViewModel
class SignInViewModel(
    private val session: SessionRepository,
    private val signIn: SignInUseCase,
    private val signOut: SignOutUseCase,
    bridge: WalletBridge,
    private val installedVendors: () -> Set<WalletVendor>,
    private val openUri: (String) -> Unit = {},
) : ViewModel() {

    @Inject
    constructor(
        session: SessionRepository,
        signIn: SignInUseCase,
        signOut: SignOutUseCase,
        bridge: WalletBridge,
        launcher: ExternalUriLauncher,
    ) : this(
        session, signIn, signOut, bridge,
        installedVendors = { WalletVendor.entries.filter(launcher::isInstalled).toSet() },
        openUri = { uri -> launcher.open(uri, null) },
    )

    private val local = MutableStateFlow(LocalState())

    val uiState: StateFlow<SignInUiState> = combine(session.state, local) { s, l ->
        SignInUiState(
            session = s,
            installed = installedVendors(),
            busy = l.busy,
            error = l.error,
            needsProfileFor = l.needsProfileFor,
            pendingVendor = l.pendingVendor,
            lastWalletUri = l.lastWalletUri,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SignInUiState(session.state.value, installedVendors()))

    init {
        viewModelScope.launch {
            bridge.events.collect { event ->
                if (event is BridgeEvent.OpenUri) local.update { it.copy(lastWalletUri = event.uri) }
            }
        }
    }

    fun connect(vendor: WalletVendor) {
        if (local.value.busy) return
        local.update { it.copy(busy = true, error = null, pendingVendor = vendor, needsProfileFor = null) }
        viewModelScope.launch { handle(signIn.signIn(vendor, profile = null)) }
    }

    fun submitProfile(email: String, firstName: String, lastName: String) {
        val vendor = local.value.pendingVendor ?: return
        if (local.value.busy) return
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch { handle(signIn.signIn(vendor, NewUserProfile(email.trim(), firstName.trim(), lastName.trim()))) }
    }

    private fun handle(result: SignInResult) {
        when (result) {
            is SignInResult.Success -> local.update { it.copy(busy = false, needsProfileFor = null, error = null) }
            is SignInResult.NeedsProfile -> local.update { it.copy(busy = false, needsProfileFor = result.address) }
            is SignInResult.Failure -> local.update { it.copy(busy = false, error = SignInError(result.code, result.message)) }
        }
    }

    fun openWallet() {
        local.value.lastWalletUri?.let(openUri)
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    fun cancelProfile() {
        local.update { it.copy(needsProfileFor = null, pendingVendor = null) }
    }

    fun signOut() {
        viewModelScope.launch { signOut.signOut() }
    }
}
