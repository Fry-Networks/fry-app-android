package com.frynetworks.fryapp.data.dashboard.model

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

/** `POST /api/rewards/get-asset-totals` response (blueprint 5.2 Miners totals header). */
data class AssetTotalsResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("totals") val totals: AssetTotalsBuckets? = null,
    @SerializedName("nextUnlockAt") val nextUnlockAt: String? = null,
    @SerializedName("nextClaimableAt") val nextClaimableAt: String? = null,
    @SerializedName("pendingWindowLabel") val pendingWindowLabel: String? = null,
    @SerializedName("legacyFryClaimedSnapshot") val legacyFryClaimedSnapshot: BigDecimal? = null,
    @SerializedName("serverTime") val serverTime: Long? = null,
)

data class AssetTotalsBuckets(
    @SerializedName("fnode") val fnode: RewardBucket? = null,
    @SerializedName("tfry") val tfry: RewardBucket? = null,
)

/** pending/claimable/claimed/accruing amounts for one asset bucket, in display units. */
data class RewardBucket(
    @SerializedName("pending") val pending: BigDecimal? = null,
    @SerializedName("claimable") val claimable: BigDecimal? = null,
    @SerializedName("claimed") val claimed: BigDecimal? = null,
    @SerializedName("accruing") val accruing: BigDecimal? = null,
)
