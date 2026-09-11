package com.frynetworks.fryapp.network.dashboard

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Forces the exact header set the dashboard fingerprints. `Accept-Encoding` is deliberately NOT
 * set here: OkHttp's BridgeInterceptor adds exactly `gzip` when absent, and that single value is
 * what gets hashed. Any `sec-ch-ua*` client hints are stripped so they hash as empty strings.
 */
class HeaderPinInterceptor(
    private val userAgent: String = DashboardConfig.USER_AGENT,
    private val accept: String = DashboardConfig.ACCEPT,
    private val acceptLanguage: String = DashboardConfig.ACCEPT_LANGUAGE,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
            .header("User-Agent", userAgent)
            .header("Accept", accept)
            .header("Accept-Language", acceptLanguage)
            .removeHeader("Accept-Encoding")
        for (name in chain.request().headers.names()) {
            if (name.lowercase().startsWith("sec-ch-ua")) builder.removeHeader(name)
        }
        return chain.proceed(builder.build())
    }
}
