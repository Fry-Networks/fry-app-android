package com.frynetworks.fryapp.util

import com.frynetworks.fryapp.data.Device

/**
 * Miner-type family a device belongs to, derived from its miner-key prefix (PROTOCOL.md
 * section 4). Every miner key this app itself provisions today is `IOT-...` (miner code
 * IOTVPN — the ESP8266/ESP32 dVPN line), but `fry.db` is a shared device store: FEM
 * (Fry Edge Miner) hardware enrolled through another Fry Networks tool persists into the same
 * table with an `FEM-` prefixed key. Anything that is not `FEM-` is treated as IOTVPN so an
 * unrecognised prefix still renders on the dashboard instead of crashing it.
 */
enum class MinerType(val label: String) {
    FEM("FEM"),
    IOTVPN("IOTVPN"),
    ;

    companion object {
        private const val FEM_PREFIX = "FEM-"

        fun fromMinerKey(minerKey: String): MinerType =
            if (minerKey.startsWith(FEM_PREFIX)) FEM else IOTVPN
    }
}

/** One Home-screen section: all devices of one [MinerType], in their existing (lastSeen DESC) order. */
data class DeviceSection(val minerType: MinerType, val devices: List<Device>)

/** Buckets [devices] by [MinerType.fromMinerKey], dropping empty buckets, in enum declaration order. */
fun groupDevicesByMinerType(devices: List<Device>): List<DeviceSection> =
    MinerType.entries.mapNotNull { type ->
        devices.filter { MinerType.fromMinerKey(it.minerKey) == type }
            .takeIf { it.isNotEmpty() }
            ?.let { DeviceSection(type, it) }
    }
