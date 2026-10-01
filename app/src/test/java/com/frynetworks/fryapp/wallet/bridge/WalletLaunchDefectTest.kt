package com.frynetworks.fryapp.wallet.bridge

import com.frynetworks.fryapp.wallet.BridgeEvent
import com.frynetworks.fryapp.wallet.TxnToSign
import com.frynetworks.fryapp.wallet.WalletVendor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * OD-35: the app launched Pera on a bare `perawallet-wc://` whatever the session's wallet and
 * whether or not anything was waiting to be signed. A wallet is opened only for a pending sign
 * request, and only the wallet the session is actually paired with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletLaunchDefectTest {

    private val h = BridgePageHarness()

    @Before fun setUp() = h.setUp()

    @After fun tearDown() = h.tearDown()

    private fun TestScope.collectOpenUri() {
        backgroundScope.launch { h.bridge.events.filterIsInstance<BridgeEvent.OpenUri>().collect { h.openUriEvents += it } }
    }

    private fun TestScope.pendingSign() {
        backgroundScope.async { h.bridge.signTxns(listOf(listOf(TxnToSign("USERLEG", sign = true)))) }
    }

    private fun assertNothingLaunched() {
        assertEquals(emptyList<String>(), h.launches)
        assertEquals(emptyList<BridgeEvent.OpenUri>(), h.openUriEvents)
    }

    @Test
    fun `D3 scripted peer with a pending sign - the Pera redirect does not open Pera`() = runTest(UnconfinedTestDispatcher()) {
        collectOpenUri()
        h.peerMeta = BridgePageHarness.SCRIPTED_META
        h.bridge.connect(WalletVendor.PERA)
        pendingSign()
        assertTrue("the navigation itself stays blocked", h.navigate("perawallet-wc://", "perawallet-wc", null))
        assertNothingLaunched()
    }

    @Test
    fun `D3b scripted peer with a pending sign - the bridge sign event does not open Pera`() = runTest(UnconfinedTestDispatcher()) {
        collectOpenUri()
        h.peerMeta = BridgePageHarness.SCRIPTED_META
        h.bridge.connect(WalletVendor.PERA)
        pendingSign()
        h.openUriEvent("perawallet-wc://", "pera")
        assertNothingLaunched()
    }

    @Test
    fun `D3c no stored session with a pending sign - nothing is opened`() = runTest(UnconfinedTestDispatcher()) {
        collectOpenUri()
        h.peerMeta = null
        h.bridge.connect(WalletVendor.PERA)
        pendingSign()
        assertTrue(h.navigate("perawallet-wc://", "perawallet-wc", null))
        assertNothingLaunched()
    }

    @Test
    fun `D4 Pera session with nothing pending - the redirect does not open Pera`() = runTest(UnconfinedTestDispatcher()) {
        collectOpenUri()
        h.peerMeta = BridgePageHarness.PERA_META
        h.bridge.connect(WalletVendor.PERA)
        assertTrue(h.navigate("perawallet-wc://", "perawallet-wc", null))
        assertNothingLaunched()
    }

    @Test
    fun `D4b Pera session with nothing pending - a sign event does not open Pera`() = runTest(UnconfinedTestDispatcher()) {
        collectOpenUri()
        h.peerMeta = BridgePageHarness.PERA_META
        h.bridge.connect(WalletVendor.PERA)
        h.openUriEvent("perawallet-wc://", "pera")
        assertNothingLaunched()
    }

    @Test
    fun `D4c after the sign completes a late redirect does not open Pera`() = runTest(UnconfinedTestDispatcher()) {
        collectOpenUri()
        h.peerMeta = BridgePageHarness.PERA_META
        h.bridge.connect(WalletVendor.PERA)
        val signed = backgroundScope.async { h.bridge.signTxns(listOf(listOf(TxnToSign("USERLEG", sign = true)))) }
        h.reply(h.lastRequest("signTxns")["id"].asString, """{"signedB64":[["SIGNEDLEG"]]}""")
        signed.await()
        assertTrue(h.navigate("perawallet-wc://", "perawallet-wc", null))
        assertNothingLaunched()
    }

    @Test
    fun `D5 Defly session with a pending sign - a Pera redirect does not open Pera`() = runTest(UnconfinedTestDispatcher()) {
        collectOpenUri()
        h.peerMeta = BridgePageHarness.DEFLY_META
        h.bridge.connect(WalletVendor.DEFLY)
        pendingSign()
        assertTrue(h.navigate("perawallet-wc://", "perawallet-wc", null))
        assertNothingLaunched()
    }
}
