package com.frynetworks.fryapp.network.dashboard

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Mirrors the dashboard's own `fetchWithFingerprintRetry`: refresh the fingerprint binding
 *  once and retry once; a second DEVICE_MISMATCH means the session is unusable. */
class FingerprintRetryInterceptorTest {

    private val server = MockWebServer()
    private var rebinds = 0
    private val events = mutableListOf<SessionEvent>()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun client(rebindResult: Boolean = true): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(FingerprintRetryInterceptor(rebind = { rebinds++; rebindResult }, onSessionEvent = { events += it }))
        .build()

    private fun post(c: OkHttpClient) = c.newCall(
        Request.Builder().url(server.url("/api/rewards/get-asset-totals")).post("{}".toRequestBody("application/json".toMediaType())).build(),
    ).execute()

    @Test
    fun `409 DEVICE_FINGERPRINT_REFRESH rebinds once and retries once`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"success":false,"code":"DEVICE_FINGERPRINT_REFRESH","message":"retry"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        val resp = post(client())
        assertEquals(200, resp.code)
        resp.close()
        assertEquals(1, rebinds)
        assertEquals(2, server.requestCount)
        assertEquals(emptyList<SessionEvent>(), events)
    }

    @Test
    fun `403 DEVICE_MISMATCH twice gives up after one retry and reports the mismatch`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"success":false,"code":"DEVICE_MISMATCH"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"success":false,"code":"DEVICE_MISMATCH"}"""))
        val resp = post(client())
        assertEquals(403, resp.code)
        assertEquals("""{"success":false,"code":"DEVICE_MISMATCH"}""", resp.body!!.string())
        assertEquals(1, rebinds)
        assertEquals(2, server.requestCount)
        assertEquals(listOf<SessionEvent>(SessionEvent.DeviceMismatch), events)
    }

    @Test
    fun `an unrelated 403 is returned untouched without a rebind`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"success":false,"code":"INVALID_SIGNATURE"}"""))
        val resp = post(client())
        assertEquals(403, resp.code)
        resp.close()
        assertEquals(0, rebinds)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a failed rebind does not retry`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"success":false,"code":"DEVICE_FINGERPRINT_REFRESH"}"""))
        val resp = post(client(rebindResult = false))
        assertEquals(409, resp.code)
        resp.close()
        assertEquals(1, rebinds)
        assertEquals(1, server.requestCount)
    }
}
