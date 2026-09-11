package com.frynetworks.fryapp.auth

import com.frynetworks.fryapp.wallet.WalletBridge

/** Ends the dashboard session server-side (best effort), drops the wallet session, wipes local state. */
class SignOutUseCase(
    private val api: NextAuthApi,
    private val bridge: WalletBridge,
    private val session: SessionRepository,
) {
    suspend fun signOut() {
        runCatching {
            val csrf = api.csrf().csrfToken
            if (csrf != null) api.signOut(csrf, "true")
        }
        runCatching { bridge.disconnect() }
        session.clear()
    }
}
