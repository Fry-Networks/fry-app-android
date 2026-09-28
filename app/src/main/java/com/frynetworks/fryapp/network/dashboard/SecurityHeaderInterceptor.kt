package com.frynetworks.fryapp.network.dashboard

import com.google.gson.JsonParser
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/**
 * Adds `x-client-token`, `x-request-signature` and `x-request-timestamp` to POSTs on the routes
 * the dashboard guards with `enforceWalletApiSecurity`. The signature is computed over the exact
 * body bytes that go on the wire (callers build them with [JsJson]), the bare encoded path and
 * the server-synchronised timestamp. Signed routes must never carry a query string: the server
 * signs `req.url` for some of them, so a query would make the two sides disagree.
 *
 * With [sessionKeys] the token and signing key are the per-session ones the dashboard issues
 * (R11/R12); a 403 in [SessionSigningKeys.REFRESH_CODES] refetches them and re-signs exactly once,
 * applying the `serverTime` the 403 body carries. Without it every request uses [signer].
 */
class SecurityHeaderInterceptor(
    private val signer: SecurityHeaderSigner,
    private val clock: ServerClock,
    private val userAgent: String = DashboardConfig.USER_AGENT,
    private val signedPaths: Set<String> = DashboardConfig.SIGNED_PATHS,
    private val sessionKeys: SessionSigningKeys? = null,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        if (request.method != "POST" || path !in signedPaths) {
            return chain.proceed(request).also { clock.observeDateHeader(it.header("Date")) }
        }
        require(request.url.query == null) { "Signed dashboard route $path must not carry a query string" }
        val body = request.body ?: throw IllegalArgumentException("Signed dashboard route $path needs a body")
        val canonical = Buffer().also { body.writeTo(it) }.readUtf8()
        val first = chain.proceed(sign(request, path, canonical)).also { clock.observeDateHeader(it.header("Date")) }
        val keys = sessionKeys ?: return first
        if (!rejectedKey(first)) return first
        keys.invalidate()
        first.close()
        return chain.proceed(sign(request, path, canonical)).also { clock.observeDateHeader(it.header("Date")) }
    }

    private fun sign(request: Request, path: String, canonical: String): Request {
        val timestamp = clock.nowSeconds()
        val credentials = sessionKeys?.current() ?: SessionSigningKeys.Credentials.Legacy
        val (token, signature) = when (credentials) {
            is SessionSigningKeys.Credentials.Session ->
                credentials.clientToken to SecurityHeaderSigner(credentials.signingKey).signature(request.method, path, canonical, timestamp)
            SessionSigningKeys.Credentials.Legacy ->
                signer.clientToken(userAgent) to signer.signature(request.method, path, canonical, timestamp)
        }
        return request.newBuilder()
            .header(SecurityHeaderSigner.HEADER_CLIENT_TOKEN, token)
            .header(SecurityHeaderSigner.HEADER_SIGNATURE, signature)
            .header(SecurityHeaderSigner.HEADER_TIMESTAMP, timestamp.toString())
            .build()
    }

    /** True for a 403 a fresh session key cures; also adopts the body's `serverTime` (ms). */
    private fun rejectedKey(response: Response): Boolean {
        if (response.code != 403) return false
        val body = runCatching { JsonParser.parseString(response.peekBody(PEEK_BYTES).string()).asJsonObject }.getOrNull() ?: return false
        val code = body.get("code")?.takeIf { it.isJsonPrimitive }?.asString ?: return false
        if (code !in SessionSigningKeys.REFRESH_CODES) return false
        body.get("serverTime")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
            ?.takeIf { it > 0 }?.let { clock.observeServerMillis(it) }
        return true
    }

    private companion object {
        const val PEEK_BYTES = 4096L
    }
}
