package com.frynetworks.fryapp.network.dashboard

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Per-session request-signing credentials from `GET /api/auth/signing-key` (registration_portal
 * R11/R12): `key` is the HMAC key for `x-request-signature`, `clientToken` is the `x-client-token`
 * the server derived for this session and User-Agent. They live in memory only and are refetched
 * [REFRESH_MARGIN_SECONDS] before the server's `ttlSeconds` runs out, or right after [invalidate].
 *
 * - 404: a dashboard from before R11 that still verifies the build-time signer → [Credentials.Legacy].
 * - 401: no session; the request goes out legacy-signed so the route answers with its own 401.
 * - anything else fails closed with an [IOException]: the build-time constant can never pass a
 *   current dashboard, so falling back to it would only turn an outage into a signature error.
 *
 * [client] must be the NextAuth client (cookie jar + pinned headers, no signing): the server binds
 * the client token to the User-Agent of this very request.
 */
class SessionSigningKeys(
    private val client: OkHttpClient,
    baseUrl: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    sealed interface Credentials {
        data class Session(val signingKey: String, val clientToken: String) : Credentials
        data object Legacy : Credentials
    }

    private val url = (if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/").toHttpUrl().resolve("api/auth/signing-key")!!
    private var cached: Credentials? = null
    private var validUntilMillis = 0L

    @Synchronized
    fun current(): Credentials {
        cached?.let { if (nowMillis() < validUntilMillis) return it }
        return fetch()
    }

    /** Drop the cached credentials (the server rejected them, or the session ended). */
    @Synchronized
    fun invalidate() {
        cached = null
        validUntilMillis = 0L
    }

    private fun fetch(): Credentials {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            when {
                response.code == 404 -> return remember(Credentials.Legacy, DEFAULT_TTL_SECONDS)
                response.code == 401 -> return Credentials.Legacy
                !response.isSuccessful -> throw IOException("Request signing is unavailable (HTTP ${response.code})")
            }
            val json = runCatching { JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject }.getOrNull()
            val key = json?.str("key")
            val token = json?.str("clientToken")
            if (json == null || key == null || token == null) throw IOException("Malformed signing-key response")
            val ttl = json.get("ttlSeconds")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: DEFAULT_TTL_SECONDS
            return remember(Credentials.Session(key, token), ttl)
        }
    }

    private fun remember(credentials: Credentials, ttlSeconds: Long): Credentials {
        cached = credentials
        validUntilMillis = nowMillis() + (ttlSeconds - REFRESH_MARGIN_SECONDS).coerceAtLeast(0) * 1000
        return credentials
    }

    private fun JsonObject.str(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    companion object {
        const val REFRESH_MARGIN_SECONDS = 60L
        /** The server's own signature window (`SIGNING_KEY_TTL_SECONDS`), used when it sends none. */
        const val DEFAULT_TTL_SECONDS = 900L

        /** 403 codes a fresh key cures; the interceptor refreshes and retries exactly once. */
        val REFRESH_CODES: Set<String> = setOf("INVALID_SIGNATURE", "MISSING_SIGNATURE", "INVALID_CLIENT_TOKEN")
    }
}
