package com.frynetworks.fryapp.util

import com.frynetworks.fryapp.data.Device
import org.junit.Assert.assertEquals
import org.junit.Test

class MinerTypeTest {

    private fun device(minerKey: String, lastSeen: Long = 0L) = Device(
        minerKey = minerKey,
        name = "Fry device",
        chip = "ESP32",
        fwVersion = "0.1.0",
        wallet = "",
        transport = "ble",
        lastSeen = lastSeen,
        status = 3,
    )

    // --- fromMinerKey ---

    @Test
    fun `FEM prefixed key maps to FEM`() {
        assertEquals(MinerType.FEM, MinerType.fromMinerKey("FEM-ABCDEF1234567890ABCDEF1234567890"))
    }

    @Test
    fun `IOT prefixed key maps to IOTVPN`() {
        assertEquals(MinerType.IOTVPN, MinerType.fromMinerKey("IOT-ABCDEF1234567890ABCDEF1234567890"))
    }

    @Test
    fun `unrecognised prefix falls back to IOTVPN`() {
        assertEquals(MinerType.IOTVPN, MinerType.fromMinerKey("UNKNOWN-1234"))
    }

    @Test
    fun `empty key falls back to IOTVPN`() {
        assertEquals(MinerType.IOTVPN, MinerType.fromMinerKey(""))
    }

    // --- groupDevicesByMinerType ---

    @Test
    fun `groups devices by type and preserves each device`() {
        val fem = device("FEM-1")
        val iot1 = device("IOT-1")
        val iot2 = device("IOT-2")

        val sections = groupDevicesByMinerType(listOf(fem, iot1, iot2))

        assertEquals(2, sections.size)
        assertEquals(MinerType.FEM, sections[0].minerType)
        assertEquals(listOf(fem), sections[0].devices)
        assertEquals(MinerType.IOTVPN, sections[1].minerType)
        assertEquals(listOf(iot1, iot2), sections[1].devices)
    }

    @Test
    fun `empty buckets are dropped, not emitted as empty sections`() {
        val sections = groupDevicesByMinerType(listOf(device("IOT-1")))

        assertEquals(1, sections.size)
        assertEquals(MinerType.IOTVPN, sections[0].minerType)
    }

    @Test
    fun `no devices yields no sections`() {
        assertEquals(emptyList<DeviceSection>(), groupDevicesByMinerType(emptyList()))
    }
}
