package com.frynetworks.fryapp.data.dashboard.model

/**
 * `RewardItem.status` values. Deserialized leniently by a [com.google.gson.JsonDeserializer]
 * registered on [DashboardGson] — an unrecognised or missing wire value maps to [UNKNOWN] rather
 * than throwing, so an unmodelled status the dashboard adds later doesn't crash parsing.
 */
enum class RewardStatus(val wireValue: String) {
    CLAIMABLE("claimable"),
    AGGREGATED("aggregated"),
    PENDING("pending"),
    ON_HOLD("onHold"),
    CLAIMED("claimed"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWireValue(value: String?): RewardStatus = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}
