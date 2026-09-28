package com.frynetworks.fryapp.domain

sealed interface MinerKeyInput {
    data object Empty : MinerKeyInput
    data class Valid(val key: String) : MinerKeyInput
    /** An `IOT-` key: never accepted; [suggestion] is the FEM- key with the same 32 characters. */
    data class LegacyIot(val suggestion: String) : MinerKeyInput
    data class Invalid(val reason: String) : MinerKeyInput
}

/**
 * Owner miner keys (contract C-1, PROTOCOL.md 11.1): `^FEM-[A-Za-z0-9]{32}$`, exactly 36 ASCII
 * bytes, byte-exact and case-sensitive. The dashboard mints uppercase base36, FEM PC uppercase hex,
 * and migrated boards may hold lowercase hex, so case is never changed. Input is only cleaned of
 * surrounding whitespace and of zero-width characters a copy from a chat or PDF can carry.
 */
object MinerKeyFormat {

    val OWNER_KEY = Regex("^FEM-[A-Za-z0-9]{32}$")
    private val LEGACY_IOT = Regex("^IOT-([A-Za-z0-9]{32})$")
    private val ZERO_WIDTH = Regex("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]")

    const val LEGACY_GUIDANCE = "IOT- keys are now FEM- keys: use FEM- with the same 32 characters"

    fun clean(raw: String): String = raw.replace(ZERO_WIDTH, "").trim()

    fun parse(raw: String): MinerKeyInput {
        val key = clean(raw)
        if (key.isEmpty()) return MinerKeyInput.Empty
        if (OWNER_KEY.matches(key)) return MinerKeyInput.Valid(key)
        LEGACY_IOT.matchEntire(key)?.let { return MinerKeyInput.LegacyIot("FEM-" + it.groupValues[1]) }
        return MinerKeyInput.Invalid(
            when {
                key.startsWith("fem-", ignoreCase = true) && !key.startsWith("FEM-") -> "Miner keys start with FEM- in capital letters."
                key.any { it.isWhitespace() } -> "A miner key has no spaces. Paste it again in one piece."
                key.startsWith("FEM-") && key.length != 36 -> "A miner key is FEM- followed by exactly 32 letters and digits (this one has ${key.length - 4})."
                else -> "That is not a miner key. It must be FEM- followed by 32 letters and digits."
            },
        )
    }

    /** The unauthenticated display form of PROTOCOL.md 11.1: first 6 characters + U+2026. */
    fun mask(key: String): String = key.take(6) + "…"

    /** True for the masked form a v1.1 board shows over its open AP; such a value is never a usable key. */
    fun isMasked(key: String?): Boolean = key != null && key.endsWith("…")
}
