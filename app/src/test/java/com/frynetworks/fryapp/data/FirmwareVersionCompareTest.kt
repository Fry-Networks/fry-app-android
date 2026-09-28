package com.frynetworks.fryapp.data

import com.frynetworks.fryapp.api.OtaBuild
import com.frynetworks.fryapp.api.OtaManifest
import com.frynetworks.fryapp.api.OtaManifestClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * U15: a board on 0.3.3 (Pages flasher) was told "Update available: 0.3.1" because the app
 * compared version strings for inequality. Only a strictly newer manifest version (SemVer 2.0
 * precedence, C-5) is an update; an older one is never offered as one.
 */
class FirmwareVersionCompareTest {

    private val url = "https://github.com/Fry-Networks/fry-firmware/releases/download/v0.3.1/firmware-esp32.bin"

    private suspend fun check(device: String, manifest: String): UpdateCheckResult {
        val client = mockk<OtaManifestClient> {
            coEvery { fetchManifest() } returns OtaManifest(manifest, mapOf("esp32" to OtaBuild(url, "0".repeat(64))))
        }
        val repo = DeviceRepository(mockk(relaxed = true), client)
        return repo.checkForUpdate(Device("FEM-TESTKEY0000000000000000000000001", "FRY-ESP32", "ESP32", device, "", Transport.BLE, 0L, 3))
    }

    @Test
    fun `a board newer than the published release is up to date, not offered a downgrade`() = runTest {
        assertEquals(UpdateCheckResult.UpToDate, check(device = "0.3.3", manifest = "0.3.1"))
        assertEquals(UpdateCheckResult.UpToDate, check(device = "0.10.0", manifest = "0.9.9"))
    }

    @Test
    fun `a strictly newer release is offered`() = runTest {
        assertEquals(UpdateCheckResult.Available("0.4.0", url), check(device = "0.3.3", manifest = "0.4.0"))
        assertEquals(UpdateCheckResult.Available("0.10.0", url), check(device = "0.9.9", manifest = "0.10.0"))
    }

    @Test
    fun `the same version is up to date`() = runTest {
        assertEquals(UpdateCheckResult.UpToDate, check(device = "0.3.1", manifest = "0.3.1"))
        assertEquals(UpdateCheckResult.UpToDate, check(device = "v0.3.1", manifest = "0.3.1"))
    }

    @Test
    fun `a pre-release is older than its release and newer than the one before`() = runTest {
        assertEquals(UpdateCheckResult.Available("0.4.1", url), check(device = "0.4.1-rc.1", manifest = "0.4.1"))
        assertEquals(UpdateCheckResult.UpToDate, check(device = "0.4.1", manifest = "0.4.1-rc.1"))
        assertEquals(UpdateCheckResult.Available("0.4.1-rc.1", url), check(device = "0.4.0", manifest = "0.4.1-rc.1"))
    }

    @Test
    fun `an unreadable version is never reported as an update`() = runTest {
        assertEquals(UpdateCheckResult.Unknown, check(device = "dev-build", manifest = "0.4.0"))
        assertEquals(UpdateCheckResult.Unknown, check(device = "0.4.0", manifest = "latest"))
    }
}
