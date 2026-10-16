package com.frynetworks.fryapp.data.dashboard.repo.impl

import com.frynetworks.fryapp.data.dashboard.api.AlgodApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** `poolHasNoRecord` is true only for a definitive 404 from the pending pool; the existing single-argument `txStatus` is untouched. */
class AlgodPoolHasNoRecordTest {

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
    fun `a 404 from the pool is a definitive no record`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Transaction not found"}"""))
        assertTrue(repo.poolHasNoRecord("TX"))
    }

    @Test
    fun `a pooled or confirmed transaction is not no record`() = runTest {
        server.enqueue(MockResponse().setBody("""{"pool-error":""}"""))
        assertFalse(repo.poolHasNoRecord("TX"))
        server.enqueue(MockResponse().setBody("""{"confirmed-round":12,"pool-error":""}"""))
        assertFalse(repo.poolHasNoRecord("TX"))
    }

    @Test
    fun `a node error or a dropped connection is not no record`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))
        assertFalse(repo.poolHasNoRecord("TX"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertFalse(repo.poolHasNoRecord("TX"))
    }
}
