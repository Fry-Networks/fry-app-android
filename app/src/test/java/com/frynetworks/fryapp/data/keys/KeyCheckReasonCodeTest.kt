package com.frynetworks.fryapp.data.keys

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/** IV6 A4: the key check keeps a `reason_code` the dashboard sends (additive field). */
class KeyCheckReasonCodeTest {

    private val server = MockWebServer()
    private val address = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"
    private val key = "FEM-TESTKEY0000000000000000000000001"

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun client() = KeyCheckClient(
        Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient.Builder().retryOnConnectionFailure(false).build()).build()
            .create(KeyCheckApi::class.java),
    )

    @Test
    fun `reason_code is read when present and null when absent`() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":true,"verdict":"ok_active_elsewhere","owner":"you","reason_code":"lease_held_elsewhere"}"""))
        assertEquals("lease_held_elsewhere", (client().check(address, key) as KeyCheckResult.Checked).reasonCode)
        server.enqueue(MockResponse().setBody("""{"success":true,"verdict":"ok","owner":"you"}"""))
        assertNull((client().check(address, key) as KeyCheckResult.Checked).reasonCode)
    }
}
