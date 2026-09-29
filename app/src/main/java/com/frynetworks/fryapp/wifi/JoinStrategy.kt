package com.frynetworks.fryapp.wifi

import android.os.Build

/** How the phone joins a board's setup AP, decided once before any system call. */
sealed interface JoinPlan {
    /** Below Android 10 there is no per-app network request; the user gets [SOFTAP_NEEDS_ANDROID_10]. */
    data object NeedsAndroid10 : JoinPlan

    /** A [android.net.wifi.WifiNetworkSpecifier] for [ssid]: WPA2 with [wpa2Passphrase], open without one. */
    data class Specifier(val ssid: String, val wpa2Passphrase: String?) : JoinPlan
}

/**
 * The only join path this app has: a WifiNetworkSpecifier request on API 29+, open for a keyed
 * board's AP and WPA2 with the setup code for a keyless v1.1 board's AP. There is no network
 * suggestion or STA+STA fallback; older phones are pointed at the USB web setup instead.
 */
object JoinStrategy {
    const val MIN_SDK = Build.VERSION_CODES.Q

    fun decide(sdkInt: Int, apSsid: String, setupCode: String?): JoinPlan =
        if (sdkInt < MIN_SDK) JoinPlan.NeedsAndroid10 else JoinPlan.Specifier(apSsid, setupCode?.takeIf { it.isNotBlank() })
}
