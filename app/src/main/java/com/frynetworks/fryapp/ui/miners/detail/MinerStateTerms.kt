package com.frynetworks.fryapp.ui.miners.detail

import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail

/** The four device-state rows, in the dashboard's words (I9/I13, contract C-4). */
data class MinerStateRows(
    val registered: String,
    val active: String,
    val earning: String,
    val verification: String,
)

/**
 * Mirrors the dashboard's device vocabulary so the app and the dashboard never disagree:
 * - Registered: `is_registered` (the old "Active" chip meant exactly this).
 * - Active: the dashboard's own `is_active` (lib/deviceActivity.ts: recent heartbeat, lease or
 *   slot proof), never this phone's last BLE/Wi-Fi contact.
 * - Earning: `reward_eligible`, with the blocking gate in words (`reward_block_message`, else the
 *   `reward_block_reason` code translated here).
 * - Verification: the stake badge (`verified` = a verification stake is held), which is why an
 *   unstaked board reads "Unverified" on the dashboard while working normally.
 */
object MinerStateTerms {

    fun rows(detail: DeviceDetail?, registeredFallback: Boolean?, verifiedFallback: Boolean?): MinerStateRows {
        val registered = detail?.isRegistered ?: registeredFallback
        val verified = detail?.verified ?: verifiedFallback
        return MinerStateRows(
            registered = when (registered) {
                true -> "Registered"
                false -> "Not registered"
                null -> "—"
            },
            active = when (detail?.isActive) {
                true -> "Active"
                false -> "Inactive: no recent heartbeat on the dashboard"
                null -> "Not reported by the dashboard"
            },
            earning = earning(detail),
            verification = when (verified) {
                true -> "Verified (verification stake held)"
                false -> "Unverified (no verification stake)"
                null -> "—"
            },
        )
    }

    fun earning(detail: DeviceDetail?): String = when (detail?.rewardEligible) {
        true -> "Earning"
        false -> {
            // The dashboard's message already reads "Not earning: …" (R2): show it verbatim.
            val message = detail.rewardBlockMessage?.takeIf { it.isNotBlank() }
            if (message != null && message.startsWith(NOT_EARNING, ignoreCase = true)) {
                message
            } else {
                "$NOT_EARNING " + (message ?: detail.rewardBlockReason?.let { reasonWords(it, platformOf(detail)) } ?: "the dashboard gives no reason")
            }
        }
        null -> "Not reported by the dashboard"
    }

    /** The dashboard's `reward_fix_text`, verbatim, while the device is not earning. */
    fun fixText(detail: DeviceDetail?): String? =
        detail?.takeIf { it.rewardEligible == false }?.rewardFixText?.takeIf { it.isNotBlank() }

    private const val NOT_EARNING = "Not earning:"

    /** The dashboard's `device_platform` (lower-case os), which picks the words' variant. */
    private fun platformOf(detail: DeviceDetail): String? =
        detail.raw?.get("device_platform")?.takeIf { it.isJsonPrimitive }?.asString

    private val PLATFORM_LABELS = mapOf("esp32" to "ESP32", "esp32c3" to "ESP32-C3", "esp32s3" to "ESP32-S3", "esp8266" to "ESP8266")

    /**
     * C-4 `reward_block_reason` codes in words, for a dashboard that sends a code without its
     * message: the dashboard's canonical words (IV5 r2-strings.json), PC or ESP variant by
     * [platform]. An unknown code is shown as sent.
     */
    fun reasonWords(code: String, platform: String? = null): String {
        val os = platform?.trim()?.lowercase()
        val esp = os != null && os.startsWith("esp")
        val label = PLATFORM_LABELS[os] ?: platform?.trim()
        return when (code) {
            "update_required" ->
                if (esp) "this board reports an older PoC version but a newer version is required."
                else "this device reports an older PoC version but a newer version is required. Update its client or firmware."
            "no_recent_heartbeat" -> "no proof-of-connectivity heartbeat reached the server in the last 24 hours."
            "no_poc_data" -> "the server has no proof-of-connectivity report from this device yet. Power it on and connect it to the internet."
            // Pinned by MinerStateTermsTest; the dashboard adopted these words (IV5).
            "platform_not_enabled" -> "rewards are not enabled for this board type yet"
            "no_software_report" ->
                if (esp) "the server has no software version from this board yet."
                else "the device does not report its software version, so the server cannot confirm it runs a supported client. Update its firmware or client."
            "no_slot_proofs" ->
                if (esp) "$label rewards are paid per valid 10-minute slot proof, and no valid slot proofs were recorded for this board on the last scored day."
                else "no valid 10-minute proof slots were recorded on the last scored day."
            "integration_required" ->
                if (esp) "$label rewards need the board's secure link to the Fry server, and it has not connected yet."
                else "Fry Edge Miner rewards need an active integration (FryVPN or Olostep, or a partner integration), and none is active on this key."
            "not_registered" -> "this device is not registered to a wallet yet. Register it to start earning."
            "no_reward_wallet" -> "no reward wallet is set for this device. Set one to start earning."
            "ineligible" -> "the server marked this device as not reward-eligible."
            else -> code
        }
    }
}
