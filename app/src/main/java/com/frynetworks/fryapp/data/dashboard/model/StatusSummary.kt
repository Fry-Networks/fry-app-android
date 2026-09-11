package com.frynetworks.fryapp.data.dashboard.model

import com.google.gson.annotations.SerializedName

/** `POST /api/devices/status-summary` response — fleet online/total counts. */
data class StatusSummaryResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("total") val total: Int? = null,
    @SerializedName("online") val online: Int? = null,
)
