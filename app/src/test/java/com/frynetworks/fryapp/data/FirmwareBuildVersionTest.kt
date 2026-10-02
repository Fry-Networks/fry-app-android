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
    fun `an ESP8266 on 0_3_3 is offered its own 0_3_4, never the top-level 0_4_1`() = runTest {
        assertEquals(UpdateCheckResult.Available("0.3.4", "https://example.invalid/fw-v0.3.4/firmware-esp8266.bin"), check("ESP8266", "0.3.3"))
        assertEquals(UpdateCheckResult.UpToDate, check("ESP8266", "0.3.4"))
    }

    @Test
    fun `builds without their own version use the top-level version`() = runTest {
        assertEquals(UpdateCheckResult.Available("0.4.1", "https://example.invalid/fw-v0.4.1/firmware-esp32.bin"), check("ESP32", "0.4.0"))
        assertEquals(UpdateCheckResult.UpToDate, check("ESP32-C3", "0.4.1"))
    }

    /** The live fw-v0.4.1 manifest.json (2026-10-02), verbatim: no per-build versions, nested factory images. */
    private val liveManifestJson = """{
  "builds": {
    "esp32": {
      "factory": {
        "sha256": "55ba9d8df4b57e0ae36bf8ec2dba9e895a2c7edf96c87a616da3fade74d46eab",
        "url": "https://github.com/Fry-Networks/fry-firmware/releases/download/fw-v0.4.1/firmware-esp32-factory.bin"
      },
      "sha256": "950e171fce12171ec39c222216a3228afb58c094653677bdff9f7911001cf755",
      "url": "https://github.com/Fry-Networks/fry-firmware/releases/download/fw-v0.4.1/firmware-esp32.bin"
    },
    "esp32c3": {
      "factory": {
        "sha256": "0a56c086f3d674af0bd66a04e74b391a6547c57b156132c76ab541c05693570b",
        "url": "https://github.com/Fry-Networks/fry-firmware/releases/download/fw-v0.4.1/firmware-esp32c3-factory.bin"
      },
      "sha256": "d0a7139cea0b81213aef7375963092c4521ed82e6b2fddabb27edf409d9d09a3",
      "url": "https://github.com/Fry-Networks/fry-firmware/releases/download/fw-v0.4.1/firmware-esp32c3.bin"
    }
  },
  "channel": "prod",
  "firmware_version": "0.4.1"
}"""

    private suspend fun checkLive(chip: String, fw: String): UpdateCheckResult {
        val manifest = Gson().fromJson(liveManifestJson, OtaManifest::class.java)
        val client = mockk<OtaManifestClient> { coEvery { fetchManifest() } returns manifest }
        return DeviceRepository(mockk(relaxed = true), client)
            .checkForUpdate(Device("FEM-TESTKEY0000000000000000000000001", "FRY-$chip", chip, fw, "", Transport.BLE, 0L, 3))
    }

    @Test
    fun `an ESP32 on the top-level version with no per-build version is up to date`() = runTest {
        assertEquals(UpdateCheckResult.UpToDate, check("ESP32", "0.4.1"))
    }

    @Test
    fun `the live manifest without per-build versions behaves as before`() = runTest {
        val manifest = Gson().fromJson(liveManifestJson, OtaManifest::class.java)
        assertEquals(null, manifest.builds["esp32"]?.version)
        assertEquals(UpdateCheckResult.UpToDate, checkLive("ESP32", "0.4.1"))
        assertEquals(UpdateCheckResult.UpToDate, checkLive("ESP32-C3", "0.4.1"))
        assertEquals(
            UpdateCheckResult.Available("0.4.1", "https://github.com/Fry-Networks/fry-firmware/releases/download/fw-v0.4.1/firmware-esp32.bin"),
            checkLive("ESP32", "0.4.0"),
        )
        assertEquals(UpdateCheckResult.Unknown, checkLive("ESP8266", "0.3.3"))
        assertEquals(UpdateCheckResult.Unknown, checkLive("ESP32-S3", "0.3.3"))
    }
}
