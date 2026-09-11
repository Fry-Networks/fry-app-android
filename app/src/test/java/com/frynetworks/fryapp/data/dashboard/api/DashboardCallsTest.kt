package com.frynetworks.fryapp.data.dashboard.api

import app.cash.turbine.test
import com.frynetworks.fryapp.data.dashboard.model.ApiErrorBody
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.network.dashboard.SessionEvent
import com.frynetworks.fryapp.network.dashboard.SessionEventBus
import com.frynetworks.fryapp.network.dashboard.jsonBody
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/** The error-mapping contract every repository relies on, exercised without a server. */
class DashboardCallsTest {

    private val bus = SessionEventBus()
    private val calls = DashboardCalls(bus)

    private fun error(code: Int, body: String = "", retryAfter: String? = null): Response<okhttp3.ResponseBody> {
        val raw = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://dashboard.frynetworks.com/api/x").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code)
            .message("err")
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .build()
        return Response.error(body.toResponseBody("application/json".toMediaType()), raw)
    }

    private fun success(body: String): Response<okhttp3.ResponseBody> = Response.success(body.toResponseBody("application/json".toMediaType()))

    @Test
    fun `toJsonRequestBody writes the canonical bytes with a bare application-json media type`() {
        val body = jsonBody { "miner_key" to "FEM-ABC"; "no" to 3; "preview" to true }.toJsonRequestBody()
        assertEquals("application/json", body.contentType().toString())
        assertEquals("""{"miner_key":"FEM-ABC","no":3,"preview":true}""", Buffer().also { body.writeTo(it) }.readUtf8())
    }

    @Test
    fun `a 2xx body is returned parsed and an empty body is JsonNull`() = runTest {
        assertEquals("ok", calls.execute { success("""{"message":"ok"}""") }.asJsonObject["message"].asString)
        assertTrue(calls.execute { success("") }.isJsonNull)
        val e = runCatching { calls.parse(calls.execute { success("") }, ApiErrorBody::class.java) }.exceptionOrNull() as DashboardException
        assertEquals("BAD_RESPONSE", e.code)
    }

    @Test
    fun `an envelope maps to code, message, action and status`() = runTest {
        val e = runCatching { calls.execute { error(409, """{"success":false,"code":"ALREADY_TRANSITIONED","message":"Refresh","action":"Reload"}""") } }.exceptionOrNull() as DashboardException
        assertEquals("ALREADY_TRANSITIONED", e.code)
        assertEquals("Refresh", e.message)
        assertEquals("Reload", e.action)
        assertEquals(409, e.httpStatus)
    }

    @Test
    fun `a non-JSON error body falls back to HTTP_n and a generic message`() = runTest {
        val e = runCatching { calls.execute { error(502, "<html>Bad Gateway</html>") } }.exceptionOrNull() as DashboardException
        assertEquals("HTTP_502", e.code)
        assertEquals("HTTP 502", e.message)
        assertNull(e.action)
    }

    @Test
    fun `401 expires the session except for WALLET_MISMATCH, and can be opted out per call`() = runTest {
        bus.events.test {
            runCatching { calls.execute { error(401, """{"success":false,"code":"SESSION_REQUIRED","message":"expired"}""") } }
            assertEquals(SessionEvent.Expired, awaitItem())

            runCatching { calls.execute { error(401, "") } }
            assertEquals(SessionEvent.Expired, awaitItem())

            runCatching { calls.execute { error(401, """{"success":false,"code":"WALLET_MISMATCH","message":"mismatch"}""") } }
            expectNoEvents()

            val e = runCatching { calls.execute(expireSessionOn401 = false) { error(401, """{"success":false,"code":"UNAUTHORIZED","message":"no stake"}""") } }.exceptionOrNull() as DashboardException
            expectNoEvents()
            calls.emitExpiredIfSessionError(e)
            expectNoEvents()
            calls.emitExpiredIfSessionError(DashboardException("SESSION_REQUIRED", "x", httpStatus = 401))
            assertEquals(SessionEvent.Expired, awaitItem())
        }
    }

    @Test
    fun `429 uses the Retry-After seconds as the action and defaults the code`() = runTest {
        val withHeader = runCatching { calls.execute { error(429, "", "12") } }.exceptionOrNull() as DashboardException
        assertEquals("RATE_LIMIT_EXCEEDED", withHeader.code)
        assertEquals("12", withHeader.action)

        val dateHeader = runCatching { calls.execute { error(429, """{"code":"RATE_LIMIT_EXCEEDED","message":"m","action":"wait"}""", "Wed, 21 Oct 2026 07:28:00 GMT") } }.exceptionOrNull() as DashboardException
        assertEquals("wait", dateHeader.action)
    }

    @Test
    fun `IOException becomes NETWORK_ERROR with status 0`() = runTest {
        val e = runCatching { calls.execute { throw IOException("Unable to resolve host") } }.exceptionOrNull() as DashboardException
        assertEquals("NETWORK_ERROR", e.code)
        assertEquals("Unable to resolve host", e.message)
        assertEquals(0, e.httpStatus)
    }
}
