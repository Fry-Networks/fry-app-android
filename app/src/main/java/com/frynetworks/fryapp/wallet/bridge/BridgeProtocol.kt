package com.frynetworks.fryapp.wallet.bridge

import com.frynetworks.fryapp.wallet.BridgeErrorCode
import com.frynetworks.fryapp.wallet.BridgeEvent
import com.frynetworks.fryapp.wallet.WalletVendor
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class BridgeError(val code: BridgeErrorCode, val message: String, val detail: String?)

sealed interface BridgeMessage {
    data class Reply(val id: String, val result: JsonObject?, val error: BridgeError?) : BridgeMessage
    data class Event(val event: BridgeEvent, val raw: JsonObject) : BridgeMessage
}

/**
 * JSON-RPC framing shared with `tools/bridge/src/rpc.mjs`:
 * Kotlin -> JS: `window.__fryBridge.dispatch('{"id","method","params"}')`
 * JS -> Kotlin: `FryNative.onMessage('{"id","ok":true,"result"}' | '{"id","ok":false,"error"}' | '{"event",...}')`
 */
object BridgeProtocol {
    private val gson = Gson()

    fun encodeRequest(id: String, method: String, params: Map<String, Any?>): String {
        val o = JsonObject()
        o.addProperty("id", id)
        o.addProperty("method", method)
        o.add("params", gson.toJsonTree(params))
        return gson.toJson(o)
    }

    /** JS statement handing the request JSON to the page as a string literal. */
    fun dispatchScript(requestJson: String): String =
        "window.__fryBridge.dispatch(" + gson.toJson(requestJson) + ");"

    fun decode(json: String): BridgeMessage? {
        val o = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        val id = o.get("id")?.takeIf { !it.isJsonNull }?.asString
        if (id != null) {
            val ok = o.get("ok")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            if (ok) return BridgeMessage.Reply(id, o.get("result")?.takeIf { it.isJsonObject }?.asJsonObject, null)
            val e = o.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
            val error = BridgeError(
                code = BridgeErrorCode.fromWire(e?.get("code")?.takeIf { !it.isJsonNull }?.asString),
                message = e?.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "Bridge error",
                detail = e?.get("detail")?.takeIf { !it.isJsonNull }?.asString,
            )
            return BridgeMessage.Reply(id, null, error)
        }
        val name = o.get("event")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val event = when (name) {
            "openUri" -> BridgeEvent.OpenUri(
                uri = o.get("uri")?.takeIf { !it.isJsonNull }?.asString ?: return null,
                vendor = WalletVendor.fromId(o.get("wallet")?.takeIf { !it.isJsonNull }?.asString),
            )
            "ready" -> BridgeEvent.Ready
            "disconnected" -> BridgeEvent.Disconnected
            else -> return null
        }
        return BridgeMessage.Event(event, o)
    }
}
