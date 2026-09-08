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
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
    val minerKey: String?,
    val fw: String?,
    val chip: String?,
)

/** `GET /status` response — PROTOCOL.md section 3, same state machine as BLE section 2. */
data class SoftApStatus(
    val status: Int,
    val err: Int,
    val minerKey: String?,
    val ip: String?,
)

sealed class SoftApEvent {
    data class Info(val info: SoftApInfo) : SoftApEvent()
    data class StatusUpdate(val status: SoftApStatus) : SoftApEvent()
    data object ProvisionAccepted : SoftApEvent()
    data class Failed(val reason: String) : SoftApEvent()
}

private val SOFTAP_SSID_REGEX = Regex("^FRY-SETUP-[0-9A-F]{6}$")
private const val SOFTAP_IP = "192.168.4.1"
private const val JOIN_TIMEOUT_MS = 30_000
private const val STATUS_POLL_INTERVAL_MS = 2_000L
private const val STATUS_POLL_TIMEOUT_MS = 60_000L
private const val PROV_STATUS_CONNECTED = 3
private const val PROV_STATUS_ERROR = 4

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
    private val context: Context,
) {
    @SuppressLint("MissingPermission")
    fun scanForSoftApSsids(): Flow<String> = callbackFlow {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            close(IllegalStateException("WifiManager unavailable"))
            return@callbackFlow
        }

        val seen = HashSet<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                wifiManager.scanResults
                    .mapNotNull { it.SSID }
                    .filter { SOFTAP_SSID_REGEX.matches(it) && seen.add(it) }
                    .forEach { trySend(it) }
            }
        }
        context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

        @Suppress("DEPRECATION")
        wifiManager.startScan()

        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    @SuppressLint("MissingPermission")
    fun provision(apSsid: String, wifiSsid: String, wifiPass: String, wallet: String): Flow<SoftApEvent> =
        callbackFlow {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val specifier = WifiNetworkSpecifier.Builder().setSsid(apSsid).build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            var settled = false
            val networkDeferred = CompletableDeferred<Network?>()
            val callback = object : ConnectivityManager.NetworkCallback() {
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

            val network = withTimeoutOrNull(JOIN_TIMEOUT_MS.toLong() + 1_000) { networkDeferred.await() }
            if (network == null) {
                trySend(SoftApEvent.Failed("Failed to join $apSsid"))
                close()
                awaitClose { runCatching { connectivityManager.unregisterNetworkCallback(callback) } }
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
                awaitClose { runCatching { connectivityManager.unregisterNetworkCallback(callback) } }
                return@callbackFlow
            }
            trySend(SoftApEvent.Info(info))

            try {
                val body = FormBody.Builder()
                    .add("ssid", wifiSsid)
                    .add("pass", wifiPass)
                    .add("wallet", wallet)
                    .build()
                val req = Request.Builder().url("http://$SOFTAP_IP/provision").post(body).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("POST /provision failed: HTTP ${resp.code}")
                }
            } catch (e: Exception) {
                trySend(SoftApEvent.Failed("POST /provision failed: ${e.message}"))
                close()
                awaitClose { runCatching { connectivityManager.unregisterNetworkCallback(callback) } }
                return@callbackFlow
            }
            trySend(SoftApEvent.ProvisionAccepted)

            val polledOk = withTimeoutOrNull(STATUS_POLL_TIMEOUT_MS) {
                var reachedTerminal = false
                while (!reachedTerminal) {
                    val status = try {
                        val req = Request.Builder().url("http://$SOFTAP_IP/status").build()
                        client.newCall(req).execute().use { resp ->
                            gson.fromJson(resp.body?.string(), SoftApStatus::class.java)
                        }
                    } catch (e: Exception) {
                        null
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
            if (polledOk != true) {
                trySend(SoftApEvent.Failed("Status polling timed out"))
            }

            close()
            awaitClose { runCatching { connectivityManager.unregisterNetworkCallback(callback) } }
        }
}
