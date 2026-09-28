package com.frynetworks.fryapp.provisioning

/**
 * ESP8266 SoftAP handoff (PROTOCOL.md v1.1): once `POST /provision` is accepted the board joins
 * the user's Wi-Fi and tears its setup AP down, so `/status` stops answering. Losing the AP at
 * that point means "joining", not "failed"; only the backend can confirm the board arrived.
 */
object SoftApHandoff {
    /** `/status` misses in a row (2 s apart) that count as the AP being gone. */
    const val FAILURES_FOR_HANDOFF = 3

    fun isHandoff(accepted: Boolean, consecutiveFailures: Int, apLost: Boolean): Boolean =
        accepted && (apLost || consecutiveFailures >= FAILURES_FOR_HANDOFF)
}
