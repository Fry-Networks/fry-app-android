package com.frynetworks.fryapp.data.dashboard.model

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type
import java.math.BigDecimal

/** `POST /api/devices/{miner_key}` response — a single hydrated device. */
data class DeviceDetailResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("device") val device: DeviceDetail? = null,
)

/**
 * Typed subset of the dashboard's `Device` document (dashb `lib/types.ts`), as returned by
 * `/api/devices/{miner_key}` and each entry of `/api/devices/batch`'s `devices` map. Parsed by
 * [DeviceDetailDeserializer] (registered on [DashboardGson]), which also stashes the complete
 * source object in [raw] so a caller can reach any field this subset doesn't model.
 */
data class DeviceDetail(
    val address: String? = null,
    val rewardWallet: String? = null,
    val nickname: String? = null,
    val name: String? = null,
    val minerKey: String? = null,
    val isRegistered: Boolean = false,
    val verified: Boolean = false,
    val virtual: Boolean = false,
    val activated: Boolean = false,
    /** The dashboard stores this as a license-key string, not a boolean — see `Device.byod`. */
    val byod: String? = null,
    val staked: DeviceStakeInfo? = null,
    val registration: DeviceStakeInfo? = null,
    val node: DeviceStakeInfo? = null,
    val position: DevicePosition? = null,
    val isActive: Boolean? = null,
    val rewardEligible: Boolean? = null,
    val rewardBlockReason: String? = null,
    val createdAt: String? = null,
    /** The complete source JSON object, for any field this typed subset doesn't model. */
    val raw: JsonObject? = null,
)

/**
 * Shape shared by `Device.staked`, `Device.registration` and `Device.node` (dashb
 * `lib/types.ts`). `type`/`withdrawBoost` only ever appear on `staked`; the rest are common to
 * all three.
 */
data class DeviceStakeInfo(
    @SerializedName("type") val type: String? = null,
    @SerializedName("amount") val amount: BigDecimal? = null,
    @SerializedName("time") val time: String? = null,
    @SerializedName("txId") val txId: String? = null,
    @SerializedName("asset_id") val assetId: String? = null,
    @SerializedName("withdraw_boost") val withdrawBoost: Boolean? = null,
    @SerializedName("history") val history: List<DeviceStakeHistoryEntry> = emptyList(),
    @SerializedName("lastWithdrawal") val lastWithdrawal: DeviceStakeHistoryEntry? = null,
    @SerializedName("withdrawals") val withdrawals: List<DeviceStakeHistoryEntry> = emptyList(),
)

data class DeviceStakeHistoryEntry(
    @SerializedName("amount") val amount: BigDecimal? = null,
    @SerializedName("txId") val txId: String? = null,
    @SerializedName("time") val time: String? = null,
    @SerializedName("asset_id") val assetId: String? = null,
    @SerializedName("type") val type: String? = null,
)

data class DevicePosition(
    @SerializedName("lat") val lat: Double? = null,
    @SerializedName("lng") val lng: Double? = null,
)

/**
 * Manually pulls [DeviceDetail]'s typed subset out of the source object (rather than a plain
 * reflective Gson mapping) so [DeviceDetail.raw] can also capture the complete original object —
 * Gson has no built-in "everything I didn't map" catch-all field.
 */
internal object DeviceDetailDeserializer : JsonDeserializer<DeviceDetail> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): DeviceDetail {
        val obj = json.asJsonObject

        fun string(key: String): String? = obj.get(key)?.takeIf { !it.isJsonNull }?.asString
        fun boolean(key: String): Boolean = obj.get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        fun booleanOrNull(key: String): Boolean? = obj.get(key)?.takeIf { !it.isJsonNull }?.asBoolean
        fun <T> nested(key: String, type: Class<T>): T? =
            obj.get(key)?.takeIf { !it.isJsonNull }?.let { context.deserialize(it, type) }

        return DeviceDetail(
            address = string("address"),
            rewardWallet = string("reward_wallet"),
            nickname = string("nickname"),
            name = string("name"),
            minerKey = string("miner_key"),
            isRegistered = boolean("is_registered"),
            verified = boolean("verified"),
            virtual = boolean("virtual"),
            activated = boolean("activated"),
            byod = string("byod"),
            staked = nested("staked", DeviceStakeInfo::class.java),
            registration = nested("registration", DeviceStakeInfo::class.java),
            node = nested("node", DeviceStakeInfo::class.java),
            position = nested("position", DevicePosition::class.java),
            isActive = booleanOrNull("is_active"),
            rewardEligible = booleanOrNull("reward_eligible"),
            rewardBlockReason = string("reward_block_reason"),
            createdAt = string("created_at"),
            raw = obj,
        )
    }
}
