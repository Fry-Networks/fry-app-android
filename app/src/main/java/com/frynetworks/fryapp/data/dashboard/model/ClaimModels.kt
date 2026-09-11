package com.frynetworks.fryapp.data.dashboard.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

/** `POST /api/rewards/claim {preview:true}` response. */
data class ClaimPreviewResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("preview") val preview: Boolean = false,
    @SerializedName("totals") val totals: List<ClaimTotalDto> = emptyList(),
    @SerializedName("message") val message: String? = null,
)

data class ClaimTotalDto(
    @SerializedName("asset_id") val assetId: String? = null,
    @SerializedName("amount") val amount: BigDecimal? = null,
)

/**
 * `POST /api/rewards/claim` response (no `preview`). Either `mode == "user_pays"` (sign
 * [unsignedUserLeg] + confirm) or the custodial path, which carries a ready [txId] instead.
 */
data class ClaimEnvelopeResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("preview") val preview: Boolean = false,
    @SerializedName("mode") val mode: String? = null,
    @SerializedName("groupId") val groupId: String? = null,
    @SerializedName("unsignedUserLeg") val unsignedUserLeg: String? = null,
    @SerializedName("unsignedServerLegs") val unsignedServerLegs: List<String> = emptyList(),
    @SerializedName("expected") val expected: JsonElement? = null,
    @SerializedName("totals") val totals: List<ClaimTotalDto> = emptyList(),
    /** Set only on the custodial path (`REWARD_USER_PAYS_GAS` off). */
    @SerializedName("txId") val txId: String? = null,
    @SerializedName("message") val message: String? = null,
)

/** `POST /api/rewards/confirm` response — both the `{groupId,...}` and legacy `{txId}` shapes. */
data class ConfirmResultResponse(
    @SerializedName("ok") val ok: Boolean? = null,
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("txId") val txId: String? = null,
    @SerializedName("claimedAt") val claimedAt: String? = null,
)
