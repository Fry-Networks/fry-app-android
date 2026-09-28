package com.frynetworks.fryapp.ui.provision

import com.frynetworks.fryapp.data.keys.KeyCheckResult

/** What the key step shows for a key-check answer; the dashboard's own words come first. */
object KeyCheckCopy {

    fun summary(result: KeyCheckResult): String = when (result) {
        is KeyCheckResult.Checked -> listOfNotNull(result.message, result.bindingRule).joinToString("\n").ifBlank { fallback(result.verdict) }
        is KeyCheckResult.Unverifiable -> result.message
        is KeyCheckResult.Failed -> result.message
    }

    /** The checkbox text for an `ok_active_elsewhere` key; null when no acknowledgement is needed. */
    fun acknowledgement(result: KeyCheckResult): String? {
        if (result !is KeyCheckResult.Checked || !result.needsAcknowledgement) return null
        val consequence = when (result.consequence) {
            "stops_other" -> "Setting this board up with the key stops the other install from earning with it."
            "blocked_until_other_stops" -> "This board will wait and not earn until the other install stops using the key."
            else -> "Only one install can use a key at a time."
        }
        return "I understand: this key is active on another install. $consequence"
    }

    private fun fallback(verdict: String): String = when (verdict) {
        "ok" -> "This key is yours and free to use."
        "ok_active_elsewhere" -> "This key is yours but already active on another install."
        "not_found" -> "The dashboard has no record of this key. Check it for typos."
        "not_registered" -> "This key is not registered to a wallet yet; register it on the dashboard after setup."
        "registered_other" -> "This key is registered to another wallet, so it cannot earn for yours."
        "legacy_iot" -> "IOT- keys are now FEM- keys: use FEM- with the same 32 characters."
        else -> "That is not a valid miner key."
    }
}
