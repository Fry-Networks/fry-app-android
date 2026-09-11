package com.frynetworks.fryapp.data.dashboard.model

import com.google.gson.annotations.SerializedName

/** `POST /api/devices/list` response. */
data class MinerListResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("miner_keys") val minerKeys: List<MinerSummary> = emptyList(),
)

/** One row of `/api/devices/list` — server-derived flags only, no stake/reward detail. */
data class MinerSummary(
    @SerializedName("miner_key") val minerKey: String? = null,
    @SerializedName("nickname") val nickname: String? = null,
    @SerializedName("productName") val productName: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("is_registered") val isRegistered: Boolean = false,
    @SerializedName("virtual") val virtual: Boolean = false,
    @SerializedName("activated") val activated: Boolean = false,
    @SerializedName("verified") val verified: Boolean = false,
    @SerializedName("staked") val staked: Boolean = false,
    @SerializedName("legacy_stake_unlocked") val legacyStakeUnlocked: Boolean = false,
    @SerializedName("byod") val byod: Boolean = false,
    @SerializedName("node") val node: Boolean = false,
)
