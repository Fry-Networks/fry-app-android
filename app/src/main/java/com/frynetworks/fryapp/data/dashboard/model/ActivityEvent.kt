package com.frynetworks.fryapp.data.dashboard.model

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

/** `POST /api/activity/recent` response (blueprint 5.6 Activity feed, up to 12 events). */
data class ActivityResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("events") val events: List<ActivityEvent> = emptyList(),
)

data class ActivityEvent(
    /** `"reward_unlocked" | "reward_claimed" | "registered"`. */
    @SerializedName("type") val type: String? = null,
    @SerializedName("miner_key") val minerKey: String? = null,
    @SerializedName("nickname") val nickname: String? = null,
    @SerializedName("amount") val amount: BigDecimal? = null,
    @SerializedName("asset") val asset: String? = null,
    @SerializedName("at") val at: String? = null,
)
