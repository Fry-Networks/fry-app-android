package com.frynetworks.fryapp.data.keys

import com.frynetworks.fryapp.auth.SessionRepository
import javax.inject.Inject

fun interface KeyChecker {
    suspend fun check(minerKey: String): KeyCheckResult
}

/** Checks as the signed-in wallet; signed out, the key cannot be checked at all. */
class DashboardKeyChecker @Inject constructor(
    private val client: KeyCheckClient,
    private val session: SessionRepository,
) : KeyChecker {
    override suspend fun check(minerKey: String): KeyCheckResult {
        val address = session.signedInAddress
            ?: return KeyCheckResult.Unverifiable(null, "Sign in to check who owns this key before you set it up.")
        return client.check(address, minerKey)
    }
}
