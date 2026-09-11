package com.frynetworks.fryapp.domain

import com.frynetworks.fryapp.data.dashboard.model.Product
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/** Verification stake tier — mirrors the dashboard's `type: 'one' | 'two'` stake-note field. */
enum class StakeTier(val code: String) {
    /** Short lock (1 day). */
    ONE("one"),

    /** Long lock (180 days). */
    TWO("two"),
    ;

    companion object {
        fun fromCode(code: String): StakeTier? = entries.firstOrNull { it.code == code }
    }
}

/**
 * USD/token math for the stake sheet (blueprint 5.8, mirroring Stake.tsx). Every USD amount is a
 * product-catalog value in dollars; BYOD (bring-your-own-device) halves the amount and rounds to
 * 2 decimal places, matching the dashboard's `round((usd / 2) * 100) / 100`.
 */
object StakeMath {

    private val TWO: BigDecimal = BigDecimal(2)
    private val ONE_DAY_LOCK: Duration = Duration.ofDays(1)
    private val TWO_TIER_LOCK: Duration = Duration.ofDays(180)

    /** The registration USD amount for [product], halved and rounded to 2dp when [byod]. */
    fun registrationUsd(product: Product, byod: Boolean): BigDecimal? =
        halveIfByod(product.reward?.stake?.register, byod)

    /** The node-staking USD amount for [product], halved and rounded to 2dp when [byod]. */
    fun nodeUsd(product: Product, byod: Boolean): BigDecimal? =
        halveIfByod(product.reward?.stake?.node, byod)

    /** The verification-tier USD amount for [product]/[tier], halved and rounded to 2dp when [byod]. */
    fun verificationAmount(product: Product, tier: StakeTier, byod: Boolean): BigDecimal? {
        val amount = when (tier) {
            StakeTier.ONE -> product.reward?.stake?.stakeOne
            StakeTier.TWO -> product.reward?.stake?.stakeTwo
        }
        return halveIfByod(amount, byod)
    }

    private fun halveIfByod(usd: BigDecimal?, byod: Boolean): BigDecimal? {
        if (usd == null) return null
        if (!byod) return usd
        return usd.divide(TWO).setScale(2, RoundingMode.HALF_UP)
    }

    /**
     * `floor(usd / price)`, the whole-token count [usd] buys at [price] (both in display units).
     * Returns `null` for a non-positive [price] (division is undefined/meaningless here).
     */
    fun tokensFor(usd: BigDecimal, price: BigDecimal): Long? {
        if (price.signum() <= 0) return null
        return usd.divide(price, 10, RoundingMode.FLOOR)
            .setScale(0, RoundingMode.FLOOR)
            .longValueExact()
    }

    /** Time remaining on [tier]'s lock, clamped to [Duration.ZERO] once it has expired. */
    fun lockRemaining(stakedAt: Instant, tier: StakeTier, now: Instant): Duration {
        val lockPeriod = when (tier) {
            StakeTier.ONE -> ONE_DAY_LOCK
            StakeTier.TWO -> TWO_TIER_LOCK
        }
        val remaining = lockPeriod.minus(Duration.between(stakedAt, now))
        return if (remaining.isNegative) Duration.ZERO else remaining
    }

    /**
     * Builds the stake-transaction note in the exact key order the dashboard's server re-derives
     * and compares against (Stake.tsx note construction): `action, miner_key, asset_id, type?,
     * from, to, amount, operation, timestamp`. [type] is included only for a verification
     * operation — omit it (leave `null`) for registration/node stakes.
     */
    fun stakeNote(
        action: String,
        minerKeyShort: String,
        assetId: Long,
        type: String? = null,
        from: String,
        to: String,
        amount: Long,
        operation: String,
        timestampMillis: Long,
    ): LinkedHashMap<String, Any?> {
        val note = LinkedHashMap<String, Any?>()
        note["action"] = action
        note["miner_key"] = minerKeyShort
        note["asset_id"] = assetId
        if (type != null) note["type"] = type
        note["from"] = from
        note["to"] = to
        note["amount"] = amount
        note["operation"] = operation
        note["timestamp"] = timestampMillis
        return note
    }
}
