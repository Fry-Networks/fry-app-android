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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import javax.net.SocketFactory

/** The setup-AP HTTP calls are blocking OkHttp calls: they must never run on the thread that collects the flow (Main). */
@SuppressLint("NewApi")
class WifiProvisionerDispatcherTest {

    private val threads = Collections.synchronizedList(mutableListOf<Pair<String, Thread>>())

    /** Records the thread of every use, then refuses the connection so the flow ends with a failure event. */
    private val recordingFactory = object : SocketFactory() {
        override fun createSocket(): Socket {
            threads += "createSocket" to Thread.currentThread()
            throw IOException("no route")
        }
        override fun createSocket(host: String?, port: Int): Socket = createSocket()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket()
        override fun createSocket(host: InetAddress?, port: Int): Socket = createSocket()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket()
    }

    @Before
    fun setUp() {
        mockkConstructor(WifiNetworkSpecifier.Builder::class, NetworkRequest.Builder::class)
        every { anyConstructed<WifiNetworkSpecifier.Builder>().setSsid(any()) } answers { self as WifiNetworkSpecifier.Builder }
        every { anyConstructed<WifiNetworkSpecifier.Builder>().build() } returns mockk(relaxed = true)
        every { anyConstructed<NetworkRequest.Builder>().addTransportType(any()) } answers { self as NetworkRequest.Builder }
        every { anyConstructed<NetworkRequest.Builder>().removeCapability(any()) } answers { self as NetworkRequest.Builder }
        every { anyConstructed<NetworkRequest.Builder>().setNetworkSpecifier(any<android.net.NetworkSpecifier>()) } answers { self as NetworkRequest.Builder }
        every { anyConstructed<NetworkRequest.Builder>().build() } returns mockk(relaxed = true)
    }

    @After fun tearDown() = unmockkConstructor(WifiNetworkSpecifier.Builder::class, NetworkRequest.Builder::class)

    @Test
    fun `GET info and the other setup AP calls run off the collecting thread`() = runTest {
        val network = mockk<Network>()
        every { network.socketFactory } answers {
            threads += "socketFactory" to Thread.currentThread()
            recordingFactory
        }
        val connectivity = mockk<ConnectivityManager>(relaxed = true)
        every { connectivity.requestNetwork(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>(), any<Int>()) } answers {
            secondArg<ConnectivityManager.NetworkCallback>().onAvailable(network)
        }
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivity

        val collector = Thread.currentThread()
        val events = WifiProvisioner(context)
            .joinAndProvision("FRY-SETUP-ABCDEF", "home", "secret", "WALLET", null, null)
            .toList()

        assertTrue("$events", events.single() is SoftApEvent.Failed)
        assertTrue("the socket factory was never used: $threads", threads.map { it.first }.containsAll(listOf("socketFactory", "createSocket")))
        val onCollector = threads.filter { it.second === collector }
        assertEquals(
            "ran on the collector thread ${collector.name}: ${onCollector.map { it.first }}",
            emptyList<String>(),
            onCollector.map { it.first },
        )
    }
}
