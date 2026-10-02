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
 * AP3-F: on Android the Defly Connect SDK redirects a sign to `algorand://?browser=<name>`
 * (bridge.js: `av() ? "algorand://" : "defly-wc://"`), which app-v0.4.1 launched. It is Defly's
 * sign redirect, so it opens only while a sign is pending on a Defly session, like `defly-wc://`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletLaunchDeflyAlgorandTest {

    private val h = BridgePageHarness()
    private val redirect = "algorand://?browser=Chrome"

    @Before fun setUp() = h.setUp()

    @After fun tearDown() = h.tearDown()

    private fun TestScope.session(meta: String?, vendor: WalletVendor, pendingSign: Boolean) {
        backgroundScope.launch { h.bridge.events.filterIsInstance<BridgeEvent.OpenUri>().collect { h.openUriEvents += it } }
        h.peerMeta = meta
        backgroundScope.async { h.bridge.connect(vendor) }
        if (pendingSign) backgroundScope.async { h.bridge.signTxns(listOf(listOf(TxnToSign("USERLEG", sign = true)))) }
    }

    @Test
    fun `F1 Defly session with a pending sign - the page's algorand redirect opens it as v0_4_1 did`() = runTest(UnconfinedTestDispatcher()) {
        session(BridgePageHarness.DEFLY_META, WalletVendor.DEFLY, pendingSign = true)
        h.native.openUri(redirect)
        assertEquals(listOf("open($redirect, DEFLY)"), h.launches)
        assertEquals(listOf(BridgeEvent.OpenUri(redirect, WalletVendor.DEFLY)), h.openUriEvents)
    }

    @Test
    fun `F1b Defly session with a pending sign - an algorand page navigation opens it too`() = runTest(UnconfinedTestDispatcher()) {
        session(BridgePageHarness.DEFLY_META, WalletVendor.DEFLY, pendingSign = true)
        assertTrue(h.navigate(redirect, "algorand", null))
        assertEquals(listOf("open($redirect, DEFLY)"), h.launches)
    }

    @Test
    fun `F2 Defly session with nothing pending - the algorand redirect opens nothing`() = runTest(UnconfinedTestDispatcher()) {
        session(BridgePageHarness.DEFLY_META, WalletVendor.DEFLY, pendingSign = false)
        h.native.openUri(redirect)
        assertTrue(h.navigate(redirect, "algorand", null))
        assertEquals(emptyList<String>(), h.launches)
        assertEquals(emptyList<BridgeEvent.OpenUri>(), h.openUriEvents)
    }

    @Test
    fun `F3 Pera session with a pending sign - algorand is not Pera's redirect and opens nothing`() = runTest(UnconfinedTestDispatcher()) {
        session(BridgePageHarness.PERA_META, WalletVendor.PERA, pendingSign = true)
        h.native.openUri(redirect)
        assertEquals(emptyList<String>(), h.launches)
        // Positive control: the same session does open Pera's own redirect.
        assertTrue(h.navigate("perawallet-wc://", "perawallet-wc", null))
        assertEquals(listOf("open(perawallet-wc://, PERA)"), h.launches)
    }

    @Test
    fun `F4 unknown session peer with a pending sign - the algorand redirect opens nothing`() = runTest(UnconfinedTestDispatcher()) {
        session(BridgePageHarness.SCRIPTED_META, WalletVendor.DEFLY, pendingSign = true)
        h.native.openUri(redirect)
        assertTrue(h.navigate(redirect, "algorand", null))
        assertEquals(emptyList<String>(), h.launches)
        assertEquals(emptyList<BridgeEvent.OpenUri>(), h.openUriEvents)
    }
}
