package com.frynetworks.fryapp.data.dashboard.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A hydrated device document (`/api/devices/batch` entry or `/api/devices/{key}`) kept as the
 * exact JSON object the dashboard sent, so `DeviceDetail.raw` survives the round trip.
 */
@Entity(tableName = "miner_detail_cache")
data class MinerDetailCacheEntity(
    @PrimaryKey val minerKey: String,
    val json: String,
    val fetchedAt: Long,
)

/** One miner's reward summary (`/api/rewards/get-reward-summary(-batch)`), verbatim JSON. */
@Entity(tableName = "reward_summary_cache")
data class RewardSummaryCacheEntity(
    @PrimaryKey val minerKey: String,
    val json: String,
    val fetchedAt: Long,
)

/** The single `/api/rewards/get-asset-totals` response for the signed-in wallet (row id is always 1). */
@Entity(tableName = "asset_totals_cache")
data class AssetTotalsCacheEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val json: String,
    val fetchedAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
