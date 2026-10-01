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
import org.junit.Before
import org.junit.Test

/**
 * OD-35 characterization goldens, part 2 (T2 r1 MINOR-4): two more launch routes that are RIGHT
 * at app-v0.4.1 and must stay byte-identical after the fix. Never edited to match a change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletLaunchGoldenExtraTest {

    private val h = BridgePageHarness()

    @Before fun setUp() = h.setUp()

    @After fun tearDown() = h.tearDown()

    private fun TestScope.collectOpenUri() {
        backgroundScope.launch { h.bridge.events.filterIsInstance<BridgeEvent.OpenUri>().collect { h.openUriEvents += it } }
    }

    @Test
    fun `G2b Defly session with a pending sign - the bridge sign event opens Defly`() = runTest(UnconfinedTestDispatcher()) {
        collectOpenUri()
        h.peerMeta = BridgePageHarness.DEFLY_META
        h.bridge.connect(WalletVendor.DEFLY)
        backgroundScope.async { h.bridge.signTxns(listOf(listOf(TxnToSign("USERLEG", sign = true), TxnToSign("SERVERLEG", sign = false)))) }
        h.openUriEvent("defly-wc://", "defly")
        assertEquals(listOf("open(defly-wc://, DEFLY)"), h.launches)
        assertEquals(listOf(BridgeEvent.OpenUri("defly-wc://", WalletVendor.DEFLY)), h.openUriEvents)
    }

    @Test
    fun `G3b connect - a wc pairing URI through window open opens the chosen wallet`() = runTest(UnconfinedTestDispatcher()) {
        collectOpenUri()
        h.held = setOf("connect")
        val account = backgroundScope.async { h.bridge.connect(WalletVendor.PERA) }
        val wc = "wc:00e46b69-d0cc-4b3e-b6a2-cee442f97188@1?bridge=https%3A%2F%2Fbridge.example&key=00"
        h.native.openUri(wc)
        assertEquals(listOf("open($wc, PERA)"), h.launches)
        assertEquals(listOf(BridgeEvent.OpenUri(wc, WalletVendor.PERA)), h.openUriEvents)
        h.reply(h.lastRequest("connect")["id"].asString, """{"address":"${BridgePageHarness.ADDRESS}"}""")
        assertEquals(BridgePageHarness.ADDRESS, account.await().address)
    }
}
