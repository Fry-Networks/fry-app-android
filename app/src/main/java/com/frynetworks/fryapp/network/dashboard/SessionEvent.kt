package com.frynetworks.fryapp.network.dashboard

/** Session-level outcomes the network layer surfaces to the UI. */
sealed interface SessionEvent {
    /** The dashboard no longer recognises our session cookie (401 / empty session). */
    data object Expired : SessionEvent

    /** The dashboard rejected our fingerprint twice (403 DEVICE_MISMATCH); sign in again. */
    data object DeviceMismatch : SessionEvent

    data object SignedOut : SessionEvent
}

/** A dashboard API error envelope (`{success:false, code, message, action}`) as an exception. */
class DashboardException(
    val code: String,
    override val message: String,
    val action: String? = null,
    val httpStatus: Int = 0,
) : Exception(message)
