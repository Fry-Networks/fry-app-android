package com.frynetworks.fryapp.data.dashboard.repo.impl

import com.frynetworks.fryapp.data.dashboard.api.AlgodApi
import com.frynetworks.fryapp.data.dashboard.repo.TxStatus
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
import retrofit2.converter.gson.GsonConverterFactory

/** The pool-only transaction lookup: it reports what the pending endpoint says and never claims a transaction is absent. */
class AlgodRepositoryTxStatusTest {

    private val server = MockWebServer()
    private lateinit var repo: AlgodRepositoryImpl

    @Before
    fun start() {
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
    fun `a confirmed round means Confirmed`() = runTest {
        server.enqueue(MockResponse().setBody("""{"confirmed-round":1234,"pool-error":""}"""))
        assertEquals(TxStatus.Confirmed(1234), repo.txStatus("TX"))
        assertEquals("/v2/transactions/pending/TX?format=json", server.takeRequest().path)
    }

    @Test
    fun `a pooled transaction without a confirmed round means InPool`() = runTest {
        server.enqueue(MockResponse().setBody("""{"pool-error":""}"""))
        assertEquals(TxStatus.InPool, repo.txStatus("TX"))
    }

    @Test
    fun `a pool error means Rejected`() = runTest {
        server.enqueue(MockResponse().setBody("""{"pool-error":"overspend"}"""))
        assertEquals(TxStatus.Rejected("overspend"), repo.txStatus("TX"))
    }

    @Test
    fun `an unknown transaction or a node error is Unknown, never Absent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"not found"}"""))
        assertEquals(TxStatus.Unknown, repo.txStatus("TX"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))
        assertEquals(TxStatus.Unknown, repo.txStatus("TX"))
    }

    @Test
    fun `the current round is the node's last round, or null when it cannot be read`() = runTest {
        server.enqueue(MockResponse().setBody("""{"last-round":4321}"""))
        assertEquals(4321L, repo.currentRound())
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))
        assertNull(repo.currentRound())
    }
}
