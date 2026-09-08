package com.frynetworks.fryapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** How this device was last reached — used to pick a provisioner when reconnecting. */
object Transport {
    const val BLE = "ble"
    const val SOFTAP = "softap"
}

@Entity(tableName = "devices")
data class Device(
    @PrimaryKey val minerKey: String,
    val name: String,
    val chip: String,
    val fwVersion: String,
    val wallet: String,
    val transport: String,
    val lastSeen: Long,
    val status: Int,
)
