package com.frynetworks.fryapp.data.dashboard.model

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

/** `POST /api/rewards/get-rewards-page` response (blueprint 5.4 Rewards history, 10/page). */
data class RewardsPageResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("items") val items: List<RewardItem> = emptyList(),
    @SerializedName("totalPages") val totalPages: Int? = null,
    @SerializedName("weeklyCount") val weeklyCount: Int? = null,
    @SerializedName("dailyCount") val dailyCount: Int? = null,
    @SerializedName("totalCount") val totalCount: Int? = null,
    @SerializedName("serverTime") val serverTime: Long? = null,
)

data class RewardItem(
    @SerializedName("_id") val id: String? = null,
    @SerializedName("miner_key") val minerKey: String? = null,
    @SerializedName("no") val no: Int? = null,
    @SerializedName("status") val status: RewardStatus = RewardStatus.UNKNOWN,
    @SerializedName("asset_id") val assetId: String? = null,
    @SerializedName("amount") val amount: BigDecimal? = null,
    @SerializedName("originalAmount") val originalAmount: BigDecimal? = null,
    @SerializedName("onHold") val onHold: Boolean = false,
    @SerializedName("txId") val txId: String? = null,
    @SerializedName("createdAt") val createdAt: String? = null,
    @SerializedName("claimedAt") val claimedAt: String? = null,
    @SerializedName("isWeekly") val isWeekly: Boolean = false,
    @SerializedName("progressDays") val progressDays: Int? = null,
    @SerializedName("etaDate") val etaDate: String? = null,
    @SerializedName("weekLabel") val weekLabel: String? = null,
    @SerializedName("fiatValue") val fiatValue: BigDecimal? = null,
)
