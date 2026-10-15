package com.frynetworks.fryapp.wallet.bridge

import android.webkit.RenderProcessGoneDetail
import com.frynetworks.fryapp.wallet.BridgeErrorCode
import com.frynetworks.fryapp.wallet.BridgeException
import com.frynetworks.fryapp.wallet.WalletVendor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** After a renderer crash the host view must be told to attach a fresh WebView: [WalletBridgeWebView.generation] is that signal. */
@OptIn(ExperimentalCoroutinesApi::class)
class BridgeReattachTest {
    private val h = BridgePageHarness()

    @Before fun setUp() = h.setUp()

    @After fun tearDown() = h.tearDown()

    private fun crash() {
        val detail = mockk<RenderProcessGoneDetail>()
        every { detail.didCrash() } returns true
        assertTrue(h.client.onRenderProcessGone(h.webView, detail))
    }

    @Test
    fun `each renderer crash moves the generation on by one`() = runTest {
        assertEquals(0, h.bridge.generation.value)
        crash()
        assertEquals(1, h.bridge.generation.value)
        crash()
        assertEquals(2, h.bridge.generation.value)
    }

    @Test
    fun `the generation changes only after the dead bridge is fully reset`() = runTest(UnconfinedTestDispatcher()) {
        h.held = setOf("signTxns")
        val signing = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { h.bridge.signTxns(emptyList()) }.exceptionOrNull()
        }
        assertFalse(signing.isCompleted)
        assertTrue(h.bridge.isAttached)

        val seen = mutableListOf<Triple<Int, Boolean, Boolean>>()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            h.bridge.generation.collect { seen += Triple(it, h.bridge.isAttached, signing.isCompleted) }
        }
        crash()
        watcher.cancel()

        // First entry is the value at subscription; the second is the bump, which must see a reset bridge.
        assertEquals(listOf(Triple(0, true, false), Triple(1, false, true)), seen)
        val failure = signing.await()
        assertTrue("$failure", failure is BridgeException && failure.code == BridgeErrorCode.BRIDGE_RESET)
    }

    @Test
    fun `a call made after the crash and a re-attach initialises the new page`() = runTest {
        h.bridge.reconnect(WalletVendor.PERA)
        crash()
        h.bridge.attach()
        assertTrue(h.bridge.isAttached)
        h.native.onMessage("""{"event":"ready","version":"1"}""")

        h.bridge.reconnect(WalletVendor.PERA)

        assertEquals(listOf("init", "reconnect", "init", "reconnect"), h.requests.map { it["method"].asString })
    }
}
