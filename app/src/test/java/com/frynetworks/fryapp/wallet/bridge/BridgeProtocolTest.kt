package com.frynetworks.fryapp.wallet.bridge

import com.frynetworks.fryapp.wallet.BridgeErrorCode
import com.frynetworks.fryapp.wallet.BridgeEvent
import com.frynetworks.fryapp.wallet.WalletVendor
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JSON-RPC framing between Kotlin and bridge.js (see tools/bridge/README.md). */
class BridgeProtocolTest {

    @Test
    fun `requests serialise as id, method and params`() {
        val json = BridgeProtocol.encodeRequest("r1", "signTxns", mapOf("groups" to listOf(listOf(mapOf("txnB64" to "AA==", "sign" to true)))))
        val o = JsonParser.parseString(json).asJsonObject
        assertEquals("r1", o["id"].asString)
        assertEquals("signTxns", o["method"].asString)
        assertEquals("AA==", o["params"].asJsonObject["groups"].asJsonArray[0].asJsonArray[0].asJsonObject["txnB64"].asString)
    }

    @Test
    fun `a successful reply carries its id and result`() {
        val m = BridgeProtocol.decode("""{"id":"r1","ok":true,"result":{"address":"ABC"}}""")
        assertTrue(m is BridgeMessage.Reply)
        m as BridgeMessage.Reply
        assertEquals("r1", m.id)
        assertEquals("ABC", m.result?.get("address")?.asString)
        assertNull(m.error)
    }

    @Test
    fun `an error reply maps the wire code onto BridgeErrorCode`() {
        val m = BridgeProtocol.decode("""{"id":"r2","ok":false,"error":{"code":"USER_REJECTED","message":"rejected","detail":"Confirmation Failed(4100)"}}""") as BridgeMessage.Reply
        assertEquals(BridgeErrorCode.USER_REJECTED, m.error?.code)
        assertEquals("rejected", m.error?.message)
        assertEquals("Confirmation Failed(4100)", m.error?.detail)
    }

    @Test
    fun `unknown error codes fall back to UNKNOWN`() {
        val m = BridgeProtocol.decode("""{"id":"r3","ok":false,"error":{"code":"SOMETHING_NEW","message":"x"}}""") as BridgeMessage.Reply
        assertEquals(BridgeErrorCode.UNKNOWN, m.error?.code)
    }

    @Test
    fun `events decode to typed BridgeEvents`() {
        val open = BridgeProtocol.decode("""{"event":"openUri","uri":"perawallet-wc://","wallet":"pera"}""") as BridgeMessage.Event
        assertEquals(BridgeEvent.OpenUri("perawallet-wc://", WalletVendor.PERA), open.event)
        val ready = BridgeProtocol.decode("""{"event":"ready","version":"1"}""") as BridgeMessage.Event
        assertEquals(BridgeEvent.Ready, ready.event)
        val gone = BridgeProtocol.decode("""{"event":"disconnected"}""") as BridgeMessage.Event
        assertEquals(BridgeEvent.Disconnected, gone.event)
    }

    @Test
    fun `garbage is rejected rather than crashing the binder thread`() {
        assertNull(BridgeProtocol.decode("not json"))
        assertNull(BridgeProtocol.decode("""{"neither":"id nor event"}"""))
    }

    @Test
    fun `dispatch scripts embed the request as a JS string literal`() {
        val script = BridgeProtocol.dispatchScript("""{"id":"r1","method":"ping","params":{}}""")
        assertTrue(script.startsWith("window.__fryBridge.dispatch("))
        assertTrue(script.contains("\\\"id\\\":\\\"r1\\\""))
    }
}
