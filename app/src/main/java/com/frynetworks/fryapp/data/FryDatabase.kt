package com.frynetworks.fryapp.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Device::class], version = 1, exportSchema = false)
abstract class FryDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
}
