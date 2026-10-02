package com.frynetworks.fryapp.ui.device

import com.frynetworks.fryapp.data.UpdateCheckResult

/**
 * The device screen's answer to "Check for update", in board-firmware words (R4). ESP32 and
 * ESP32-C3 boards install published firmware by themselves (shortly after Wi-Fi comes up, then
 * every 6 h), so there is nothing to flash. ESP32-S3 and ESP8266 have no automatic update: they
 * are never offered one, whatever the manifest lists, and get the guide instead.
 */
object FirmwareUpdateText {

    private const val GUIDE = "https://docs.frynetworks.com/docs/esp-miners.html"

    /** Chips whose boards take the published firmware by themselves. */
    private val AUTO_UPDATE_CHIPS = setOf("ESP32", "ESP32-C3")

    private const val AUTO_HOW = "ESP32 and ESP32-C3 boards install it by themselves shortly after Wi-Fi comes up and every 6 h. Nothing to flash."

    const val S3_TEXT = "No automatic firmware update for this chip (ESP32-S3). This board keeps the firmware it has; the guide explains your options:"

    /** The one place the ESP8266 wording lives (flips only if an ESP8266 OTA is published). */
    const val ESP8266_TEXT = "No automatic firmware update for this chip (ESP8266). This board keeps the firmware it has; the guide explains your options:"

    fun forResult(chip: String, result: UpdateCheckResult): String {
        val key = chip.uppercase()
        manualChip(key)?.let { return it.first }
        if (key !in AUTO_UPDATE_CHIPS) return "Board firmware update status unknown for this chip."
        return when (result) {
            is UpdateCheckResult.Available -> "Board firmware ${result.latestVersion} is available. $AUTO_HOW"
            is UpdateCheckResult.UpToDate ->
                "Board firmware is up to date. ESP32 and ESP32-C3 boards install new firmware by themselves shortly after Wi-Fi comes up and every 6 h. Nothing to flash."
            is UpdateCheckResult.Unknown ->
                "Board firmware update status unknown right now. ESP32 and ESP32-C3 boards check for new firmware by themselves; nothing to flash."
        }
    }

    /** The guide section for a chip without automatic updates; null for the others. */
    fun guideUrl(chip: String): String? = manualChip(chip.uppercase())?.second

    private fun manualChip(key: String): Pair<String, String>? = when (key) {
        "ESP32-S3" -> S3_TEXT to "$GUIDE#s3"
        "ESP8266" -> ESP8266_TEXT to "$GUIDE#esp8266"
        else -> null
    }
}
