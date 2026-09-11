package com.frynetworks.fryapp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.frynetworks.fryapp.data.dashboard.db.AssetTotalsCacheDao
import com.frynetworks.fryapp.data.dashboard.db.AssetTotalsCacheEntity
import com.frynetworks.fryapp.data.dashboard.db.MinerDetailCacheDao
import com.frynetworks.fryapp.data.dashboard.db.MinerDetailCacheEntity
import com.frynetworks.fryapp.data.dashboard.db.RemoteMinerDao
import com.frynetworks.fryapp.data.dashboard.db.RemoteMinerEntity
import com.frynetworks.fryapp.data.dashboard.db.RewardSummaryCacheDao
import com.frynetworks.fryapp.data.dashboard.db.RewardSummaryCacheEntity

/**
 * v1: `devices` (this phone's provisioned miners). v2: + the dashboard caches (`remote_miners`,
 * `miner_detail_cache`, `reward_summary_cache`, `asset_totals_cache`) via
 * `data/dashboard/db/Migrations.kt`. Schemas export to `app/schemas` so every future bump ships
 * with a validated migration.
 */
@Database(
    entities = [
        Device::class,
        RemoteMinerEntity::class,
        MinerDetailCacheEntity::class,
        RewardSummaryCacheEntity::class,
        AssetTotalsCacheEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class FryDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun remoteMinerDao(): RemoteMinerDao
    abstract fun minerDetailCacheDao(): MinerDetailCacheDao
    abstract fun rewardSummaryCacheDao(): RewardSummaryCacheDao
    abstract fun assetTotalsCacheDao(): AssetTotalsCacheDao
}
