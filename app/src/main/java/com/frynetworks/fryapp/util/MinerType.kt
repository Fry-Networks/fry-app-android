package com.frynetworks.fryapp.util

import com.frynetworks.fryapp.data.Device

/**
 * Miner-type family a device belongs to, derived from its miner-key prefix (PROTOCOL.md
 * section 4 and 11.1). The ESP8266/ESP32 boards this app provisions (miner code IOTVPN, the dVPN
 * line) carried `IOT-...` keys up to firmware 0.3.1; from 0.3.2 they hold `FEM-...` keys (legacy
 * `IOT-` keys are rewritten on boot and no longer accepted), the same prefix as FEM (Fry Edge
 * Miner) hardware enrolled through other Fry Networks tools into this shared device store. Anything
 * that is not `FEM-` is treated as IOTVPN so an unrecognised prefix still renders instead of crashing.
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
