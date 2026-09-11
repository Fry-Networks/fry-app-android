package com.frynetworks.fryapp.auth

import com.frynetworks.fryapp.wallet.WalletBridge

/**
 * Ends the dashboard session server-side (best effort), drops the wallet session, drops the cached
 * dashboard rows ([clearCaches], best effort — the local `devices` table is never touched), wipes
 * local state.
 */
class SignOutUseCase(
    private val api: NextAuthApi,
    private val bridge: WalletBridge,
    private val session: SessionRepository,
    private val clearCaches: suspend () -> Unit = {},
) {
    suspend fun signOut() {
        runCatching {
            val csrf = api.csrf().csrfToken
            if (csrf != null) api.signOut(csrf, "true")
        }
        runCatching { bridge.disconnect() }
        runCatching { clearCaches() }
        session.clear()
    }
}
