package com.frynetworks.fryapp.data.dashboard.model

import com.google.gson.annotations.SerializedName

/** `POST /api/hardware/status` response, keyed by `miner_key`. */
data class HardwareStatusResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("statuses") val statuses: Map<String, HardwareStatusEntry> = emptyMap(),
)

/** One entry's hardware-verification result (blueprint 5.3 `miner_hw_status`). */
data class HardwareStatusEntry(
    @SerializedName("linked") val linked: Boolean? = null,
    @SerializedName("valid") val valid: Boolean? = null,
    @SerializedName("mac_match") val macMatch: Boolean? = null,
    @SerializedName("reason") val reason: String? = null,
)
