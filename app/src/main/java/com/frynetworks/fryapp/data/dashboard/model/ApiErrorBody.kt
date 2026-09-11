package com.frynetworks.fryapp.data.dashboard.model

import com.google.gson.annotations.SerializedName

/** Common dashboard error envelope, e.g. `{success:false, code:"REWARD_ON_HOLD", message, action}`. */
data class ApiErrorBody(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("code") val code: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("action") val action: String? = null,
    @SerializedName("assetId") val assetId: Long? = null,
)
