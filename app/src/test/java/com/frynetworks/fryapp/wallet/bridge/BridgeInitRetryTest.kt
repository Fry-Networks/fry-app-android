package com.frynetworks.fryapp.wallet.bridge

import com.frynetworks.fryapp.wallet.BridgeErrorCode
import com.frynetworks.fryapp.wallet.BridgeException
import com.frynetworks.fryapp.wallet.WalletVendor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A bridge `init` that failed, timed out or was cancelled left the bridge marked initialised and was never sent again. */
@OptIn(ExperimentalCoroutinesApi::class)
class BridgeInitRetryTest {
    private val h = BridgePageHarness()

    @Before fun setUp() = h.setUp()

    @After fun tearDown() = h.tearDown()

    private fun methods() = h.requests.map { it["method"].asString }

    @Test
    fun `an init that timed out is sent again before the next call`() = runTest {
        h.held = setOf("init")
        val failure = runCatching { h.bridge.reconnect(WalletVendor.PERA) }.exceptionOrNull()
        assertTrue("$failure", failure is BridgeException && failure.code == BridgeErrorCode.TIMEOUT)

        h.held = emptySet()
        assertEquals(BridgePageHarness.ADDRESS, h.bridge.reconnect(WalletVendor.PERA)?.address)

        assertEquals(listOf("init", "cancel", "init", "reconnect"), methods())
    }

    @Test
    fun `an init that was cancelled mid-flight is sent again before the next call`() = runTest {
        h.held = setOf("init")
        val first = launch(start = CoroutineStart.UNDISPATCHED) { h.bridge.reconnect(WalletVendor.PERA) }
        assertEquals(listOf("init"), methods())
        first.cancel()
        first.join()

        h.held = emptySet()
        assertEquals(BridgePageHarness.ADDRESS, h.bridge.reconnect(WalletVendor.PERA)?.address)

        assertEquals(listOf("init", "init", "reconnect"), methods())
    }

    @Test
    fun `a successful init is not repeated`() = runTest {
        h.bridge.reconnect(WalletVendor.PERA)
        h.bridge.reconnect(WalletVendor.PERA)

        assertEquals(listOf("init", "reconnect", "reconnect"), methods())
    }

    @Test
    fun `the failure of a timed out init still reaches the caller`() = runTest {
        h.held = setOf("init")
        val failure = runCatching { h.bridge.reconnect(WalletVendor.PERA) }.exceptionOrNull()
        assertEquals(BridgeException::class, failure!!::class)
        assertEquals(BridgeErrorCode.TIMEOUT, (failure as BridgeException).code)
    }
}
