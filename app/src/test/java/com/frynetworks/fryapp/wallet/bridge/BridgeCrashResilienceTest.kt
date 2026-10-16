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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Concurrent first calls, stale crash callbacks and crashes while calls wait for the page must not leave the bridge in a wrong state. */
@OptIn(ExperimentalCoroutinesApi::class)
class BridgeCrashResilienceTest {
    private val h = BridgePageHarness()

    @Before fun setUp() = h.setUp()

    @After fun tearDown() = h.tearDown()

    private fun methods() = h.requests.map { it["method"].asString }

    private fun crash(view: android.webkit.WebView = h.webView) {
        val detail = mockk<RenderProcessGoneDetail>()
        every { detail.didCrash() } returns true
        assertTrue(h.client.onRenderProcessGone(view, detail))
    }

    @Test
    fun `a second call waits for the init that is in flight instead of racing it`() = runTest(UnconfinedTestDispatcher()) {
        h.held = setOf("init")
        val first = launch(start = CoroutineStart.UNDISPATCHED) { h.bridge.reconnect(WalletVendor.PERA) }
        val second = launch(start = CoroutineStart.UNDISPATCHED) { h.bridge.reconnect(WalletVendor.PERA) }
        assertEquals(listOf("init"), methods())

        h.held = emptySet()
        h.reply(h.lastRequest("init")["id"].asString, "{}")
        first.join()
        second.join()

        assertEquals(listOf("init", "reconnect", "reconnect"), methods())
    }

    @Test
    fun `a crash between the init reply and the resumed caller does not mark the new page initialised`() = runTest {
        h.held = setOf("init")
        val caller = launch { runCatching { h.bridge.reconnect(WalletVendor.PERA) } }
        runCurrent()
        assertEquals(listOf("init"), methods())

        h.reply(h.lastRequest("init")["id"].asString, "{}")
        crash() // before the caller has resumed
        runCurrent()
        caller.join()

        h.bridge.attach()
        h.native.onMessage("""{"event":"ready","version":"1"}""")
        h.held = emptySet()
        h.bridge.reconnect(WalletVendor.PERA)

        assertEquals(listOf("init", "init", "reconnect"), methods())
    }

    @Test
    fun `a crash callback for an old view is ignored while a newer one is attached`() = runTest {
        val old = h.webView
        crash(old)
        assertEquals(1, h.bridge.generation.value)
        h.bridge.attach()
        assertTrue(h.bridge.isAttached)

        crash(old)

        assertEquals(1, h.bridge.generation.value)
        assertTrue(h.bridge.isAttached)
    }

    @Test
    fun `a call waiting for the page to become ready fails with BRIDGE_RESET as soon as the page crashes`() = runTest(UnconfinedTestDispatcher()) {
        crash()
        val current = h.bridge.attach() // attached, but the page never says ready
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { runCatching { h.bridge.reconnect(WalletVendor.PERA) }.exceptionOrNull() }
        assertFalse(waiter.isCompleted)

        crash(current)

        assertTrue("the waiter was left hanging", waiter.isCompleted)
        val failure = waiter.await()
        assertTrue("$failure", failure is BridgeException && failure.code == BridgeErrorCode.BRIDGE_RESET)
    }
}
