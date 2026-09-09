package com.frynetworks.fryapp.util

import com.frynetworks.fryapp.data.Device

/**
 * Neither BLE nor SoftAP provisioning keeps a persistent connection open (PROTOCOL.md sections
 * 1 and 3 — both are one-shot sessions), so this app has no live channel to a device once
 * provisioning ends. "Online" on the Home dashboard is therefore a recency heuristic over
 * [Device.lastSeen], the last time this app itself observed the device's status — not a live
 * connection state.
 */
private const val ONLINE_WINDOW_MILLIS = 15L * 60L * 1000L

fun isDeviceOnline(device: Device, nowMillis: Long = System.currentTimeMillis()): Boolean {
    val elapsed = nowMillis - device.lastSeen
    return elapsed in 0..ONLINE_WINDOW_MILLIS
}
