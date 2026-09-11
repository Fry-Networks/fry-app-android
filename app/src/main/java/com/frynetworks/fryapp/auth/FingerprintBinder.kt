package com.frynetworks.fryapp.auth

import com.frynetworks.fryapp.network.dashboard.DashboardConfig
import com.frynetworks.fryapp.network.dashboard.JsJson
import com.frynetworks.fryapp.network.dashboard.jsonBody
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Binds the dashboard's device fingerprint to our session, the way `pages/_app.tsx` does after
 * sign-in: `POST /api/auth/capture-fingerprint` (the server hashes THIS request's headers), then
 * NextAuth `update()` so the JWT carries the fingerprint the guarded routes compare against.
 */
class FingerprintBinder(
    private val api: NextAuthApi,
    private val userAgent: String = DashboardConfig.USER_AGENT,
) {
    private val mutex = Mutex()

    @Volatile
    var lastFingerprint: String? = null
        private set

    /** Returns true when the JWT now carries the fingerprint the server computed for us. */
    suspend fun rebind(): Boolean = mutex.withLock {
        val captured = runCatching { api.captureFingerprint("{}".toRequestBody(JSON)) }.getOrNull() ?: return false
        val fingerprint = captured.fingerprint?.takeIf { it.isNotBlank() } ?: return false
        val csrf = runCatching { api.csrf().csrfToken }.getOrNull() ?: return false
        val body = JsJson.stringify(
            jsonBody {
                "csrfToken" to csrf
                "data" to jsonBody {
                    "deviceFingerprint" to fingerprint
                    "userAgent" to (captured.userAgent ?: userAgent)
                }
            },
        )
        val session = runCatching { api.updateSession(body.toRequestBody(JSON)) }.getOrNull() ?: return false
        val bound = session.get("deviceFingerprint")?.takeIf { !it.isJsonNull }?.asString == fingerprint
        if (bound) lastFingerprint = fingerprint
        bound
    }

    /** Blocking adapter for the OkHttp retry interceptor (runs on an OkHttp thread). */
    fun rebindBlocking(): Boolean = runBlocking { rebind() }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
