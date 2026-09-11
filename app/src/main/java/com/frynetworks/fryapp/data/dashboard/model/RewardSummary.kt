package com.frynetworks.fryapp.data.dashboard.model

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

/** `POST /api/rewards/get-reward-summary` response, for a single miner. */
data class RewardSummaryResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("summary") val summary: RewardSummary? = null,
)

/** `POST /api/rewards/get-reward-summary-batch` response, keyed by `miner_key`. */
data class RewardSummaryBatchResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("summaries") val summaries: Map<String, RewardSummary> = emptyMap(),
)

data class RewardSummary(
    @SerializedName("pending") val pending: BigDecimal? = null,
    @SerializedName("claimable") val claimable: BigDecimal? = null,
    @SerializedName("claimed") val claimed: BigDecimal? = null,
    @SerializedName("accruing") val accruing: BigDecimal? = null,
    @SerializedName("nextUnlockAt") val nextUnlockAt: String? = null,
    @SerializedName("firstRewardAt") val firstRewardAt: String? = null,
    @SerializedName("legacyFryClaimedSnapshot") val legacyFryClaimedSnapshot: BigDecimal? = null,
    @SerializedName("serverTime") val serverTime: Long? = null,
)
