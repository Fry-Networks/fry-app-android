package com.frynetworks.fryapp.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first `09` write triggers LESC pairing, which Android completes (with a consent dialog on
 * many phones) before it reports the write; the generic 5 s GATT window is far too short for it.
 */
class KeyWriteTimeoutTest {

    @Test
    fun `the 09 key write gets a pairing-sized window, every other write keeps the 5 s one`() {
        assertEquals(30_000L, KEY_WRITE_TIMEOUT_MS)
        assertEquals(KEY_WRITE_TIMEOUT_MS, writeTimeoutMs(FryGattContract.CHAR_MINER_KEY_WRITE))
        for (uuid in listOf(FryGattContract.CHAR_WIFI_SSID, FryGattContract.CHAR_WIFI_PASS, FryGattContract.CHAR_WALLET)) {
            assertEquals("$uuid", OP_TIMEOUT_MS, writeTimeoutMs(uuid))
        }
        assertEquals(5_000L, OP_TIMEOUT_MS)
    }

    @Test
    fun `the key write window leaves the session enough time for the Wi-Fi join and registration`() {
        assertTrue(KEY_WRITE_TIMEOUT_MS + 60_000L <= OVERALL_TIMEOUT_MS)
    }
}
