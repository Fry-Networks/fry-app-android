package com.frynetworks.fryapp.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Algorand Standard Assets the dashboard pays rewards and accepts stakes in
 * (lib/utils.ts FRY_1/FRY_2/FRY_3/fNODE/tFRY/fVPN). All six use 6 decimal places.
 */
enum class FryAsset(val id: Long, val symbol: String, val decimals: Int = 6) {
    TFRY(2681521901L, "tFRY"),
    FNODE(2485202024L, "fNODE"),
    FRY2(2485314946L, "FRY 2.0"),
    FRY1(924268058L, "FRY 1.0"),
    FRY3(3612979527L, "FRY 3.0"),
    FVPN(2485198745L, "fVPN"),
    ;

    companion object {
        fun fromId(id: Long): FryAsset? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A whole-µunit token amount (the on-chain integer unit for a 6-decimal ASA). Kept as a single
 * [Long] so it stays a zero-overhead [JvmInline] wrapper; convert to/from display units (the
 * `12.34`-shaped numbers the dashboard's JSON API sends) via [fromDisplay]/[toDisplay] — never
 * through [Double], which cannot represent these values exactly.
 */
@JvmInline
value class MicroAmount(val micro: Long) {

    /** Display-unit value (e.g. `12.340000` for 6 decimals), exact via [BigDecimal]. */
    fun toDisplay(decimals: Int = 6): BigDecimal = BigDecimal(micro).movePointLeft(decimals)

    /**
     * Locale-formatted display string: thousands grouping, truncated (never rounded up) to at
     * most [maxFraction] fractional digits, with trailing zeros trimmed.
     */
    fun format(maxFraction: Int = 2, locale: Locale = Locale.US, decimals: Int = 6): String {
        val display = toDisplay(decimals)
        val pattern = buildString {
            append("#,##0")
            if (maxFraction > 0) {
                append('.')
                repeat(maxFraction) { append('#') }
            }
        }
        val formatter = DecimalFormat(pattern, DecimalFormatSymbols(locale))
        formatter.roundingMode = RoundingMode.DOWN
        return formatter.format(display)
    }

    companion object {
        /** Converts a display-unit amount (e.g. `BigDecimal("12.34")`) into whole µunits. */
        fun fromDisplay(display: BigDecimal, decimals: Int = 6): MicroAmount =
            MicroAmount(display.movePointRight(decimals).setScale(0, RoundingMode.HALF_UP).longValueExact())

        /**
         * Parses a dashboard JSON amount field, which arrives as a display-unit number
         * (e.g. `"amount": 12.34`), into [MicroAmount]. A `null` field (absent/omitted) is
         * treated as zero. Callers must have already parsed the JSON number into [BigDecimal]
         * (never [Double] — see [com.frynetworks.fryapp.data.dashboard.model.DashboardGson]).
         */
        fun fromDashboardAmount(amount: BigDecimal?, decimals: Int = 6): MicroAmount =
            fromDisplay(amount ?: BigDecimal.ZERO, decimals)
    }
}
