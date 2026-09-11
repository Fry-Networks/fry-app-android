package com.frynetworks.fryapp.data.dashboard.model

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

/** `POST /api/price/get {asset_ids}` response — USD price per asset id (as a string key). */
data class PriceResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("prices") val prices: Map<String, BigDecimal> = emptyMap(),
)
