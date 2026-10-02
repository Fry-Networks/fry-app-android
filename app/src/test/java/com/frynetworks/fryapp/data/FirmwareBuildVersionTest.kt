package com.frynetworks.fryapp.data

import com.frynetworks.fryapp.api.OtaManifest
import com.frynetworks.fryapp.api.OtaManifestClient
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R4 (IV5): the firmware manifest keeps a top-level `firmware_version` (ESP32/C3) and may give a
 * build its own `version` (ESP8266 0.3.4, with board_id / flash_size). A build's own version wins;
 * the top level applies only when the build has none. Otherwise an ESP8266 on 0.3.3 would be told
 * the ESP32 release "0.4.1" is available.
 */
class FirmwareBuildVersionTest {

    private val manifestJson = """{
        "builds": {
          "esp32": {"sha256": "${"a".repeat(64)}", "url": "https://example.invalid/fw-v0.4.1/firmware-esp32.bin"},
          "esp32c3": {"sha256": "${"b".repeat(64)}", "url": "https://example.invalid/fw-v0.4.1/firmware-esp32c3.bin"},
          "esp8266": {"version": "0.3.4", "board_id": "esp8266-4mb", "flash_size": "4MB",
                      "sha256": "${"c".repeat(64)}", "url": "https://example.invalid/fw-v0.3.4/firmware-esp8266.bin"}
        },
        "channel": "prod",
        "firmware_version": "0.4.1"
    }"""

    private suspend fun check(chip: String, fw: String): UpdateCheckResult {
        val manifest = Gson().fromJson(manifestJson, OtaManifest::class.java)
        val client = mockk<OtaManifestClient> { coEvery { fetchManifest() } returns manifest }
        val repo = DeviceRepository(mockk(relaxed = true), client)
        return repo.checkForUpdate(Device("FEM-TESTKEY0000000000000000000000001", "FRY-$chip", chip, fw, "", Transport.BLE, 0L, 3))
    }

    @Test
    fun `the manifest's per-build version parses`() {
        val manifest = Gson().fromJson(manifestJson, OtaManifest::class.java)
        assertEquals("0.3.4", manifest.builds["esp8266"]?.version)
        assertEquals(null, manifest.builds["esp32"]?.version)
    }

    @Test
    fun `an ESP8266 on 0.3.3 is offered its own 0_3_4, never the top-level 0_4_1`() = runTest {
        assertEquals(UpdateCheckResult.Available("0.3.4", "https://example.invalid/fw-v0.3.4/firmware-esp8266.bin"), check("ESP8266", "0.3.3"))
        assertEquals(UpdateCheckResult.UpToDate, check("ESP8266", "0.3.4"))
    }

    @Test
    fun `builds without their own version use the top-level version`() = runTest {
        assertEquals(UpdateCheckResult.Available("0.4.1", "https://example.invalid/fw-v0.4.1/firmware-esp32.bin"), check("ESP32", "0.4.0"))
        assertEquals(UpdateCheckResult.UpToDate, check("ESP32-C3", "0.4.1"))
    }
}
