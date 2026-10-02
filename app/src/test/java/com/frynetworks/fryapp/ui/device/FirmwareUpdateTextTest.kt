package com.frynetworks.fryapp.ui.device

import com.frynetworks.fryapp.data.UpdateCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R4 (IV5): the device screen's update answer is about BOARD firmware. ESP32/ESP32-C3 boards
 * install it by themselves (shortly after Wi-Fi comes up and every 6 h), so there is nothing to
 * flash; ESP32-S3 and ESP8266 have no automatic update, and are never offered one.
 */
class FirmwareUpdateTextTest {

    private val available = UpdateCheckResult.Available("0.4.1", "https://example.invalid/firmware-esp32.bin")

    @Test
    fun `ESP32 and ESP32-C3 say board firmware installs by itself with nothing to flash`() {
        for (chip in listOf("ESP32", "ESP32-C3", "esp32c3")) {
            val text = FirmwareUpdateText.forResult(chip, available)
            assertTrue(text, text.startsWith("Board firmware 0.4.1 is available."))
            assertTrue(text, text.contains("install it by themselves shortly after Wi-Fi comes up and every 6 h"))
            assertTrue(text, text.contains("Nothing to flash"))
            val upToDate = FirmwareUpdateText.forResult(chip, UpdateCheckResult.UpToDate)
            assertTrue(upToDate, upToDate.startsWith("Board firmware is up to date."))
            assertTrue(upToDate, upToDate.contains("by themselves"))
            assertNull(FirmwareUpdateText.guideUrl(chip))
        }
    }

    @Test
    fun `ESP32-S3 and ESP8266 have no automatic update and are never offered one`() {
        val cases = mapOf(
            "ESP32-S3" to "https://docs.frynetworks.com/docs/esp-miners.html#s3",
            "ESP8266" to "https://docs.frynetworks.com/docs/esp-miners.html#esp8266",
        )
        for ((chip, url) in cases) {
            for (result in listOf(available, UpdateCheckResult.UpToDate, UpdateCheckResult.Unknown)) {
                val text = FirmwareUpdateText.forResult(chip, result)
                assertTrue(text, text.startsWith("No automatic firmware update for this chip ($chip)."))
                assertFalse(text, text.contains("available", ignoreCase = true))
            }
            assertEquals(url, FirmwareUpdateText.guideUrl(chip))
        }
    }

    @Test
    fun `the ESP8266 wording lives in one constant`() {
        assertEquals(FirmwareUpdateText.ESP8266_TEXT, FirmwareUpdateText.forResult("ESP8266", available))
    }

    @Test
    fun `an unknown answer or chip says so in board-firmware words`() {
        assertTrue(FirmwareUpdateText.forResult("ESP32", UpdateCheckResult.Unknown).startsWith("Board firmware update status unknown"))
        assertTrue(FirmwareUpdateText.forResult("RP2040", available).startsWith("Board firmware update status unknown"))
        assertNull(FirmwareUpdateText.guideUrl("RP2040"))
    }

    @Test
    fun `the device screen takes its update words from FirmwareUpdateText only`() {
        val src = File("src/main/java/com/frynetworks/fryapp/ui/device/DeviceDetailScreen.kt")
        assertTrue("run from the app module: ${src.absolutePath}", src.isFile)
        val screen = src.readText()
        assertTrue(screen.contains("FirmwareUpdateText.forResult("))
        assertTrue(screen.contains("FirmwareUpdateText.guideUrl("))
        for (old in listOf("\"Update available: ", "\"Firmware is up to date\"", "\"Update status unknown\"")) {
            assertFalse("inline update literal $old left", screen.contains(old))
        }
        assertTrue("the label says board firmware", screen.contains("\"Board firmware: "))
    }
}
