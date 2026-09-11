package com.frynetworks.fryapp.ui.miners.detail

import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import com.frynetworks.fryapp.data.dashboard.model.DeviceStakeInfo
import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.RewardSummary
import com.frynetworks.fryapp.data.dashboard.model.WithdrawableResponse
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.domain.StakeMath
import com.frynetworks.fryapp.domain.StakeTier
import com.frynetworks.fryapp.ui.common.TimeFormat
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

private const val MILLIS_PER_SECOND = 1000L

/** Which detail-screen actions are shown/enabled (blueprint 5.3 gating). */
data class MinerActionGating(
    val claim: Boolean = false,
    val stakeRegistration: Boolean = false,
    val showStakeNode: Boolean = false,
    val stakeNode: Boolean = false,
    val stakeVerification: Boolean = false,
    val showWithdraw: Boolean = false,
    val withdrawRegistration: Boolean = false,
    val withdrawNode: Boolean = false,
    val withdrawVerification: Boolean = false,
    val rename: Boolean = false,
    val rewardWallet: Boolean = false,
) {
    val anyWithdraw: Boolean get() = withdrawRegistration || withdrawNode || withdrawVerification
}

/** Stable key for a stake context (`withdrawable` map keys, dialog ids, test tags). */
fun StakeContext.key(): String = when (this) {
    StakeContext.Registration -> "registration"
    StakeContext.Node -> "node"
    is StakeContext.Verification -> "verification"
}

fun StakeContext.label(): String = when (this) {
    StakeContext.Registration -> "Registration stake"
    StakeContext.Node -> "Node stake"
    is StakeContext.Verification -> "Verification stake (tier ${tier.code})"
}

/** Pure gating rules, unit-tested without coroutines. */
object MinerActionRules {

    fun compute(
        signedIn: Boolean,
        detail: DeviceDetail?,
        summary: RewardSummary?,
        family: MinerFamily,
        isNodeFlag: Boolean,
        product: Product?,
        withdrawable: Map<String, WithdrawableResponse>,
        nowMillis: Long,
    ): MinerActionGating {
        if (!signedIn) return MinerActionGating()
        val claimable = summary?.claimable ?: BigDecimal.ZERO
        val stake = product?.reward?.stake
        val isNode = family.isNode || isNodeFlag
        val hasRegistration = detail?.registration?.amount != null
        val hasNode = detail?.node?.amount != null
        val hasVerification = detail?.staked?.amount != null
        return MinerActionGating(
            claim = claimable.signum() > 0,
            stakeRegistration = detail != null && !detail.isRegistered && stake?.register != null,
            showStakeNode = isNode,
            stakeNode = isNode && !hasNode && stake?.node != null,
            stakeVerification = detail != null && !hasVerification && (stake?.stakeOne != null || stake?.stakeTwo != null),
            showWithdraw = hasRegistration || hasNode || hasVerification,
            withdrawRegistration = hasRegistration && unlocked(withdrawable["registration"]),
            withdrawNode = hasNode && unlocked(withdrawable["node"]),
            withdrawVerification = hasVerification &&
                unlocked(withdrawable["verification"]) &&
                verificationLockRemaining(detail?.staked, nowMillis).isZero,
            rename = true,
            rewardWallet = true,
        )
    }

    private fun unlocked(w: WithdrawableResponse?): Boolean =
        w != null && w.withdrawable && (w.lockRemainingSeconds ?: 0L) <= 0L

    /** Lock left on the verification stake from `staked.time` + `staked.type` (`one` = 1 d, `two` = 180 d). */
    fun verificationLockRemaining(staked: DeviceStakeInfo?, nowMillis: Long): Duration {
        val stakedAt = TimeFormat.parseMillis(staked?.time) ?: return Duration.ZERO
        val tier = staked?.type?.let { StakeTier.fromCode(it) } ?: return Duration.ZERO
        return StakeMath.lockRemaining(Instant.ofEpochMilli(stakedAt), tier, Instant.ofEpochMilli(nowMillis))
    }

    /**
     * Epoch-millis (server clock) at which each stake unlocks, keyed by [StakeContext.key];
     * absent when the stake does not exist or is already unlocked. Registration/node come from
     * the dashboard's `withdrawable.lockRemainingSeconds`; verification from [StakeMath].
     */
    fun lockTargets(detail: DeviceDetail?, withdrawable: Map<String, WithdrawableResponse>, nowMillis: Long): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (kind in listOf("registration", "node")) {
            val seconds = withdrawable[kind]?.lockRemainingSeconds ?: continue
            if (seconds > 0) out[kind] = nowMillis + seconds * MILLIS_PER_SECOND
        }
        val verification = verificationLockRemaining(detail?.staked, nowMillis)
        if (!verification.isZero) out["verification"] = nowMillis + verification.toMillis()
        return out
    }

    /** Stake contexts that currently exist on the device (for the withdrawable fan-in). */
    fun existingStakes(detail: DeviceDetail?): List<StakeContext> {
        if (detail == null) return emptyList()
        val out = mutableListOf<StakeContext>()
        if (detail.registration?.amount != null) out += StakeContext.Registration
        if (detail.node?.amount != null) out += StakeContext.Node
        if (detail.staked?.amount != null) {
            out += StakeContext.Verification(detail.staked.type?.let { StakeTier.fromCode(it) } ?: StakeTier.ONE)
        }
        return out
    }
}
