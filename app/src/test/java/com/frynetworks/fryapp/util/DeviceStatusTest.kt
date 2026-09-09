package com.frynetworks.fryapp.util

import com.frynetworks.fryapp.data.Device
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStatusTest {

    private fun device(lastSeen: Long) = Device(
        minerKey = "IOT-1",
        name = "Fry device",
        chip = "ESP32",
        fwVersion = "0.1.0",
        wallet = "",
        transport = "ble",
        lastSeen = lastSeen,
        status = 3,
    )

    @Test
    fun `seen just now is online`() {
        assertTrue(isDeviceOnline(device(lastSeen = 1_000L), nowMillis = 1_000L))
    }

    @Test
    fun `seen 5 minutes ago is online`() {
        val now = 1_000_000L
        assertTrue(isDeviceOnline(device(lastSeen = now - 5 * 60 * 1000L), nowMillis = now))
    }

    @Test
    fun `seen over 15 minutes ago is offline`() {
        val now = 1_000_000L
        assertFalse(isDeviceOnline(device(lastSeen = now - 16 * 60 * 1000L), nowMillis = now))
    }

    @Test
    fun `a lastSeen in the future is treated as offline, not a clock-skew crash`() {
        val now = 1_000_000L
        assertFalse(isDeviceOnline(device(lastSeen = now + 1_000L), nowMillis = now))
    }
}
