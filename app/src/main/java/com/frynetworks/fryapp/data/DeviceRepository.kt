package com.frynetworks.fryapp.data

import com.frynetworks.fryapp.api.OtaManifestClient
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

sealed class UpdateCheckResult {
    data object UpToDate : UpdateCheckResult()
    data class Available(val latestVersion: String, val downloadUrl: String) : UpdateCheckResult()
    data object Unknown : UpdateCheckResult()
}

/**
 * Single source of truth for paired devices. Device status (connected/error/etc.) always
 * comes from the device itself over BLE or the SoftAP HTTP endpoint — this repository never
 * asks hardwareapi for it (PROTOCOL.md section 5 notes the server is not the source of
 * truth for install status from the app's perspective).
 */
@Singleton
class DeviceRepository @Inject constructor(
    private val dao: DeviceDao,
    private val otaManifestClient: OtaManifestClient,
) {
    fun observeDevices(): Flow<List<Device>> = dao.observeAll()

    suspend fun upsert(device: Device) = dao.upsert(device)

    suspend fun remove(minerKey: String) = dao.deleteByMinerKey(minerKey)

    suspend fun checkForUpdate(device: Device): UpdateCheckResult {
        val manifest = otaManifestClient.fetchManifest() ?: return UpdateCheckResult.Unknown
        val buildEnv = chipToBuildEnv(device.chip) ?: return UpdateCheckResult.Unknown
        val build = manifest.builds[buildEnv] ?: return UpdateCheckResult.Unknown
        return if (manifest.firmwareVersion != device.fwVersion) {
            UpdateCheckResult.Available(manifest.firmwareVersion, build.url)
        } else {
            UpdateCheckResult.UpToDate
        }
    }

    /** Maps a BLE/SoftAP-reported chip string to the OTA manifest's build-env key
     *  (PROTOCOL.md sections 1, 3 and 6). */
    private fun chipToBuildEnv(chip: String): String? = when (chip.uppercase()) {
        "ESP8266" -> "esp8266"
        "ESP32" -> "esp32"
        "ESP32-S3" -> "esp32s3"
        "ESP32-C3" -> "esp32c3"
        else -> null
    }
}
