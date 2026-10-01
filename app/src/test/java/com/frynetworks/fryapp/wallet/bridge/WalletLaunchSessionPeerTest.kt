package com.frynetworks.fryapp.wallet.bridge

import com.frynetworks.fryapp.wallet.BridgeEvent
import com.frynetworks.fryapp.wallet.TxnToSign
import com.frynetworks.fryapp.wallet.WalletVendor
import com.google.gson.Gson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OD-35, how the session's wallet is learnt: the bridge asks the page for the connected
 * WalletConnect session's peer, and the answer must carry only its name and url (never the
 * session key, bridge or handshake). A sign that finished while the page was answering opens
 * nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletLaunchSessionPeerTest {

    private val h = BridgePageHarness()

    @Before fun setUp() = h.setUp()

    @After fun tearDown() = h.tearDown()

    @Test
    fun `D6 the sign finished before the page answered - nothing is opened`() = runTest(UnconfinedTestDispatcher()) {
        backgroundScope.launch { h.bridge.events.filterIsInstance<BridgeEvent.OpenUri>().collect { h.openUriEvents += it } }
        h.peerMeta = BridgePageHarness.PERA_META
        h.deferPeerQuery = true
        h.bridge.connect(WalletVendor.PERA)
        val signed = backgroundScope.async { h.bridge.signTxns(listOf(listOf(TxnToSign("USERLEG", sign = true)))) }
        assertTrue(h.navigate("perawallet-wc://", "perawallet-wc", null))
        h.reply(h.lastRequest("signTxns")["id"].asString, """{"signedB64":[["SIGNEDLEG"]]}""")
        signed.await()
        h.deferredPeerAnswers.forEach { it() }
        assertEquals(emptyList<String>(), h.launches)
        assertEquals(emptyList<BridgeEvent.OpenUri>(), h.openUriEvents)
    }

    @Test
    fun `P1 the session-peer query returns only the connected peer's name and url`() = runTest(UnconfinedTestDispatcher()) {
        h.peerMeta = BridgePageHarness.PERA_META
        h.bridge.connect(WalletVendor.PERA)
        backgroundScope.async { h.bridge.signTxns(listOf(listOf(TxnToSign("USERLEG", sign = true)))) }
        h.navigate("perawallet-wc://", "perawallet-wc", null)
        val query = h.peerScripts.single()

        val session = mapOf(
            "connected" to true, "accounts" to listOf("QAWALLETADDRESS"), "chainId" to 416001,
            "bridge" to "https://bridge.example", "key" to "SECRETSESSIONKEY", "clientId" to "client-1",
            "clientMeta" to mapOf("name" to "Fry"), "peerId" to "peer-1", "handshakeId" to 1700000000L,
            "handshakeTopic" to "topic-1",
            "peerMeta" to mapOf("name" to "Pera Wallet", "url" to "https://perawallet.app", "description" to "d", "icons" to listOf("i")),
        )
        assertEquals("""{"name":"Pera Wallet","url":"https://perawallet.app"}""", runInPage(query, Gson().toJson(session)))
        assertEquals("null", runInPage(query, Gson().toJson(session + ("connected" to false))))
        assertEquals("null", runInPage(query, null))
        assertEquals("null", runInPage(query, "not json"))
    }

    /** Evaluates [script] in node against a localStorage whose `walletconnect` entry is [stored]; prints JSON of the result. */
    private fun runInPage(script: String, stored: String?): String {
        val js = File.createTempFile("peerquery", ".js").apply { deleteOnExit() }
        js.writeText(
            "const stored = ${Gson().toJson(stored)};\n" +
                "globalThis.localStorage = { getItem: (k) => (k === 'walletconnect' ? stored : null) };\n" +
                "const r = eval(${Gson().toJson(script)});\n" +
                "process.stdout.write(JSON.stringify(r === undefined ? null : r));\n",
        )
        val p = ProcessBuilder("node", js.absolutePath).redirectErrorStream(true).start()
        assertTrue("node did not finish", p.waitFor(30, TimeUnit.SECONDS))
        val out = p.inputStream.bufferedReader().readText()
        assertEquals("node failed: $out", 0, p.exitValue())
        return out
    }
}
