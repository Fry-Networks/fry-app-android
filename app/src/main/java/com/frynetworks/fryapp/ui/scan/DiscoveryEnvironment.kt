package com.frynetworks.fryapp.ui.scan

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * Reads the phone state [DiscoveryPreflight] judges. [permanentlyDenied] holds permissions the
 * user refused with "Don't ask again" — Android only reveals that right after a request, via
 * [permanentlyDeniedAfterRequest].
 */
fun readDiscoveryInputs(context: Context, permanentlyDenied: Set<String>): DiscoveryInputs {
    val sdk = Build.VERSION.SDK_INT
    val wanted = (DiscoveryPreflight.blePermissions(sdk) + DiscoveryPreflight.softApPermissions(sdk)).distinct()
    val permissions = wanted.associateWith { p ->
        when {
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED -> PermissionState.GRANTED
            p in permanentlyDenied -> PermissionState.PERMANENTLY_DENIED
            else -> PermissionState.DENIED
        }
    }
    val location = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    return DiscoveryInputs(
        sdkInt = sdk,
        permissions = permissions,
        bleSupported = adapter != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
        bluetoothOn = runCatching { adapter?.isEnabled == true }.getOrDefault(false),
        locationOn = location != null && LocationManagerCompat.isLocationEnabled(location),
        wifiOn = runCatching { wifi?.isWifiEnabled == true }.getOrDefault(false),
    )
}

/** Denied permissions Android will not prompt for again (no rationale right after a denial). */
fun permanentlyDeniedAfterRequest(context: Context, grants: Map<String, Boolean>): Set<String> {
    val activity = context.findActivity() ?: return emptySet()
    return grants.filterValues { !it }.keys.filterTo(mutableSetOf()) { !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
}

/** Starts the settings screen a [PreflightAction] points at; false when it is not a settings action. */
fun openPreflightSettings(context: Context, action: PreflightAction): Boolean {
    val intent = when (action) {
        PreflightAction.OpenAppSettings -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        PreflightAction.OpenBluetoothSettings -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        PreflightAction.OpenLocationSettings -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        PreflightAction.OpenWifiSettings -> Intent(Settings.ACTION_WIFI_SETTINGS)
        is PreflightAction.RequestPermissions, PreflightAction.None -> return false
    }
    return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
