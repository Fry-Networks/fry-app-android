package com.frynetworks.fryapp.data.dashboard.repo.impl

import com.frynetworks.fryapp.data.dashboard.api.AlgodApi
import com.frynetworks.fryapp.network.dashboard.DashboardException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** [AlgodRepositoryImpl] over a plain OkHttp client against a MockWebServer standing in for algod. */
class AlgodRepositoryImplTest {

    private val server = MockWebServer()
    private val recorded = mutableListOf<RecordedRequest>()
    private val responses = HashMap<String, () -> MockResponse>()
    private lateinit var repo: AlgodRepositoryImpl
    private val address = Fixtures.ADDRESS

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += request
                return responses[request.path]?.invoke() ?: MockResponse().setResponseCode(404).setBody("""{"message":"not found"}""")
            }
        }
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AlgodApi::class.java)
        repo = AlgodRepositoryImpl(api)
    }

    @After fun stop() = server.shutdown()

    @Test
    fun `algoBalanceMicro reads amount from accounts with exclude=all and no cookies or dashboard headers`() = runTest {
        responses["/v2/accounts/$address?exclude=all"] = { Fixtures.body("""{"address":"$address","amount":1234567,"min-balance":100000,"round":50}""") }
        assertEquals(1_234_567L, repo.algoBalanceMicro(address))
        val r = recorded.single()
        assertEquals("GET", r.method)
        assertNull(r.getHeader("Cookie"))
        assertNull(r.getHeader("x-client-token"))
        assertNull(r.getHeader("Authorization"))
    }

    @Test
    fun `an unfunded account (404) has a zero balance and no auth address`() = runTest {
        assertEquals(0L, repo.algoBalanceMicro(address))
        assertNull(repo.authAddress(address))
    }

    @Test
    fun `authAddress returns auth-addr for a rekeyed account and null otherwise`() = runTest {
        responses["/v2/accounts/$address?exclude=all"] = { Fixtures.body("""{"address":"$address","amount":1,"auth-addr":"AUTHADDR"}""") }
        assertEquals("AUTHADDR", repo.authAddress(address))
        responses["/v2/accounts/$address?exclude=all"] = { Fixtures.body("""{"address":"$address","amount":1}""") }
        assertNull(repo.authAddress(address))
    }

    @Test
    fun `assetBalanceMicro reads the holding, null when not opted in, and isOptedIn follows it`() = runTest {
        responses["/v2/accounts/$address/assets/2681521901"] = { Fixtures.body("""{"asset-holding":{"amount":5000000,"asset-id":2681521901,"is-frozen":false},"round":50}""") }
        assertEquals(5_000_000L, repo.assetBalanceMicro(address, 2681521901L))
        assertTrue(repo.isOptedIn(address, 2681521901L))
        assertNull(repo.assetBalanceMicro(address, 2485202024L))
        assertEquals(false, repo.isOptedIn(address, 2485202024L))
        assertEquals("/v2/accounts/$address/assets/2681521901", recorded[0].path)
    }

    @Test
    fun `waitConfirmed polls the pending pool and advances round by round until confirmed`() = runTest {
        var polls = 0
        responses["/v2/status"] = { Fixtures.body("""{"last-round":100}""") }
        responses["/v2/transactions/pending/TX1?format=json"] = {
            polls++
            if (polls < 3) Fixtures.body("""{"pool-error":"","confirmed-round":0,"txn":{}}""") else Fixtures.body("""{"pool-error":"","confirmed-round":103,"txn":{}}""")
        }
        responses["/v2/status/wait-for-block-after/101"] = { Fixtures.body("""{"last-round":102}""") }
        responses["/v2/status/wait-for-block-after/102"] = { Fixtures.body("""{"last-round":103}""") }

        repo.waitConfirmed("TX1", rounds = 4)

        assertEquals(
            listOf(
                "/v2/status",
                "/v2/transactions/pending/TX1?format=json",
                "/v2/status/wait-for-block-after/101",
                "/v2/transactions/pending/TX1?format=json",
                "/v2/status/wait-for-block-after/102",
                "/v2/transactions/pending/TX1?format=json",
            ),
            recorded.map { it.path },
        )
    }

    @Test
    fun `waitConfirmed gives up after the round budget with TXN_NOT_CONFIRMED`() = runTest {
        responses["/v2/status"] = { Fixtures.body("""{"last-round":100}""") }
        responses["/v2/transactions/pending/TX2?format=json"] = { Fixtures.body("""{"pool-error":"","confirmed-round":0}""") }
        for (r in 101..103) responses["/v2/status/wait-for-block-after/$r"] = { Fixtures.body("""{"last-round":${r + 1}}""") }
        val e = runCatching { repo.waitConfirmed("TX2", rounds = 2) }.exceptionOrNull() as DashboardException
        assertEquals("TXN_NOT_CONFIRMED", e.code)
        assertEquals(2, recorded.count { it.path.orEmpty().startsWith("/v2/transactions/pending/") })
    }

    @Test
    fun `waitConfirmed surfaces a pool rejection as TXN_REJECTED immediately`() = runTest {
        responses["/v2/status"] = { Fixtures.body("""{"last-round":100}""") }
        responses["/v2/transactions/pending/TX3?format=json"] = { Fixtures.body("""{"pool-error":"transaction already in ledger","confirmed-round":0}""") }
        val e = runCatching { repo.waitConfirmed("TX3") }.exceptionOrNull() as DashboardException
        assertEquals("TXN_REJECTED", e.code)
        assertEquals("transaction already in ledger", e.message)
        assertEquals(2, recorded.size)
    }

    @Test
    fun `algod failures map to ALGOD_ERROR with the node message and I-O failures to NETWORK_ERROR`() = runTest {
        responses["/v2/accounts/$address?exclude=all"] = { Fixtures.body("""{"message":"failed to parse the address"}""", 400) }
        val e = runCatching { repo.algoBalanceMicro(address) }.exceptionOrNull() as DashboardException
        assertEquals("ALGOD_ERROR", e.code)
        assertEquals("failed to parse the address", e.message)
        assertEquals(400, e.httpStatus)

        // Dispatcher-served responses only honour socket policies applied after the request is read.
        responses["/v2/accounts/$address?exclude=all"] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        assertEquals("NETWORK_ERROR", (runCatching { repo.algoBalanceMicro(address) }.exceptionOrNull() as DashboardException).code)
    }
}
