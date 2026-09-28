package com.frynetworks.fryapp.network.dashboard

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * A dashboard after R11/R12: `GET /api/auth/signing-key` hands out a per-session key + client
 * token, and the guarded route accepts only a signature made with the key currently issued.
 */
class SessionSigningKeysTest {

    private val server = MockWebServer()
    private val ua = DashboardConfig.USER_AGENT
    private val legacy = SecurityHeaderSigner(signatureSecret = "fry-rewards-signature-v1-")
    private val clock = ServerClock()
    private var now = 1_000_000L
    private var keyRoute = 200
    private var issued = 0
    private var liveKey: String? = null
    private var ttlSeconds = 900
    private val routeHits = mutableListOf<RecordedRequest>()
    private var rejectCode = "INVALID_SIGNATURE"

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/auth/signing-key" -> when (keyRoute) {
                    200 -> {
                        issued++
                        liveKey = "sessionkey$issued"
                        MockResponse().setBody("""{"key":"$liveKey","clientToken":"token$issued","expiresAt":"2026-10-28T00:00:00.000Z","ttlSeconds":$ttlSeconds}""")
                    }
                    else -> MockResponse().setResponseCode(keyRoute).setBody("""{"success":false}""")
                }
                "/api/rewards/claim" -> {
                    routeHits += request
                    val key = liveKey
                    val ts = request.getHeader("x-request-timestamp")!!.toLong()
                    val body = request.body.clone().readUtf8()
                    val ok = key != null &&
                        request.getHeader("x-client-token") == "token$issued" &&
                        request.getHeader("x-request-signature") == SecurityHeaderSigner(key).signature("POST", "/api/rewards/claim", body, ts)
                    if (ok) MockResponse().setBody("""{"success":true}""")
                    else MockResponse().setResponseCode(403).setBody("""{"success":false,"code":"$rejectCode","message":"Invalid or expired request signature","serverTime":1790000000123}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun keys() = SessionSigningKeys(OkHttpClient.Builder().addInterceptor(HeaderPinInterceptor()).build(), server.url("/").toString(), nowMillis = { now })

    private fun client(keys: SessionSigningKeys?) = OkHttpClient.Builder()
        .addInterceptor(HeaderPinInterceptor())
        .addInterceptor(SecurityHeaderInterceptor(legacy, clock, sessionKeys = keys))
        .build()

    private fun claim(c: OkHttpClient): Int =
        c.newCall(Request.Builder().url(server.url("/api/rewards/claim")).post("""{"miner_key":"FEM-TESTKEY0000000000000000000000001"}""".toRequestBody("application/json".toMediaType())).build())
            .execute().use { it.code }

    @Test
    fun `credentials are fetched once and reused until ttl minus 60 s`() {
        val k = keys()
        assertEquals(SessionSigningKeys.Credentials.Session("sessionkey1", "token1"), k.current())
        now += (900 - 61) * 1000L
        assertEquals("sessionkey1", (k.current() as SessionSigningKeys.Credentials.Session).signingKey)
        now += 2_000L
        assertEquals("sessionkey2", (k.current() as SessionSigningKeys.Credentials.Session).signingKey)
        assertEquals(2, issued)
    }

    @Test
    fun `invalidate forces a refetch`() {
        val k = keys()
        k.current()
        k.invalidate()
        k.current()
        assertEquals(2, issued)
    }

    @Test
    fun `the signing-key request carries the pinned User-Agent the server binds the token to`() {
        keys().current()
        assertEquals(ua, server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun `a dashboard without the route (404) means legacy signing`() {
        keyRoute = 404
        assertEquals(SessionSigningKeys.Credentials.Legacy, keys().current())
    }

    @Test
    fun `no session (401) signs legacy and is not cached`() {
        keyRoute = 401
        val k = keys()
        assertEquals(SessionSigningKeys.Credentials.Legacy, k.current())
        keyRoute = 200
        assertEquals("sessionkey1", (k.current() as SessionSigningKeys.Credentials.Session).signingKey)
    }

    @Test
    fun `a failing signing-key route fails closed and the guarded route is never called`() {
        for (code in listOf(500, 503)) {
            keyRoute = code
            try {
                claim(client(keys()))
                fail("expected IOException for HTTP $code")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("$code"))
            }
        }
        assertTrue(routeHits.isEmpty())
    }

    @Test
    fun `signed routes pass with the per-session key and client token`() {
        assertEquals(200, claim(client(keys())))
        assertEquals(1, routeHits.size)
    }

    @Test
    fun `the build-time signer is rejected by a current dashboard`() {
        // control: the pre-R11 client (no session keys) is what production rejects
        assertEquals(403, claim(client(null)))
    }

    @Test
    fun `a rejected key is refreshed and the request re-signed exactly once`() {
        val k = keys()
        k.current() // cache sessionkey1
        liveKey = "rotated" // the server now expects a key the app does not hold yet; next fetch issues sessionkey2
        assertEquals(200, claim(client(k)))
        assertEquals(2, routeHits.size)
        assertEquals(2, issued)
        assertTrue("serverTime from the 403 body is adopted", kotlin.math.abs(clock.nowMillis() - 1_790_000_000_123L) < 5_000)
    }

    @Test
    fun `a second rejection is returned, not retried again`() {
        keyRoute = 200
        val k = keys()
        val c = OkHttpClient.Builder()
            .addInterceptor(HeaderPinInterceptor())
            .addInterceptor(SecurityHeaderInterceptor(legacy, clock, sessionKeys = k))
            .addInterceptor { chain -> liveKey = "never"; chain.proceed(chain.request()) } // server keeps rotating
            .build()
        assertEquals(403, claim(c))
        assertEquals(2, routeHits.size)
    }

    @Test
    fun `MISSING_SIGNATURE and INVALID_CLIENT_TOKEN also refresh, other 403s do not`() {
        for (code in listOf("MISSING_SIGNATURE", "INVALID_CLIENT_TOKEN")) {
            routeHits.clear()
            rejectCode = code
            val k = keys().also { it.current() }
            liveKey = "rotated"
            assertEquals(code, 200, claim(client(k)))
            assertEquals(code, 2, routeHits.size)
        }
        routeHits.clear()
        rejectCode = "WALLET_MISMATCH"
        val k = keys().also { it.current() }
        liveKey = "rotated"
        assertEquals(403, claim(client(k)))
        assertEquals(1, routeHits.size)
    }
}
