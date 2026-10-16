package com.frynetworks.fryapp.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.Collections
import javax.net.SocketFactory

/** Backing out of the provisioning screen cancels the collector: the HTTP call in flight must be cancelled with it, not wait out its timeouts. */
@SuppressLint("NewApi")
class WifiProvisionerCancellationTest {

    private val server = MockWebServer()
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
    private val silent = setOf<String>().toMutableSet()

    /** Every socket is sent to the mock server whatever address OkHttp asks for, and remembered. */
    private val redirecting = object : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) =
                super.connect(InetSocketAddress(server.hostName, server.port), timeout)
        }.also { sockets += it }
        override fun createSocket(host: String?, port: Int): Socket = createSocket()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket()
        override fun createSocket(host: InetAddress?, port: Int): Socket = createSocket()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket()
    }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path in silent) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                return when (path) {
                    "/info" -> MockResponse().setBody("""{"deviceName":"fry","fw":"1","chip":"esp8266"}""")
                    "/provision" -> MockResponse().setBody("{}")
                    "/status" -> MockResponse().setBody("""{"status":3,"err":0,"minerKey":"FEM-AB","ip":"10.0.0.5"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        mockkConstructor(WifiNetworkSpecifier.Builder::class, NetworkRequest.Builder::class)
        every { anyConstructed<WifiNetworkSpecifier.Builder>().setSsid(any()) } answers { self as WifiNetworkSpecifier.Builder }
        every { anyConstructed<WifiNetworkSpecifier.Builder>().build() } returns mockk(relaxed = true)
        every { anyConstructed<NetworkRequest.Builder>().addTransportType(any()) } answers { self as NetworkRequest.Builder }
        every { anyConstructed<NetworkRequest.Builder>().removeCapability(any()) } answers { self as NetworkRequest.Builder }
        every { anyConstructed<NetworkRequest.Builder>().setNetworkSpecifier(any<android.net.NetworkSpecifier>()) } answers { self as NetworkRequest.Builder }
        every { anyConstructed<NetworkRequest.Builder>().build() } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkConstructor(WifiNetworkSpecifier.Builder::class, NetworkRequest.Builder::class)
        server.shutdown()
    }

    private fun provisioner(factory: SocketFactory = redirecting): WifiProvisioner {
        val network = mockk<Network>()
        every { network.socketFactory } returns factory
        val connectivity = mockk<ConnectivityManager>(relaxed = true)
        every { connectivity.requestNetwork(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>(), any<Int>()) } answers {
            secondArg<ConnectivityManager.NetworkCallback>().onAvailable(network)
        }
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivity
        return WifiProvisioner(context)
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    /** Starts the flow, waits until the server holds the silent request for [path], cancels the collector, and checks the call went with it. */
    private fun cancelWhileWaitingFor(path: String) = runBlocking {
        silent += path
        val events = Collections.synchronizedList(mutableListOf<SoftApEvent>())
        val job: Job = launch(Dispatchers.Default) {
            provisioner().joinAndProvision("FRY-SETUP-ABCDEF", "home", "secret", "WALLET", null, null).collect { events += it }
        }
        var seen: RecordedRequest?
        do {
            seen = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull("the server never saw a request for $path", seen)
        } while (seen!!.path != path)

        job.cancel()

        assertNotNull("the flow was still running 2 s after the collector was cancelled", withTimeoutOrNull(2_000) { job.join() })
        assertTrue("the in-flight call for $path was not cancelled: ${sockets.map { it.isClosed }}", waitFor(2_000) { sockets.isNotEmpty() && sockets.all { it.isClosed } })
        events.toList()
    }

    @Test
    fun `cancelling the collector cancels GET info in flight`() {
        cancelWhileWaitingFor("/info")
    }

    @Test
    fun `cancelling the collector cancels POST provision in flight`() {
        val events = cancelWhileWaitingFor("/provision")
        assertTrue("$events", events.any { it is SoftApEvent.Info })
    }

    @Test
    fun `cancelling the collector cancels GET status in flight`() {
        val events = cancelWhileWaitingFor("/status")
        assertTrue("$events", events.any { it is SoftApEvent.ProvisionAccepted })
    }

    @Test
    fun `an uncancelled run still reports info, accepted provisioning and the final status`() = runBlocking {
        val events = mutableListOf<SoftApEvent>()
        provisioner().joinAndProvision("FRY-SETUP-ABCDEF", "home", "secret", "WALLET", null, null).collect { events += it }

        assertEquals(listOf("Info", "ProvisionAccepted", "StatusUpdate"), events.map { it::class.simpleName })
        assertEquals(3, (events.last() as SoftApEvent.StatusUpdate).status.status)
    }

    @Test
    fun `a network failure on GET info is still reported as a failure event`() = runBlocking {
        val refusing = object : SocketFactory() {
            override fun createSocket(): Socket = throw IOException("no route")
            override fun createSocket(host: String?, port: Int): Socket = createSocket()
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket()
            override fun createSocket(host: InetAddress?, port: Int): Socket = createSocket()
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket()
        }
        val events = mutableListOf<SoftApEvent>()
        provisioner(refusing).joinAndProvision("FRY-SETUP-ABCDEF", "home", "secret", "WALLET", null, null).collect { events += it }

        val failed = events.single() as SoftApEvent.Failed
        assertTrue(failed.reason, failed.reason.startsWith("GET /info failed:"))
    }
}
