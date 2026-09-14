package com.frynetworks.fryapp.ui.scan

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every one of these failures previously produced an empty list and no message at all, which is
 * why users reported the app "not seeing" a board that was advertising perfectly well.
 */
class ScanErrorCopyTest {

    private fun assertActionable(text: String) {
        // An actionable message tells the user what to DO, not merely that something failed.
        assertTrue(
            "not actionable: $text",
            text.contains("Turn ", ignoreCase = true) ||
                text.contains("Wait", ignoreCase = true) ||
                text.contains("Allow", ignoreCase = true) ||
                text.contains("Restart", ignoreCase = true) ||
                text.contains("Close ", ignoreCase = true) ||
                text.contains("try again", ignoreCase = true) ||
                text.contains("does not support", ignoreCase = true),
        )
    }

    @Test
    fun `bluetooth off is reported as bluetooth off, not a generic failure`() {
        val text = ScanErrorCopy.forThrowable(IllegalStateException("Bluetooth LE scanning unavailable"))

        assertTrue(text, text.contains("Bluetooth", ignoreCase = true))
        assertActionable(text)
        assertNotEquals("Scan failed.", text)
    }

    @Test
    fun `rate limiting tells the user to wait rather than retry immediately`() {
        val text = ScanErrorCopy.forScanFailure(ScanErrorCopy.SCAN_FAILED_SCANNING_TOO_FREQUENTLY)

        assertTrue(text, text.contains("Wait", ignoreCase = true))
        assertActionable(text)
    }

    @Test
    fun `unsupported hardware says so instead of suggesting a retry`() {
        val text = ScanErrorCopy.forScanFailure(ScanErrorCopy.SCAN_FAILED_FEATURE_UNSUPPORTED)

        assertTrue(text, text.contains("does not support", ignoreCase = true))
    }

    @Test
    fun `every known scan error code maps to its own distinct message`() {
        val codes = listOf(
            ScanErrorCopy.SCAN_FAILED_ALREADY_STARTED,
            ScanErrorCopy.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED,
            ScanErrorCopy.SCAN_FAILED_INTERNAL_ERROR,
            ScanErrorCopy.SCAN_FAILED_FEATURE_UNSUPPORTED,
            ScanErrorCopy.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES,
            ScanErrorCopy.SCAN_FAILED_SCANNING_TOO_FREQUENTLY,
        )

        val messages = codes.map { ScanErrorCopy.forScanFailure(it) }

        assertEqualsSize(codes.size, messages.toSet().size)
        messages.forEach { assertNotEquals("Scan failed.", it) }
    }

    @Test
    fun `an unknown scan error code still carries the code so it can be reported`() {
        val text = ScanErrorCopy.forScanFailure(99)

        assertTrue(text, text.contains("99"))
    }

    @Test
    fun `a scan failure thrown by the scanner is decoded, not shown raw`() {
        // BleScanner wraps onScanFailed as IllegalStateException("BLE scan failed: errorCode=6").
        val text = ScanErrorCopy.forThrowable(IllegalStateException("BLE scan failed: errorCode=6"))

        assertTrue(text, text.contains("Wait", ignoreCase = true))
        assertTrue("raw errorCode leaked to the user: $text", !text.contains("errorCode"))
    }

    @Test
    fun `denied location permission explains why a scan needs it on Android 12 and up`() {
        val text = ScanErrorCopy.forMissingPermissions(listOf("android.permission.ACCESS_FINE_LOCATION"))

        assertTrue(text, text.contains("Location", ignoreCase = true))
        assertActionable(text)
    }

    @Test
    fun `denied bluetooth permission names bluetooth specifically`() {
        val text = ScanErrorCopy.forMissingPermissions(listOf("android.permission.BLUETOOTH_SCAN"))

        assertTrue(text, text.contains("Bluetooth", ignoreCase = true))
        assertActionable(text)
    }

    private fun assertEqualsSize(expected: Int, actual: Int) {
        assertTrue("expected $expected distinct messages, got $actual", expected == actual)
    }
}
