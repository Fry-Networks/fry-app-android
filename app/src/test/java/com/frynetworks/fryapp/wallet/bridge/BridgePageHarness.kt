package com.frynetworks.fryapp.wallet.bridge

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.frynetworks.fryapp.wallet.BridgeEvent
import com.frynetworks.fryapp.wallet.WalletVendor
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll

/**
 * Drives the real [WalletBridgeWebView] on the JVM: the WebView and main-thread Handler are
 * constructor mocks, and a fake bridge page answers the JSON-RPC requests it is sent. The page's
 * WalletConnect session peer ([peerMeta], the `walletconnect` localStorage entry's `peerMeta`) is
 * what any non-RPC script evaluated in the page gets back.
 */
class BridgePageHarness {
    /** `ExternalUriLauncher.open` calls, as `open(<uri>, <vendor>)`. */
    val launches = mutableListOf<String>()
    val requests = mutableListOf<JsonObject>()
    val openUriEvents = mutableListOf<BridgeEvent.OpenUri>()

    /** JSON of the connected session's peerMeta, or null when no session is stored. */
    var peerMeta: String? = null

    /** Methods the page leaves unanswered until [reply] (connect while pairing, signTxns while the wallet decides). */
    var held = setOf("signTxns")

    lateinit var bridge: WalletBridgeWebView
    lateinit var webView: WebView
    lateinit var client: WebViewClient
    lateinit var native: WalletBridgeWebView.NativeInterface

    fun setUp() {
        mockkConstructor(WebView::class, Handler::class)
        every { anyConstructed<Handler>().post(any()) } answers { firstArg<Runnable>().run(); true }
        every { anyConstructed<WebView>().settings } returns mockk<WebSettings>(relaxed = true)
        val clientSlot = slot<WebViewClient>()
        every { anyConstructed<WebView>().webViewClient = capture(clientSlot) } just runs
        val nativeSlot = slot<Any>()
        every { anyConstructed<WebView>().addJavascriptInterface(capture(nativeSlot), any()) } just runs
        every { anyConstructed<WebView>().loadUrl(any<String>()) } just runs
        every { anyConstructed<WebView>().evaluateJavascript(any(), any()) } answers {
            page(firstArg(), secondArg<ValueCallback<String>?>())
        }
        val launcher = mockk<ExternalUriLauncher>()
        every { launcher.open(any(), any()) } answers { launches += "open(${firstArg<String>()}, ${secondArg<WalletVendor?>()})"; true }
        bridge = WalletBridgeWebView(mockk<Context>(relaxed = true), launcher)
        webView = bridge.attach()
        client = clientSlot.captured
        native = nativeSlot.captured as WalletBridgeWebView.NativeInterface
        native.onMessage("""{"event":"ready","version":"1"}""")
    }

    fun tearDown() = unmockkAll()

    private fun page(script: String, callback: ValueCallback<String>?) {
        if (!script.startsWith(DISPATCH)) {
            callback?.onReceiveValue(peerMeta ?: "null")
            return
        }
        val json = Gson().fromJson(script.removePrefix(DISPATCH).removeSuffix(");"), String::class.java)
        val req = JsonParser.parseString(json).asJsonObject
        requests += req
        val method = req["method"].asString
        if (method in held || method == "cancel") return
        val result = when (method) {
            "connect", "reconnect" -> """{"address":"$ADDRESS"}"""
            else -> "{}"
        }
        reply(req["id"].asString, result)
    }

    fun reply(id: String, resultJson: String) = native.onMessage("""{"id":"$id","ok":true,"result":$resultJson}""")

    fun lastRequest(method: String): JsonObject = requests.last { it["method"].asString == method }

    /** The page navigating itself (what the wallet SDKs do with `window.location.href`). */
    fun navigate(url: String, scheme: String, host: String?): Boolean {
        val uri = mockk<Uri>()
        every { uri.scheme } returns scheme
        every { uri.host } returns host
        every { uri.toString() } returns url
        val request = mockk<WebResourceRequest>()
        every { request.url } returns uri
        return client.shouldOverrideUrlLoading(webView, request)
    }

    /** A bridge `openUri` event, as wallets/pera.mjs and defly.mjs emit it. */
    fun openUriEvent(uri: String, wallet: String) = native.onMessage("""{"event":"openUri","uri":"$uri","wallet":"$wallet"}""")

    companion object {
        const val DISPATCH = "window.__fryBridge.dispatch("
        const val ADDRESS = "QAWALLETADDRESS"
        const val PERA_META = """{"name":"Pera Wallet","url":"https://perawallet.app"}"""
        const val DEFLY_META = """{"name":"Defly Wallet","url":"https://defly.app"}"""
        const val SCRIPTED_META = """{"name":"iv1 QA scripted wallet","url":"https://frynetworks.com"}"""
    }
}
