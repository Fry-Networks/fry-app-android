package com.frynetworks.fryapp.network.dashboard

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/**
 * Adds `x-client-token`, `x-request-signature` and `x-request-timestamp` to POSTs on the routes
 * the dashboard guards with `enforceWalletApiSecurity`. The signature is computed over the exact
 * body bytes that go on the wire (callers build them with [JsJson]), the bare encoded path and
 * the server-synchronised timestamp. Signed routes must never carry a query string: the server
 * signs `req.url` for some of them, so a query would make the two sides disagree.
 */
class SecurityHeaderInterceptor(
    private val signer: SecurityHeaderSigner,
    private val clock: ServerClock,
    private val userAgent: String = DashboardConfig.USER_AGENT,
    private val signedPaths: Set<String> = DashboardConfig.SIGNED_PATHS,
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
        val timestamp = clock.nowSeconds()
        val signed = request.newBuilder()
            .header(SecurityHeaderSigner.HEADER_CLIENT_TOKEN, signer.clientToken(userAgent))
            .header(SecurityHeaderSigner.HEADER_SIGNATURE, signer.signature(request.method, path, canonical, timestamp))
            .header(SecurityHeaderSigner.HEADER_TIMESTAMP, timestamp.toString())
            .build()
        return chain.proceed(signed).also { clock.observeDateHeader(it.header("Date")) }
    }
}
