package com.frynetworks.fryapp.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.ble.BleScanner
import com.frynetworks.fryapp.data.Transport
import com.frynetworks.fryapp.wifi.WifiProvisioner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScanUiDevice(
    val label: String,
    val address: String,
    val transport: String,
    val rssi: Int? = null,
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val bleScanner: BleScanner,
    private val wifiProvisioner: WifiProvisioner,
) : ViewModel() {

    private val _results = MutableStateFlow<List<ScanUiDevice>>(emptyList())
    val results = _results.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    /** Non-null when the last scan attempt failed, already phrased for a user. */
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** False until a scan has actually run, so the UI can tell "not tried yet" from "found nothing". */
    private val _hasScanned = MutableStateFlow(false)
    val hasScanned = _hasScanned.asStateFlow()

    private var scanJob: Job? = null

    /** Called when the runtime permission prompt comes back with anything denied. */
    fun onPermissionsDenied(denied: List<String>) {
        _error.value = ScanErrorCopy.forMissingPermissions(denied)
    }

    fun startScan() {
        if (_scanning.value) return
        _results.value = emptyList()
        _error.value = null
        _scanning.value = true

        scanJob = viewModelScope.launch {
            val bleJob = launch {
                bleScanner.scan()
                    // Surfacing this is the whole point: a swallowed failure is indistinguishable
                    // from "no devices nearby", and sent users hunting for a hardware fault that
                    // was really Bluetooth being off or Location being denied.
                    .catch { t -> _error.value = ScanErrorCopy.forThrowable(t) }
                    .collect { found ->
                        addResult(
                            ScanUiDevice(
                                label = found.name ?: found.address,
                                address = found.address,
                                transport = Transport.BLE,
                                rssi = found.rssi,
                            )
                        )
                    }
            }
            val wifiJob = launch {
                // SoftAP is the secondary transport (ESP8266 only). A failure here is not worth
                // overwriting a BLE error with, since BLE is what the great majority of boards use.
                wifiProvisioner.scanForSoftApSsids()
                    .catch { t ->
                        if (_error.value == null && _results.value.isEmpty()) {
                            _error.value = ScanErrorCopy.forThrowable(t)
                        }
                    }
                    .collect { ssid ->
                        addResult(ScanUiDevice(label = ssid, address = ssid, transport = Transport.SOFTAP))
                    }
            }
            joinAll(bleJob, wifiJob)
            _scanning.value = false
            _hasScanned.value = true
        }
    }

    private fun addResult(device: ScanUiDevice) {
        val current = _results.value
        if (current.none { it.address == device.address && it.transport == device.transport }) {
            _results.value = current + device
        }
    }

    override fun onCleared() {
        scanJob?.cancel()
    }
}
