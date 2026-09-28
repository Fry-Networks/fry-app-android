package com.frynetworks.fryapp.ui.scan

import android.Manifest

enum class PermissionState { GRANTED, DENIED, PERMANENTLY_DENIED }

/** Everything the preflight needs, read from the phone by [readDiscoveryInputs]. */
data class DiscoveryInputs(
    val sdkInt: Int,
    val permissions: Map<String, PermissionState>,
    val bleSupported: Boolean,
    val bluetoothOn: Boolean,
    val locationOn: Boolean,
    val wifiOn: Boolean,
)

sealed interface PreflightAction {
    data class RequestPermissions(val permissions: List<String>) : PreflightAction
    data object OpenAppSettings : PreflightAction
    data object OpenBluetoothSettings : PreflightAction
    data object OpenLocationSettings : PreflightAction
    data object OpenWifiSettings : PreflightAction
    data object None : PreflightAction
}

data class PreflightIssue(val code: String, val message: String, val actionLabel: String?, val action: PreflightAction)

/** One transport's verdict; [issues] is empty exactly when it can scan. */
data class TransportPreflight(val issues: List<PreflightIssue>) {
    val ready: Boolean get() = issues.isEmpty()
}

data class DiscoveryPreflightResult(val ble: TransportPreflight, val softAp: TransportPreflight) {
    val canScan: Boolean get() = ble.ready || softAp.ready

    /** Issues across both transports, BLE first (most boards are ESP32); one permission prompt asks for both. */
    val issues: List<PreflightIssue>
        get() = (ble.issues + softAp.issues).groupBy { it.code }.map { (_, same) ->
            val permissions = same.flatMap { (it.action as? PreflightAction.RequestPermissions)?.permissions.orEmpty() }.distinct()
            if (permissions.isEmpty()) same.first() else same.first().copy(action = PreflightAction.RequestPermissions(permissions))
        }
}

/**
 * Decides, per Android version, whether this phone can discover a board over BLE (ESP32/C3/S3)
 * and over the SoftAP setup network (ESP8266), and what the user must do otherwise. Pure: the
 * JVM suite covers every SDK branch.
 *
 * - BLE, API 26–30: fine location + Location on (the platform hides BLE results otherwise).
 * - BLE, API 31+: BLUETOOTH_SCAN + BLUETOOTH_CONNECT and, because the manifest does not declare
 *   `neverForLocation`, fine ("Precise") location + Location on as well.
 * - SoftAP, API 26–28: unsupported — joining the setup network needs WifiNetworkSpecifier (API 29).
 * - SoftAP, API 29+: fine location + Location on + Wi-Fi on; API 33+ also NEARBY_WIFI_DEVICES.
 */
object DiscoveryPreflight {

    private const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
    private const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
    private const val SCAN = Manifest.permission.BLUETOOTH_SCAN
    private const val CONNECT = Manifest.permission.BLUETOOTH_CONNECT
    private const val NEARBY_WIFI = Manifest.permission.NEARBY_WIFI_DEVICES

    const val SOFTAP_MIN_SDK = 29

    fun blePermissions(sdkInt: Int): List<String> =
        if (sdkInt >= 31) listOf(SCAN, CONNECT, FINE, COARSE) else listOf(FINE, COARSE)

    fun softApPermissions(sdkInt: Int): List<String> = when {
        sdkInt < SOFTAP_MIN_SDK -> emptyList()
        sdkInt >= 33 -> listOf(FINE, COARSE, NEARBY_WIFI)
        else -> listOf(FINE, COARSE)
    }

    fun evaluate(inputs: DiscoveryInputs): DiscoveryPreflightResult =
        DiscoveryPreflightResult(ble = ble(inputs), softAp = softAp(inputs))

    private fun ble(inputs: DiscoveryInputs): TransportPreflight {
        if (!inputs.bleSupported) {
            return TransportPreflight(listOf(PreflightIssue("ble_unsupported", "This phone has no Bluetooth LE, so it cannot find ESP32 boards. Use the USB web setup instead.", null, PreflightAction.None)))
        }
        val issues = mutableListOf<PreflightIssue>()
        permissionIssue(inputs, blePermissions(inputs.sdkInt), "Bluetooth and Location")?.let { issues += it }
        if (!inputs.bluetoothOn) {
            issues += PreflightIssue("bluetooth_off", "Bluetooth is off. Turn it on to find ESP32 boards.", "Bluetooth settings", PreflightAction.OpenBluetoothSettings)
        }
        if (!inputs.locationOn) issues += locationOff()
        return TransportPreflight(issues)
    }

    private fun softAp(inputs: DiscoveryInputs): TransportPreflight {
        if (inputs.sdkInt < SOFTAP_MIN_SDK) {
            return TransportPreflight(listOf(PreflightIssue("softap_unsupported", "Setting up an ESP8266 over its Wi-Fi setup network needs Android 10 or newer. Use the USB web setup instead.", null, PreflightAction.None)))
        }
        val issues = mutableListOf<PreflightIssue>()
        permissionIssue(inputs, softApPermissions(inputs.sdkInt), "Location and Nearby devices")?.let { issues += it }
        if (!inputs.wifiOn) {
            issues += PreflightIssue("wifi_off", "Wi-Fi is off. Turn it on to find an ESP8266's FRY-SETUP network.", "Wi-Fi settings", PreflightAction.OpenWifiSettings)
        }
        if (!inputs.locationOn) issues += locationOff()
        return TransportPreflight(issues)
    }

    private fun locationOff() = PreflightIssue(
        "location_off",
        "Location is off. Android hides nearby Bluetooth and Wi-Fi devices from apps while it is off; Fry never reads your position.",
        "Location settings",
        PreflightAction.OpenLocationSettings,
    )

    private fun permissionIssue(inputs: DiscoveryInputs, required: List<String>, what: String): PreflightIssue? {
        fun state(p: String) = inputs.permissions[p] ?: PermissionState.DENIED
        val missing = required.filter { state(it) != PermissionState.GRANTED }
        if (missing.isEmpty()) return null
        if (missing.any { state(it) == PermissionState.PERMANENTLY_DENIED }) {
            return PreflightIssue(
                "permission_blocked",
                "Android will not ask again: $what access is turned off for Fry. Open App settings → Permissions and allow it.",
                "App settings",
                PreflightAction.OpenAppSettings,
            )
        }
        // Android 12+ lets the user pick "Approximate": coarse granted, fine refused. BLE scan
        // results then never arrive, so say exactly which choice to make.
        if (inputs.sdkInt >= 31 && missing == listOf(FINE)) {
            return PreflightIssue(
                "location_precise",
                "Fry was given only approximate location. Choose \"Precise\" — Android needs it to show nearby devices; Fry never reads your position.",
                "Allow precise location",
                PreflightAction.RequestPermissions(listOf(FINE, COARSE)),
            )
        }
        return PreflightIssue(
            "permission_needed",
            "Allow $what so Fry can find nearby boards. Android requires Location for this even though Fry never uses your position.",
            "Allow",
            PreflightAction.RequestPermissions(required),
        )
    }
}
