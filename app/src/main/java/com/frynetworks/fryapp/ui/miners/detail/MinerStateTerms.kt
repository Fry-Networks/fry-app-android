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
        false -> "Not earning: " + (
            detail.rewardBlockMessage?.takeIf { it.isNotBlank() }
                ?: detail.rewardBlockReason?.let { reasonWords(it) }
                ?: "the dashboard gives no reason"
            )
        null -> "Not reported by the dashboard"
    }

    /** C-4 `reward_block_reason` codes in words; an unknown code is shown as sent. */
    fun reasonWords(code: String): String = when (code) {
        "update_required" -> "a firmware update is required"
        "no_recent_heartbeat" -> "no heartbeat in the last 24 hours"
        "no_poc_data" -> "no proof-of-coverage data yet"
        "platform_not_enabled" -> "rewards are not enabled for this board type yet"
        "no_software_report" -> "the board has not reported its software version"
        "no_slot_proofs" -> "no slot proofs recorded"
        "integration_required" -> "a required integration is not active"
        "not_registered" -> "the board is not registered"
        "no_reward_wallet" -> "no reward wallet is set"
        "ineligible" -> "the dashboard marks it ineligible without a specific gate"
        else -> code
    }
}
