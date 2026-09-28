package com.frynetworks.fryapp.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import com.frynetworks.fryapp.provisioning.DeviceCapabilities
import com.frynetworks.fryapp.provisioning.KeyTransport
import com.frynetworks.fryapp.provisioning.KeyTransportPolicy
import com.frynetworks.fryapp.provisioning.SoftApHandoff
import com.google.gson.Gson
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** `GET /info` response — PROTOCOL.md section 3. */
data class SoftApInfo(
    val deviceName: String?,
    /** Masked (`FEM-AB…`) on a v1.1 board; never a usable key then. */
    val minerKey: String?,
    val fw: String?,
    val chip: String?,
    /** PROTOCOL.md 11.6: `2` on v1.1 boards, absent before. */
    val proto: Int? = null,
    val caps: List<String>? = null,
    val keySet: Boolean? = null,
)

/** v1.1 capabilities from `/info`; a board that sends no `proto` speaks protocol 1. */
fun SoftApInfo.capabilities(): DeviceCapabilities =
    if (proto == null) DeviceCapabilities.PROTO_1 else DeviceCapabilities(proto, caps.orEmpty().toSet(), keyPresent = keySet, fw = fw)

/** `GET /status` response — PROTOCOL.md section 3, same state machine as BLE section 2. */
data class SoftApStatus(
    val status: Int,
    val err: Int,
    val minerKey: String?,
    val ip: String?,
    /** PROTOCOL.md v1.1: the refined error code (6–13) when `err` is the legacy 4. */
    val detail: Int? = null,
)

sealed class SoftApEvent {
    data class Info(val info: SoftApInfo) : SoftApEvent()
    data class StatusUpdate(val status: SoftApStatus) : SoftApEvent()
    data object ProvisionAccepted : SoftApEvent()
    data class Failed(val reason: String) : SoftApEvent()

    /** `POST /provision` answered with an error status and (v1.1) an `err` code, e.g. 422 key_required. */
    data class Refused(val httpCode: Int, val err: String?) : SoftApEvent()

    /** The setup AP went away after the board accepted the settings: it is joining Wi-Fi. */
    data object Handoff : SoftApEvent()
}

private val SOFTAP_SSID_REGEX = Regex("^FRY-SETUP-[0-9A-F]{6}$")
private const val SOFTAP_IP = "192.168.4.1"
private const val JOIN_TIMEOUT_MS = 30_000
private const val STATUS_POLL_INTERVAL_MS = 2_000L
private const val STATUS_POLL_TIMEOUT_MS = 60_000L
private const val PROV_STATUS_CONNECTED = 3
private const val PROV_STATUS_ERROR = 4
private const val SOFTAP_SCAN_WINDOW_MS = 15_000L

/**
 * Provisions an ESP8266 board over its SoftAP per PROTOCOL.md section 3: join the open
 * `FRY-SETUP-<MAC6>` access point (no internet), then GET /info, POST /provision, and poll
 * GET /status every 2s for up to 60s.
 *
 * The AP has no internet, so [ConnectivityManager.requestNetwork] with
 * `NET_CAPABILITY_INTERNET` removed is used instead of [ConnectivityManager.bindProcessToNetwork]:
 * all HTTP calls are bound to the returned [Network]'s own `socketFactory`, which keeps the
 * OkHttp client's traffic on that network specifically and never leaks it onto (or off of) the
 * process's default network.
 */
@Singleton
class WifiProvisioner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @SuppressLint("MissingPermission")
    fun scanForSoftApSsids(): Flow<String> = callbackFlow {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            close(IllegalStateException("WifiManager unavailable"))
            return@callbackFlow
        }

        val seen = HashSet<String>()
        // runCatching: onReceive runs on the main thread, and scanResults throws SecurityException
        // when Location is revoked mid-scan; that must not take the process down.
        fun emitSetupNetworks() = runCatching {
            wifiManager.scanResults
                .mapNotNull { it.SSID }
                .filter { SOFTAP_SSID_REGEX.matches(it) && seen.add(it) }
                .forEach { trySend(it) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                emitSetupNetworks()
            }
        }
        context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

        // Android throttles app-triggered scans (4 per 2 minutes), so the last results count too.
        emitSetupNetworks()
        @Suppress("DEPRECATION")
        wifiManager.startScan()

        // Bounded like the BLE scan. An open-ended flow kept the Scan screen on "Scanning..."
        // forever, because the screen waits for both scans to finish (U5).
        val windowJob = launch {
            delay(SOFTAP_SCAN_WINDOW_MS)
            close()
        }

        awaitClose {
            windowJob.cancel()
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    fun provision(apSsid: String, wifiSsid: String, wifiPass: String, wallet: String): Flow<SoftApEvent> =
        provision(apSsid, wifiSsid, wifiPass, wallet, minerKey = null, setupCode = null)

    /**
     * Joining the setup AP needs [WifiNetworkSpecifier] (API 29); older phones get a clear failure.
     * [setupCode] is the WPA2 passphrase of a keyless v1.1 board's AP (shown only over USB); the
     * owner's [minerKey] is sent only over that protected AP ([KeyTransportPolicy]).
     */
    fun provision(
        apSsid: String,
        wifiSsid: String,
        wifiPass: String,
        wallet: String,
        minerKey: String?,
        setupCode: String?,
    ): Flow<SoftApEvent> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            joinAndProvision(apSsid, wifiSsid, wifiPass, wallet, minerKey, setupCode)
        } else {
            flowOf(SoftApEvent.Failed(SOFTAP_NEEDS_ANDROID_10))
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun joinAndProvision(
        apSsid: String,
        wifiSsid: String,
        wifiPass: String,
        wallet: String,
        minerKey: String?,
        setupCode: String?,
    ): Flow<SoftApEvent> =
        callbackFlow {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val specifier = WifiNetworkSpecifier.Builder().setSsid(apSsid)
                .apply { if (setupCode != null) setWpa2Passphrase(setupCode) }
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            var settled = false
            val apLost = java.util.concurrent.atomic.AtomicBoolean(false)
            val networkDeferred = CompletableDeferred<Network?>()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    apLost.set(true)
                }

                override fun onAvailable(network: Network) {
                    if (!settled) {
                        settled = true
                        networkDeferred.complete(network)
                    }
                }

                override fun onUnavailable() {
                    if (!settled) {
                        settled = true
                        networkDeferred.complete(null)
                    }
                }
            }

            // Bounded by the system's own chooser/association window; onUnavailable fires on
            // timeout or user decline.
            connectivityManager.requestNetwork(request, callback, JOIN_TIMEOUT_MS)

            // Idempotent, because the try/finally below and the awaitClose blocks can both fire.
            val unregistered = java.util.concurrent.atomic.AtomicBoolean(false)
            fun unregisterOnce() {
                if (unregistered.compareAndSet(false, true)) {
                    runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                }
            }

            // try/finally around every suspend point, not awaitClose alone. If the collector is
            // cancelled while we are waiting to join or polling /status -- the user backing out of
            // the provisioning screen -- awaitClose is never reached and the callback stays
            // registered, leaving the process steered at an internet-less network. That is exactly
            // the failure this class documents itself as avoiding. Repeated leaks also count
            // against the per-UID NetworkRequest cap.
            try {

            val network = withTimeoutOrNull(JOIN_TIMEOUT_MS.toLong() + 1_000) { networkDeferred.await() }
            if (network == null) {
                trySend(SoftApEvent.Failed("Failed to join $apSsid"))
                close()
                awaitClose { unregisterOnce() }
                return@callbackFlow
            }

            val client = OkHttpClient.Builder()
                .socketFactory(network.socketFactory)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val gson = Gson()

            val info = try {
                val req = Request.Builder().url("http://$SOFTAP_IP/info").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("GET /info failed: HTTP ${resp.code}")
                    gson.fromJson(resp.body?.string(), SoftApInfo::class.java)
                }
            } catch (e: Exception) {
                trySend(SoftApEvent.Failed("GET /info failed: ${e.message}"))
                close()
                awaitClose { unregisterOnce() }
                return@callbackFlow
            }
            trySend(SoftApEvent.Info(info))

            val caps = info.capabilities()
            val transport = if (minerKey == null) null else KeyTransportPolicy.forSoftAp(caps, joinedWithSetupCode = setupCode != null)
            if (transport is KeyTransport.Refuse) {
                trySend(SoftApEvent.Failed(transport.reason))
                close()
                awaitClose { unregisterOnce() }
                return@callbackFlow
            }

            try {
                val body = FormBody.Builder()
                    .add("ssid", wifiSsid)
                    .add("pass", wifiPass)
                    .add("wallet", wallet)
                    .apply { if (transport == KeyTransport.Send && minerKey != null) add("key", minerKey) }
                    .build()
                val req = Request.Builder().url("http://$SOFTAP_IP/provision").post(body).build()
                val refused = client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) null else SoftApEvent.Refused(resp.code, errCode(resp.body?.string()))
                }
                if (refused != null) {
                    trySend(refused)
                    close()
                    awaitClose { unregisterOnce() }
                    return@callbackFlow
                }
            } catch (e: Exception) {
                trySend(SoftApEvent.Failed("POST /provision failed: ${e.message}"))
                close()
                awaitClose { unregisterOnce() }
                return@callbackFlow
            }
            trySend(SoftApEvent.ProvisionAccepted)

            var handedOff = false
            val polledOk = withTimeoutOrNull(STATUS_POLL_TIMEOUT_MS) {
                var reachedTerminal = false
                var failures = 0
                while (!reachedTerminal) {
                    val status = try {
                        val req = Request.Builder().url("http://$SOFTAP_IP/status").build()
                        client.newCall(req).execute().use { resp ->
                            gson.fromJson(resp.body?.string(), SoftApStatus::class.java)
                        }
                    } catch (e: Exception) {
                        null
                    }
                    failures = if (status == null) failures + 1 else 0
                    if (SoftApHandoff.isHandoff(accepted = true, consecutiveFailures = failures, apLost = apLost.get())) {
                        handedOff = true
                        trySend(SoftApEvent.Handoff)
                        break
                    }
                    if (status != null) {
                        trySend(SoftApEvent.StatusUpdate(status))
                        if (status.status == PROV_STATUS_CONNECTED || status.status == PROV_STATUS_ERROR) {
                            reachedTerminal = true
                        }
                    }
                    if (!reachedTerminal) delay(STATUS_POLL_INTERVAL_MS)
                }
                true
            }
            if (polledOk != true && !handedOff) {
                trySend(SoftApEvent.Failed("Status polling timed out"))
            }

            close()
            awaitClose { unregisterOnce() }
            } finally {
                unregisterOnce()
            }
        }
}

/** The v1.1 `err` field of a `/provision` error body, e.g. `{"err":"key_required"}`; null when absent. */
internal fun errCode(body: String?): String? =
    runCatching { JsonParser.parseString(body.orEmpty()).asJsonObject["err"]?.takeIf { !it.isJsonNull }?.asString }.getOrNull()

const val SOFTAP_NEEDS_ANDROID_10 =
    "Setting up an ESP8266 over its Wi-Fi setup network needs Android 10 or newer. Use the USB web setup instead."
