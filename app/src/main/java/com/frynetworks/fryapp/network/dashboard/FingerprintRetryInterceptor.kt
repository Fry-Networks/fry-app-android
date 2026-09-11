package com.frynetworks.fryapp.network.dashboard

import com.google.gson.JsonParser
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Mirrors the dashboard's `fetchWithFingerprintRetry`: on `409 DEVICE_FINGERPRINT_REFRESH` or
 * `403 DEVICE_MISMATCH` re-bind the device fingerprint (capture + session update) and retry the
 * request exactly once. A second `DEVICE_MISMATCH` means the session cannot be used from this
 * client; [onSessionEvent] receives [SessionEvent.DeviceMismatch] so the UI can sign out.
 *
 * The server allows 3 mismatches per 30 s before blocking, so rebinds are rate-limited to one
 * per [minRebindIntervalMillis].
 */
class FingerprintRetryInterceptor(
    private val rebind: () -> Boolean,
    private val onSessionEvent: (SessionEvent) -> Unit,
    private val minRebindIntervalMillis: Long = 5_000L,
) : Interceptor {

    @Volatile private var lastRebindAt = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val first = chain.proceed(request)
        val code = fingerprintCode(first) ?: return first

        val now = System.currentTimeMillis()
        if (now - lastRebindAt < minRebindIntervalMillis) {
            if (code == CODE_MISMATCH) onSessionEvent(SessionEvent.DeviceMismatch)
            return first
        }
        lastRebindAt = now
        val rebound = runCatching { rebind() }.getOrDefault(false)
        if (!rebound) {
            if (code == CODE_MISMATCH) onSessionEvent(SessionEvent.DeviceMismatch)
            return first
        }
        first.close()
        val second = chain.proceed(request)
        if (fingerprintCode(second) == CODE_MISMATCH) onSessionEvent(SessionEvent.DeviceMismatch)
        return second
    }

    /** Returns the fingerprint error code when the response is one we retry, else null. */
    private fun fingerprintCode(response: Response): String? {
        if (response.code != 409 && response.code != 403) return null
        val peeked = runCatching { response.peekBody(PEEK_BYTES).string() }.getOrNull() ?: return null
        val code = runCatching { JsonParser.parseString(peeked).asJsonObject["code"]?.asString }.getOrNull() ?: return null
        return if (code == CODE_REFRESH || code == CODE_MISMATCH) code else null
    }

    private companion object {
        const val CODE_REFRESH = "DEVICE_FINGERPRINT_REFRESH"
        const val CODE_MISMATCH = "DEVICE_MISMATCH"
        const val PEEK_BYTES = 4096L
    }
}
