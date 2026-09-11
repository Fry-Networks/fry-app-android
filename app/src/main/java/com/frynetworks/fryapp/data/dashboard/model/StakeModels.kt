package com.frynetworks.fryapp.data.dashboard.model

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

/** `POST /api/stake/withdrawable` response. */
data class WithdrawableResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("withdrawable") val withdrawable: Boolean = false,
    @SerializedName("amount") val amount: BigDecimal? = null,
    @SerializedName("asset_id") val assetId: String? = null,
    @SerializedName("lockRemainingSeconds") val lockRemainingSeconds: Long? = null,
)

/** `POST /api/stake/precheck` response — a 429 maps to `ApiErrorBody` instead of this shape. */
data class PrecheckResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("message") val message: String? = null,
)

/** `POST /api/stake/{registration|node-staking|verification}` response. */
data class StakeSubmitResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("txId") val txId: String? = null,
    /** The dashboard waives an already-satisfied stake instead of erroring; still a success. */
    @SerializedName("waived") val waived: Boolean = false,
)
