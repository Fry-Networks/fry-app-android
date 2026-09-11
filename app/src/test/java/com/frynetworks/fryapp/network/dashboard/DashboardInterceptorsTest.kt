package com.frynetworks.fryapp.network.dashboard

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The dashboard fingerprints requests by `UA|Accept-Language|Accept-Encoding|Accept|sec-ch-ua|
 * sec-ch-ua-mobile`, so every request must carry a byte-identical header set, and signed POSTs must
 * carry the three `x-request-*` headers computed over the exact body bytes.
 */
class DashboardInterceptorsTest {

    private val server = MockWebServer()
    private val ua = "FryApp/0.3.0 (Android; wallet-bridge)"
    private val signer = SecurityHeaderSigner(signatureSecret = "fry-rewards-signature-v1-")
    private val clock = ServerClock()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HeaderPinInterceptor(userAgent = ua))
        .addInterceptor(SecurityHeaderInterceptor(signer, clock, ua, DashboardConfig.SIGNED_PATHS))
        .build()

    @Test
    fun `every request carries the pinned header set and nothing that would change the fingerprint`() {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        val c = client()
        c.newCall(Request.Builder().url(server.url("/api/devices/list")).post("{}".toRequestBody("application/json".toMediaType())).build()).execute().close()
        c.newCall(Request.Builder().url(server.url("/api/my-keys")).header("sec-ch-ua", "leak").header("User-Agent", "other").build()).execute().close()

        repeat(2) {
            val r = server.takeRequest()
            assertEquals(ua, r.getHeader("User-Agent"))
            assertEquals(DashboardConfig.ACCEPT, r.getHeader("Accept"))
            assertEquals(DashboardConfig.ACCEPT_LANGUAGE, r.getHeader("Accept-Language"))
            assertEquals("gzip", r.getHeader("Accept-Encoding"))
            assertNull(r.getHeader("sec-ch-ua"))
            assertNull(r.getHeader("sec-ch-ua-mobile"))
            val names = r.headers.names().map { it.lowercase() }.filter { it !in setOf("host", "connection", "content-type", "content-length", "user-agent", "accept", "accept-language", "accept-encoding", "x-client-token", "x-request-signature", "x-request-timestamp") }
            assertTrue("unexpected headers $names", names.isEmpty())
        }
    }

    @Test
    fun `signed POST paths carry client token, signature and timestamp computed over the body bytes`() {
        server.enqueue(MockResponse().setBody("{}"))
        val body = jsonBody { "miner_key" to "FEM-ABC"; "preview" to true }.canonical()
        client().newCall(Request.Builder().url(server.url("/api/rewards/claim")).post(body.toRequestBody("application/json".toMediaType())).build()).execute().close()

        val r = server.takeRequest()
        val sent = r.body.readUtf8()
        assertEquals(body, sent)
        assertEquals(signer.clientToken(ua), r.getHeader("x-client-token"))
        val ts = r.getHeader("x-request-timestamp")!!.toLong()
        assertTrue(kotlin.math.abs(ts - System.currentTimeMillis() / 1000) < 5)
        assertEquals(signer.signature("POST", "/api/rewards/claim", sent, ts), r.getHeader("x-request-signature"))
    }

    @Test
    fun `unsigned paths and GETs never carry the security headers`() {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        val c = client()
        c.newCall(Request.Builder().url(server.url("/api/devices/list")).post("{}".toRequestBody("application/json".toMediaType())).build()).execute().close()
        c.newCall(Request.Builder().url(server.url("/api/my-keys")).build()).execute().close()
        repeat(2) {
            val r = server.takeRequest()
            assertNull(r.getHeader("x-client-token"))
            assertNull(r.getHeader("x-request-signature"))
            assertNull(r.getHeader("x-request-timestamp"))
        }
    }

    @Test
    fun `a signed request with a query string is refused because the server signs the bare path`() {
        server.enqueue(MockResponse().setBody("{}"))
        val call = client().newCall(Request.Builder().url(server.url("/api/rewards/claim?x=1")).post("{}".toRequestBody("application/json".toMediaType())).build())
        val failed = runCatching { call.execute().close() }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $failed", failed is IllegalArgumentException)
    }

    @Test
    fun `server clock offset follows the Date header`() {
        val clock = ServerClock()
        val nowSeconds = System.currentTimeMillis() / 1000
        clock.observeServerSeconds(nowSeconds + 120)
        assertTrue(kotlin.math.abs(clock.nowSeconds() - (nowSeconds + 120)) <= 1)
    }
}
