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

    private var scanJob: Job? = null

    fun startScan() {
        if (_scanning.value) return
        _results.value = emptyList()
        _scanning.value = true

        scanJob = viewModelScope.launch {
            val bleJob = launch {
                bleScanner.scan()
                    .catch { /* scan failures just end this branch; SoftAP results still flow */ }
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
                wifiProvisioner.scanForSoftApSsids()
                    .catch { /* see above */ }
                    .collect { ssid ->
                        addResult(ScanUiDevice(label = ssid, address = ssid, transport = Transport.SOFTAP))
                    }
            }
            joinAll(bleJob, wifiJob)
            _scanning.value = false
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
