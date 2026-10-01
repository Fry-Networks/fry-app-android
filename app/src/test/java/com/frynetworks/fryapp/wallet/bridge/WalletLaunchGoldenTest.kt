package com.frynetworks.fryapp.wallet.bridge

import com.frynetworks.fryapp.wallet.BridgeEvent
import com.frynetworks.fryapp.wallet.TxnToSign
import com.frynetworks.fryapp.wallet.WalletVendor
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * OD-35 characterization goldens: the wallet launches that are RIGHT at app-v0.4.1 (recorded
 * there) and must stay byte-identical after the fix. Never edited to match a change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletLaunchGoldenTest {

    private val h = BridgePageHarness()

    @Before fun setUp() = h.setUp()

    @After fun tearDown() = h.tearDown()

    private fun TestScope.collectOpenUri() {
        backgroundScope.launch { h.bridge.events.filterIsInstance<BridgeEvent.OpenUri>().collect { h.openUriEvents += it } }
    }

    private fun TestScope.pendingSign(): Deferred<List<List<String?>>> =
        backgroundScope.async { h.bridge.signTxns(listOf(listOf(TxnToSign("USERLEG", sign = true), TxnToSign("SERVERLEG", sign = false)))) }

    @Test
    fun `G0 grouped signTxns keeps its wire request and result mapping`() = runTest(UnconfinedTestDispatcher()) {
        collectOpenUri()
        h.peerMeta = BridgePageHarness.PERA_META
        h.bridge.connect(WalletVendor.PERA)
        val signed = pendingSign()
        val req = h.lastRequest("signTxns")
        assertEquals("""{"groups":[[{"txnB64":"USERLEG","sign":true},{"txnB64":"SERVERLEG","sign":false}]]}""", req["params"].toString())
        h.reply(req["id"].asString, """{"signedB64":[["SIGNEDLEG",null]]}""")
        assertEquals(listOf(listOf("SIGNEDLEG", null)), signed.await())
        assertEquals(listOf("init", "connect", "signTxns"), h.requests.map { it["method"].asString })
        assertEquals(emptyList<String>(), h.launches)
        assertEquals(emptyList<BridgeEvent.OpenUri>(), h.openUriEvents)
    }

    @Test
    fun `G1 Pera session with a pending sign - the page redirect opens Pera`() = runTest(UnconfinedTestDispatcher()) {
        collectOpenUri()
        h.peerMeta = BridgePageHarness.PERA_META
        h.bridge.connect(WalletVendor.PERA)
        pendingSign()
        assertTrue(h.navigate("perawallet-wc://", "perawallet-wc", null))
        assertEquals(listOf("open(perawallet-wc://, PERA)"), h.launches)
        assertEquals(listOf(BridgeEvent.OpenUri("perawallet-wc://", WalletVendor.PERA)), h.openUriEvents)
    }

    @Test
    fun `G1b Pera session with a pending sign - the bridge sign event opens Pera`() = runTest(UnconfinedTestDispatcher()) {
        collectOpenUri()
        h.peerMeta = BridgePageHarness.PERA_META
        h.bridge.connect(WalletVendor.PERA)
        pendingSign()
        h.openUriEvent("perawallet-wc://", "pera")
        assertEquals(listOf("open(perawallet-wc://, PERA)"), h.launches)
        assertEquals(listOf(BridgeEvent.OpenUri("perawallet-wc://", WalletVendor.PERA)), h.openUriEvents)
    }

    @Test
    fun `G2 Defly session with a pending sign - the page redirect opens Defly`() = runTest(UnconfinedTestDispatcher()) {
        collectOpenUri()
        h.peerMeta = BridgePageHarness.DEFLY_META
        h.bridge.connect(WalletVendor.DEFLY)
        pendingSign()
        assertTrue(h.navigate("defly-wc://", "defly-wc", null))
        assertEquals(listOf("open(defly-wc://, DEFLY)"), h.launches)
        assertEquals(listOf(BridgeEvent.OpenUri("defly-wc://", WalletVendor.DEFLY)), h.openUriEvents)
    }

    @Test
    fun `G3 connect - the wc pairing URI opens the chosen wallet`() = runTest(UnconfinedTestDispatcher()) {
        collectOpenUri()
        h.held = setOf("connect")
        val account = backgroundScope.async { h.bridge.connect(WalletVendor.PERA) }
        val wc = "wc:00e46b69-d0cc-4b3e-b6a2-cee442f97188@1?bridge=https%3A%2F%2Fbridge.example&key=00"
        h.openUriEvent(wc, "pera")
        assertEquals(listOf("open($wc, PERA)"), h.launches)
        assertEquals(listOf(BridgeEvent.OpenUri(wc, WalletVendor.PERA)), h.openUriEvents)
        h.reply(h.lastRequest("connect")["id"].asString, """{"address":"${BridgePageHarness.ADDRESS}"}""")
        assertEquals(BridgePageHarness.ADDRESS, account.await().address)
    }

    @Test
    fun `G4 http navigation never launches anything and only the bridge page may load`() = runTest(UnconfinedTestDispatcher()) {
        collectOpenUri()
        h.peerMeta = BridgePageHarness.PERA_META
        h.bridge.connect(WalletVendor.PERA)
        pendingSign()
        assertTrue(h.navigate("https://perawallet.app/", "https", "perawallet.app"))
        assertFalse(h.navigate(WalletBridgeWebView.BRIDGE_URL, "https", WalletBridgeWebView.BRIDGE_HOST))
        assertEquals(emptyList<String>(), h.launches)
        assertEquals(emptyList<BridgeEvent.OpenUri>(), h.openUriEvents)
    }
}
