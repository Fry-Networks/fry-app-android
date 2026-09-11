package com.frynetworks.fryapp.wallet.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.frynetworks.fryapp.BuildConfig
import com.frynetworks.fryapp.network.dashboard.DashboardConfig
import com.frynetworks.fryapp.wallet.BridgeErrorCode
import com.frynetworks.fryapp.wallet.BridgeEvent
import com.frynetworks.fryapp.wallet.BridgeException
import com.frynetworks.fryapp.wallet.TxnSummary
import com.frynetworks.fryapp.wallet.TxnToSign
import com.frynetworks.fryapp.wallet.WalletAccount
import com.frynetworks.fryapp.wallet.WalletBridge
import com.frynetworks.fryapp.wallet.WalletVendor
import com.google.gson.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The invisible signing bridge: one WebView (application context, attached by [BridgeHost]) that
 * loads `assets/bridge/bridge.html` through [WebViewAssetLoader] on an https origin and runs the
 * official Pera/Defly SDKs. Kotlin talks JSON-RPC to it ([BridgeProtocol]); wallet deep links the
 * page would `window.open` are routed to [ExternalUriLauncher] instead.
 *
 * The WebView never navigates anywhere else, never sees the dashboard cookies (those live in the
 * OkHttp jar) and exposes only three `@JavascriptInterface` methods that carry no secrets.
 */
@Singleton
class WalletBridgeWebView @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uriLauncher: ExternalUriLauncher,
) : WalletBridge {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject?>>()
    private val _events = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 32)
    override val events: Flow<BridgeEvent> = _events

    @Volatile private var webView: WebView? = null
    @Volatile private var ready = CompletableDeferred<Unit>()
    @Volatile private var initialized = false
    @Volatile private var currentVendor: WalletVendor? = null
    @Volatile var stubMode: Boolean = false

    val isAttached: Boolean get() = webView != null

    /** Creates (once) and returns the WebView; must be called on the main thread by the host view. */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(): WebView {
        webView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    uriLauncher.open(url.toString(), currentVendor)
                    _events.tryEmit(BridgeEvent.OpenUri(url.toString(), currentVendor))
                    return true
                }
                // Only the bridge page itself may load; everything else is blocked.
                return url.host != BRIDGE_HOST
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "bridge renderer gone (crashed=${detail.didCrash()}); resetting")
                resetAfterCrash(view)
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                Log.i(TAG, "bridge page finished: $url")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                Log.w(TAG, "bridge resource error ${error.errorCode} ${error.description} for ${request.url}")
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.i(TAG, "console[${consoleMessage.messageLevel()}] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})")
                return true
            }
        }
        wv.addJavascriptInterface(NativeInterface(), "FryNative")
        wv.loadUrl(BRIDGE_URL)
        webView = wv
        return wv
    }

    private fun resetAfterCrash(dead: WebView) {
        (dead.parent as? ViewGroup)?.removeView(dead)
        runCatching { dead.destroy() }
        webView = null
        initialized = false
        val boom = BridgeException(BridgeErrorCode.BRIDGE_RESET, "The wallet bridge was reset")
        pending.values.forEach { it.completeExceptionally(boom) }
        pending.clear()
        ready = CompletableDeferred()
    }

    inner class NativeInterface {
        @JavascriptInterface
        fun onMessage(json: String) {
            when (val m = BridgeProtocol.decode(json)) {
                is BridgeMessage.Reply -> pending.remove(m.id)?.let { d ->
                    if (m.error == null) d.complete(m.result)
                    else d.completeExceptionally(BridgeException(m.error.code, m.error.message, m.error.detail))
                }
                is BridgeMessage.Event -> {
                    if (m.event is BridgeEvent.Ready) {
                        if (!ready.isCompleted) ready.complete(Unit)
                    }
                    if (m.event is BridgeEvent.OpenUri) uriLauncher.open(m.event.uri, m.event.vendor ?: currentVendor)
                    _events.tryEmit(m.event)
                }
                null -> Log.w(TAG, "ignoring unrecognised bridge message")
            }
        }

        @JavascriptInterface
        fun openUri(uri: String) {
            uriLauncher.open(uri, currentVendor)
            _events.tryEmit(BridgeEvent.OpenUri(uri, currentVendor))
        }

        @JavascriptInterface
        fun log(level: String, message: String) {
            if (BuildConfig.DEBUG) Log.d(TAG, "[$level] $message")
        }
    }

    private suspend fun ensureReady() {
        if (webView == null) throw BridgeException(BridgeErrorCode.BRIDGE_RESET, "The wallet bridge is not attached")
        try {
            withTimeout(READY_TIMEOUT_MS) { ready.await() }
        } catch (e: TimeoutCancellationException) {
            throw BridgeException(BridgeErrorCode.TIMEOUT, "The wallet bridge did not become ready")
        }
        if (!initialized) {
            initialized = true
            call("init", mapOf("algodServer" to DashboardConfig.ALGOD_URL, "stub" to stubMode), 15_000L, skipReady = true)
        }
    }

    private suspend fun call(method: String, params: Map<String, Any?>, timeoutMs: Long, skipReady: Boolean = false): JsonObject {
        if (!skipReady) ensureReady()
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonObject?>()
        pending[id] = deferred
        val script = BridgeProtocol.dispatchScript(BridgeProtocol.encodeRequest(id, method, params))
        mainHandler.post {
            val wv = webView
            if (wv == null) {
                pending.remove(id)?.completeExceptionally(BridgeException(BridgeErrorCode.BRIDGE_RESET, "The wallet bridge is not attached"))
            } else {
                wv.evaluateJavascript(script, null)
            }
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() } ?: JsonObject()
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            mainHandler.post { webView?.evaluateJavascript(BridgeProtocol.dispatchScript(BridgeProtocol.encodeRequest(UUID.randomUUID().toString(), "cancel", mapOf("id" to id))), null) }
            throw BridgeException(BridgeErrorCode.TIMEOUT, "The wallet did not answer in time ($method)")
        }
    }

    override suspend fun connect(vendor: WalletVendor): WalletAccount {
        currentVendor = vendor
        val r = call("connect", mapOf("wallet" to vendor.id), CONNECT_TIMEOUT_MS)
        val address = r.get("address")?.takeIf { !it.isJsonNull }?.asString
            ?: throw BridgeException(BridgeErrorCode.NOT_CONNECTED, "The wallet returned no account")
        return WalletAccount(address, vendor)
    }

    override suspend fun reconnect(vendor: WalletVendor): WalletAccount? {
        currentVendor = vendor
        val r = call("reconnect", mapOf("wallet" to vendor.id), 15_000L)
        val address = r.get("address")?.takeIf { !it.isJsonNull }?.asString ?: return null
        return WalletAccount(address, vendor)
    }

    override suspend fun disconnect() {
        runCatching { call("disconnect", emptyMap(), 10_000L) }
        currentVendor = null
    }

    override suspend fun buildPayment(sender: String, receiver: String, amountMicro: Long, noteUtf8: String?): String =
        call("buildPayment", mapOf("sender" to sender, "receiver" to receiver, "amountMicro" to amountMicro, "noteUtf8" to noteUtf8), 30_000L).txn()

    override suspend fun buildAssetTransfer(sender: String, receiver: String, assetId: Long, amountMicro: Long, noteUtf8: String?): String =
        call("buildAssetTransfer", mapOf("sender" to sender, "receiver" to receiver, "assetId" to assetId, "amountMicro" to amountMicro, "noteUtf8" to noteUtf8), 30_000L).txn()

    override suspend fun buildOptIn(sender: String, assetId: Long): String =
        call("buildOptIn", mapOf("sender" to sender, "assetId" to assetId), 30_000L).txn()

    override suspend fun signTxns(groups: List<List<TxnToSign>>): List<List<String?>> {
        val params = mapOf("groups" to groups.map { g -> g.map { mapOf("txnB64" to it.txnB64, "sign" to it.sign) } })
        val r = call("signTxns", params, SIGN_TIMEOUT_MS)
        val arr = r.getAsJsonArray("signedB64") ?: throw BridgeException(BridgeErrorCode.UNKNOWN, "Bridge returned no signatures")
        return arr.map { g -> g.asJsonArray.map { e -> if (e.isJsonNull) null else e.asString } }
    }

    override suspend fun submit(signedB64: List<String>, waitRounds: Int): List<String> {
        val r = call("submit", mapOf("signedB64" to signedB64, "waitRounds" to waitRounds), 90_000L)
        return r.getAsJsonArray("txIds")?.map { it.asString } ?: emptyList()
    }

    override suspend fun decodeTxn(txnB64: String): TxnSummary {
        val r = call("decodeTxn", mapOf("txnB64" to txnB64), 15_000L)
        fun s(k: String) = r.get(k)?.takeIf { !it.isJsonNull }?.asString
        fun l(k: String) = r.get(k)?.takeIf { !it.isJsonNull }?.asLong
        return TxnSummary(s("type") ?: "unknown", s("sender"), s("receiver"), l("amount"), l("assetId"), s("note"), s("txId"))
    }

    /** Liveness probe used by tests and the account screen. */
    suspend fun ping(): Boolean = call("ping", emptyMap(), 10_000L).get("ok")?.asBoolean == true

    private fun JsonObject.txn(): String =
        get("txnB64")?.takeIf { !it.isJsonNull }?.asString ?: throw BridgeException(BridgeErrorCode.UNKNOWN, "Bridge returned no transaction")

    companion object {
        private const val TAG = "FryBridge"
        const val BRIDGE_HOST = "appassets.androidplatform.net"
        /** `/assets/` maps to the APK assets root, so this serves `assets/bridge/bridge.html`. */
        const val BRIDGE_URL = "https://$BRIDGE_HOST/assets/bridge/bridge.html"
        private const val READY_TIMEOUT_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 120_000L
        private const val SIGN_TIMEOUT_MS = 180_000L
    }
}
