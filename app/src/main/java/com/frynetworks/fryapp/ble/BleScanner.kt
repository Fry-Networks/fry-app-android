package com.frynetworks.fryapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One BLE advertisement matching the Fry service UUID. */
data class BleFoundDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
)

/**
 * Scans for Fry boards advertising [FryGattContract.SERVICE_FRY]. Per PROTOCOL.md section 1
 * the device name lives in the scan response, not the advertisement, so an active scan
 * (Android's default) is required for [ScanRecord.getDeviceName] to resolve.
 */
@Singleton
class BleScanner @Inject constructor(
    private val context: Context,
) {
    @SuppressLint("MissingPermission")
    fun scan(): Flow<BleFoundDevice> = callbackFlow {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth LE scanning unavailable"))
            return@callbackFlow
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(FryGattContract.SERVICE_FRY))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(
                    BleFoundDevice(
                        name = result.scanRecord?.deviceName,
                        address = result.device.address,
                        rssi = result.rssi,
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed: errorCode=$errorCode"))
            }
        }

        scanner.startScan(listOf(filter), settings, callback)

        val timeoutJob = launch {
            delay(SCAN_WINDOW_MS)
            close()
        }

        awaitClose {
            timeoutJob.cancel()
            scanner.stopScan(callback)
        }
    }

    companion object {
        const val SCAN_WINDOW_MS = 15_000L
    }
}
