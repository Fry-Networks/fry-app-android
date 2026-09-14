package com.frynetworks.fryapp.ui.scan

/**
 * Turns a BLE scan failure into something a user can act on.
 *
 * Scan failures used to be swallowed entirely, so a phone with Bluetooth switched off, or with
 * Location denied on Android 12+, or being rate-limited by the platform, all looked identical:
 * a button that appeared to work and a list that stayed empty. Reported on Discord 2026-09-12 as
 * "The Android app does not see my ESP32 board".
 *
 * Pure string mapping, so the JVM unit suite covers every branch.
 */
object ScanErrorCopy {

    // android.bluetooth.le.ScanCallback error codes. Duplicated as plain constants so this object
    // stays free of Android dependencies and therefore unit-testable.
    const val SCAN_FAILED_ALREADY_STARTED = 1
    const val SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2
    const val SCAN_FAILED_INTERNAL_ERROR = 3
    const val SCAN_FAILED_FEATURE_UNSUPPORTED = 4
    const val SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES = 5
    const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6

    private val ERROR_CODE_IN_MESSAGE = Regex("""errorCode=(\d+)""")

    fun forScanFailure(errorCode: Int): String = when (errorCode) {
        SCAN_FAILED_ALREADY_STARTED ->
            "A Bluetooth scan is already running. Wait a few seconds and try again."

        SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "Android would not register the Bluetooth scan. Restart the app and try again."

        SCAN_FAILED_INTERNAL_ERROR ->
            "Android's Bluetooth stack reported an internal error. Turn Bluetooth off and on again, " +
                "then try again."

        SCAN_FAILED_FEATURE_UNSUPPORTED ->
            "This phone does not support Bluetooth LE scanning, which is required to set up a Fry device."

        SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES ->
            "Bluetooth has run out of scan resources. Close other Bluetooth apps and try again."

        SCAN_FAILED_SCANNING_TOO_FREQUENTLY ->
            "Android is rate-limiting Bluetooth scans. Wait about 30 seconds and try again."

        else ->
            "Bluetooth scan failed (code $errorCode). Turn Bluetooth off and on again, then try again."
    }

    fun forThrowable(t: Throwable): String {
        val message = t.message.orEmpty()

        // BleScanner reports an absent LE scanner this way when the adapter is off or missing.
        if (message.contains("unavailable", ignoreCase = true)) {
            return "Bluetooth is off or unavailable. Turn Bluetooth on and try again."
        }

        // BleScanner wraps onScanFailed as IllegalStateException("BLE scan failed: errorCode=N").
        // Decode it rather than showing the user a raw platform constant.
        ERROR_CODE_IN_MESSAGE.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { code ->
            return forScanFailure(code)
        }

        return "Bluetooth scan failed. Turn Bluetooth off and on again, then try again."
    }

    fun forMissingPermissions(denied: List<String>): String {
        val needsLocation = denied.any { it.contains("LOCATION", ignoreCase = true) }
        val needsBluetooth = denied.any { it.contains("BLUETOOTH", ignoreCase = true) }
        val needsNearbyWifi = denied.any { it.contains("NEARBY_WIFI", ignoreCase = true) }

        return when {
            needsLocation && needsBluetooth ->
                "Allow both Bluetooth and Location to scan. Android requires Location permission for " +
                    "Bluetooth scanning even though Fry never uses your position."

            needsLocation ->
                "Allow Location to scan for devices. Android 12 and later require it for Bluetooth " +
                    "scanning even though Fry never uses your position."

            needsBluetooth ->
                "Allow Bluetooth access so the app can find nearby Fry devices, then try again."

            needsNearbyWifi ->
                "Allow nearby Wi-Fi devices so the app can find a device's setup network, then try again."

            else ->
                "Allow the requested permissions and try again."
        }
    }
}
