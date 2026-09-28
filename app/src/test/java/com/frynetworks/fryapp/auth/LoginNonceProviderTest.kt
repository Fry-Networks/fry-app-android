package com.frynetworks.fryapp.auth

import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class LoginNonceProviderTest {

    private val server = MockWebServer()
    private val address = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun provider(): LoginNonceProvider {
        val api = NextAuthApi.create(OkHttpClient.Builder().retryOnConnectionFailure(false).build(), server.url("/").toString())
        return LoginNonceProvider(api as LoginNonceApi, legacy = NonceGenerator { "777" })
    }

    private suspend fun assertUnavailable(block: suspend () -> Unit) {
        try {
            block()
            fail("expected LoginNonceUnavailableException")
        } catch (e: LoginNonceUnavailableException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `the nonce the dashboard issues is used as-is`() = runTest {
        server.enqueue(MockResponse().setBody("""{"nonce":"Zb3_q-XyN0nce","expiresAt":"2026-09-28T20:00:00.000Z"}"""))
        assertEquals("Zb3_q-XyN0nce", provider().nonceFor(address))
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/auth/nonce", r.path)
        assertEquals(address, JsonParser.parseString(r.body.readUtf8()).asJsonObject["address"].asString)
    }

    @Test
    fun `a dashboard without the nonce route (404) gets a local nonce`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("<html>404</html>"))
        assertEquals("777", provider().nonceFor(address))
    }

    @Test
    fun `server errors and rate limits fail instead of falling back`() = runTest {
        for (code in listOf(500, 502, 503, 429, 400)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"success":false}"""))
            assertUnavailable { provider().nonceFor(address) }
        }
    }

    @Test
    fun `a network failure fails instead of falling back`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertUnavailable { provider().nonceFor(address) }
    }

    @Test
    fun `a 200 without a usable nonce fails`() = runTest {
        for (body in listOf("""{"expiresAt":"x"}""", """{"nonce":""}""", """{"nonce":null}""", "not json")) {
            server.enqueue(MockResponse().setBody(body))
            assertUnavailable { provider().nonceFor(address) }
        }
    }
}
