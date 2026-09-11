package com.frynetworks.fryapp.data.dashboard.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Cached `/api/devices/list` rows. A refresh writes the new set with [upsertAll] and then trims
 * stale keys with [deleteNotIn] — never delete-then-insert, so observers never see an empty
 * table between the two statements.
 */
@Dao
interface RemoteMinerDao {
    @Query("SELECT * FROM remote_miners ORDER BY minerKey")
    fun observeAll(): Flow<List<RemoteMinerEntity>>

    @Query("SELECT * FROM remote_miners ORDER BY minerKey")
    suspend fun getAllOnce(): List<RemoteMinerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<RemoteMinerEntity>)

    @Query("DELETE FROM remote_miners WHERE minerKey NOT IN (:keep)")
    suspend fun deleteNotIn(keep: List<String>)

    @Query("UPDATE remote_miners SET nickname = :nickname WHERE minerKey = :minerKey")
    suspend fun updateNickname(minerKey: String, nickname: String)

    @Query("DELETE FROM remote_miners")
    suspend fun deleteAll()
}

@Dao
interface MinerDetailCacheDao {
    @Query("SELECT * FROM miner_detail_cache WHERE minerKey = :minerKey")
    fun observe(minerKey: String): Flow<MinerDetailCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MinerDetailCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MinerDetailCacheEntity>)

    @Query("DELETE FROM miner_detail_cache")
    suspend fun deleteAll()
}

@Dao
interface RewardSummaryCacheDao {
    @Query("SELECT * FROM reward_summary_cache WHERE minerKey = :minerKey")
    fun observe(minerKey: String): Flow<RewardSummaryCacheEntity?>

    @Query("SELECT * FROM reward_summary_cache")
    fun observeAll(): Flow<List<RewardSummaryCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<RewardSummaryCacheEntity>)

    @Query("DELETE FROM reward_summary_cache")
    suspend fun deleteAll()
}

@Dao
interface AssetTotalsCacheDao {
    @Query("SELECT * FROM asset_totals_cache WHERE id = 1")
    fun observe(): Flow<AssetTotalsCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: AssetTotalsCacheEntity)

    @Query("DELETE FROM asset_totals_cache")
    suspend fun deleteAll()
}
