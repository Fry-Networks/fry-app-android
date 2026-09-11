package com.frynetworks.fryapp.auth

import com.frynetworks.fryapp.network.dashboard.cookies.PersistentCookieJar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SignInStep {
    ConnectingWallet, CheckingUser, BuildingProof, AwaitingSignature, Authenticating, BindingDevice, Verifying,
}

sealed interface SessionState {
    data object SignedOut : SessionState
    data class SigningIn(val step: SignInStep) : SessionState
    data class SignedIn(val profile: SessionProfile, val fingerprintBound: Boolean) : SessionState
}

/**
 * Single source of truth for "are we signed in to the dashboard". The session cookie lives in the
 * [PersistentCookieJar]; the profile is remembered in [SessionStore]; this class only tracks the
 * state machine and restores it at start-up by asking the dashboard.
 */
class SessionRepository(
    private val api: NextAuthApi,
    private val jar: PersistentCookieJar,
    private val store: SessionStore,
) {
    private val _state = MutableStateFlow<SessionState>(initialState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private fun initialState(): SessionState {
        val profile = store.load()
        return if (profile != null && jar.hasSessionCookie()) SessionState.SignedIn(profile, profile.fingerprint != null) else SessionState.SignedOut
    }

    val signedInAddress: String?
        get() = (_state.value as? SessionState.SignedIn)?.profile?.address

    fun setStep(step: SignInStep) {
        _state.value = SessionState.SigningIn(step)
    }

    fun markSignedIn(profile: SessionProfile, fingerprintBound: Boolean) {
        store.save(profile)
        _state.value = SessionState.SignedIn(profile, fingerprintBound)
    }

    fun markSignedOut() {
        _state.value = SessionState.SignedOut
    }

    /** Forget everything local: cookies, profile, state. Does not call the server. */
    fun clear() {
        jar.clear()
        store.save(null)
        _state.value = SessionState.SignedOut
    }

    /** Re-validates a remembered session against the dashboard; purges it when the server says `{}`. */
    suspend fun restore(): SessionState {
        if (!jar.hasSessionCookie()) {
            if (_state.value !is SessionState.SigningIn) clear()
            return _state.value
        }
        val session = runCatching { api.session() }.getOrNull() ?: return _state.value
        val address = session.getAsJsonObject("user")?.get("address")?.takeIf { !it.isJsonNull }?.asString
        if (address.isNullOrBlank()) {
            clear()
            return _state.value
        }
        val remembered = store.load()
        val fingerprint = session.get("deviceFingerprint")?.takeIf { !it.isJsonNull }?.asString
        val profile = (remembered ?: SessionProfile(address)).copy(address = address, fingerprint = fingerprint ?: remembered?.fingerprint)
        markSignedIn(profile, fingerprint != null)
        return _state.value
    }
}
