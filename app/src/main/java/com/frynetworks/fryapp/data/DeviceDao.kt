package com.frynetworks.fryapp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<Device>>

    /** One-shot read of the same rows [observeAll] streams, for pull-to-refresh: the Flow is
     *  already live, so this exists to give the gesture a real, observable re-read to perform
     *  rather than a fake spinner over data that never changed. */
    @Query("SELECT * FROM devices ORDER BY lastSeen DESC")
    suspend fun getAllOnce(): List<Device>

    @Upsert
    suspend fun upsert(device: Device)

    @Delete
    suspend fun delete(device: Device)

    @Query("DELETE FROM devices WHERE minerKey = :minerKey")
    suspend fun deleteByMinerKey(minerKey: String)
}
