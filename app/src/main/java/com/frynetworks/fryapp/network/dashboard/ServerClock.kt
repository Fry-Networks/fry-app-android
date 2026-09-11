package com.frynetworks.fryapp.network.dashboard

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * Timestamps for `x-request-timestamp` must be within the server's window (not more than 10 s in
 * the future, not older than 15 min), so we track the offset between this device and the
 * dashboard from its `Date` header / `serverTime` fields instead of trusting the phone clock.
 */
class ServerClock {
    private val offsetMillis = AtomicLong(0)

    fun nowMillis(): Long = System.currentTimeMillis() + offsetMillis.get()

    fun nowSeconds(): Long = nowMillis() / 1000

    fun observeServerMillis(serverMillis: Long) {
        offsetMillis.set(serverMillis - System.currentTimeMillis())
    }

    fun observeServerSeconds(serverSeconds: Long) = observeServerMillis(serverSeconds * 1000)

    /** Parses an RFC 1123 `Date` response header; ignored when unparseable. */
    fun observeDateHeader(date: String?) {
        if (date.isNullOrBlank()) return
        val parsed = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(date)
        }.getOrNull() ?: return
        observeServerMillis(parsed.time)
    }
}
