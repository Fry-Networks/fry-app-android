package com.frynetworks.fryapp.data.keys

import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class KeyCheckClientTest {

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
    fun `a C-3 answer is read in full and the request carries address and key`() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":true,"input":{"kind":"fem","normalized":"$key"},"verdict":"ok_active_elsewhere","owner":"you","message":"This key is yours and runs on your Windows PC.","binding_rule":{"id":"one-active-install-per-key","text":"One key runs on one install at a time."},"installs":[{"platform":"windows","last_seen_age_s":120,"holds_lease":true}],"consequence":"stops_other","device_compatible":{"esp_firmware":true}}"""))
        val r = client().check(address, key) as KeyCheckResult.Checked

        assertEquals("ok_active_elsewhere", r.verdict)
        assertEquals("you", r.owner)
        assertEquals("This key is yours and runs on your Windows PC.", r.message)
        assertEquals("One key runs on one install at a time.", r.bindingRule)
        assertEquals(listOf(KeyInstall("windows", 120, true)), r.installs)
        assertEquals("stops_other", r.consequence)
        assertTrue(r.needsAcknowledgement)
        assertFalse(r.blocksSetup)

        val req = server.takeRequest()
        assertEquals("/api/keys/check", req.path)
        val body = JsonParser.parseString(req.body.readUtf8()).asJsonObject
        assertEquals(address, body["address"].asString)
        assertEquals(key, body["miner_key"].asString)
    }

    @Test
    fun `someone else's key and a non-ESP key block setup`() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":true,"verdict":"registered_other","owner":"another_wallet","message":"Registered to another wallet."}"""))
        assertTrue((client().check(address, key) as KeyCheckResult.Checked).blocksSetup)
        server.enqueue(MockResponse().setBody("""{"success":true,"verdict":"ok","owner":"you","device_compatible":{"esp_firmware":false}}"""))
        assertTrue((client().check(address, key) as KeyCheckResult.Checked).blocksSetup)
        server.enqueue(MockResponse().setBody("""{"success":true,"verdict":"ok","owner":"you"}"""))
        assertFalse((client().check(address, key) as KeyCheckResult.Checked).blocksSetup)
    }

    @Test
    fun `an older dashboard (404 without a code) falls back to the device lookup`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("<html>404: This page could not be found</html>"))
        server.enqueue(MockResponse().setBody("""{"success":true,"device":{"miner_key":"$key"}}"""))
        val found = client().check(address, key)
        assertEquals(true, (found as KeyCheckResult.Unverifiable).existsOnDashboard)
        assertTrue(found.message.startsWith("Unverifiable"))
        server.takeRequest()
        assertEquals("/api/devices/$key", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(404).setBody(""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"code":"DEVICE_NOT_FOUND"}"""))
        assertEquals(false, (client().check(address, key) as KeyCheckResult.Unverifiable).existsOnDashboard)
    }

    @Test
    fun `a 404 with a code is an answer, not a missing route`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"code":"NOT_FOUND","message":"No such key."}"""))
        assertEquals(KeyCheckResult.Failed("No such key."), client().check(address, key))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `rate limits, sign-out and network failures are reported, never treated as ok`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "42").setBody("""{"success":false,"code":"RATE_LIMITED"}"""))
        assertEquals(42L, (client().check(address, key) as KeyCheckResult.Failed).retryAfterSeconds)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"success":false,"code":"SESSION_REQUIRED"}"""))
        assertTrue((client().check(address, key) as KeyCheckResult.Failed).message.contains("Sign in"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(client().check(address, key) is KeyCheckResult.Failed)
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        assertTrue(client().check(address, key) is KeyCheckResult.Failed)
    }
}
