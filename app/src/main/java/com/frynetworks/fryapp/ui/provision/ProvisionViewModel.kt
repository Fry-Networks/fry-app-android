package com.frynetworks.fryapp.ui.provision

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.ble.BleProvisioner
import com.frynetworks.fryapp.ble.ProvError
import com.frynetworks.fryapp.ble.ProvState
import com.frynetworks.fryapp.ble.ProvisionEvent
import com.frynetworks.fryapp.data.Device
import com.frynetworks.fryapp.data.DeviceRepository
import com.frynetworks.fryapp.data.SettingsRepository
import com.frynetworks.fryapp.data.Transport
import com.frynetworks.fryapp.util.AlgorandAddress
import com.frynetworks.fryapp.wifi.SoftApEvent
import com.frynetworks.fryapp.wifi.WifiProvisioner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ProvisionUiState {
    data object Idle : ProvisionUiState()
    data class InProgress(val message: String) : ProvisionUiState()
    data class Success(val minerKey: String) : ProvisionUiState()
    data class Error(val reason: String) : ProvisionUiState()
}

private const val PROV_STATUS_CONNECTED_CODE = 3
private const val PROV_STATUS_ERROR_CODE = 4

@HiltViewModel
class ProvisionViewModel @Inject constructor(
    private val bleProvisioner: BleProvisioner,
    private val wifiProvisioner: WifiProvisioner,
    private val repository: DeviceRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ProvisionUiState>(ProvisionUiState.Idle)
    val state = _state.asStateFlow()

    fun defaultWallet(): String = settingsRepository.getDefaultWallet()

    fun submit(address: String, transport: String, ssid: String, pass: String, wallet: String) {
        if (_state.value is ProvisionUiState.InProgress) return
        if (!AlgorandAddress.isValid(wallet)) {
            _state.value = ProvisionUiState.Error("Invalid Algorand wallet address")
            return
        }

        _state.value = ProvisionUiState.InProgress("Connecting...")
        viewModelScope.launch {
            var minerKey = ""
            var chip = ""
            var fwVersion = ""
            var name = ""

            suspend fun persistAndSucceed() {
                if (minerKey.isNotEmpty()) {
                    repository.upsert(
                        Device(
                            minerKey = minerKey,
                            name = name,
                            chip = chip,
                            fwVersion = fwVersion,
                            wallet = wallet,
                            transport = transport,
                            lastSeen = System.currentTimeMillis(),
                            status = PROV_STATUS_CONNECTED_CODE,
                        )
                    )
                }
                _state.value = ProvisionUiState.Success(minerKey)
            }

            if (transport == Transport.BLE) {
                bleProvisioner.provision(address, ssid, pass, wallet).collect { event ->
                    when (event) {
                        is ProvisionEvent.DeviceInfo -> {
                            minerKey = event.info.minerKey
                            chip = event.info.chip
                            fwVersion = event.info.fwVersion
                            name = event.info.name
                            _state.value = ProvisionUiState.InProgress("Connected to ${event.info.name}")
                        }
                        is ProvisionEvent.StatusUpdate -> {
                            when (event.status.state) {
                                ProvState.IDLE -> _state.value = ProvisionUiState.InProgress("Idle")
                                ProvState.PROVISIONING ->
                                    _state.value = ProvisionUiState.InProgress("Sending credentials...")
                                ProvState.CONNECTING ->
                                    _state.value = ProvisionUiState.InProgress("Joining Wi-Fi...")
                                ProvState.CONNECTED -> {
                                    persistAndSucceed()
                                    return@collect
                                }
                                ProvState.ERROR ->
                                    _state.value = ProvisionUiState.Error(errorMessage(event.status.error))
                            }
                        }
                        is ProvisionEvent.Failed -> _state.value = ProvisionUiState.Error(event.reason)
                    }
                }
            } else {
                wifiProvisioner.provision(address, ssid, pass, wallet).collect { event ->
                    when (event) {
                        is SoftApEvent.Info -> {
                            minerKey = event.info.minerKey ?: minerKey
                            chip = event.info.chip ?: chip
                            fwVersion = event.info.fw ?: fwVersion
                            name = event.info.deviceName ?: name
                            _state.value = ProvisionUiState.InProgress("Connected to ${event.info.deviceName}")
                        }
                        is SoftApEvent.ProvisionAccepted ->
                            _state.value = ProvisionUiState.InProgress("Credentials sent")
                        is SoftApEvent.StatusUpdate -> {
                            when (event.status.status) {
                                PROV_STATUS_CONNECTED_CODE -> {
                                    persistAndSucceed()
                                    return@collect
                                }
                                // Codes come straight from the device's /status JSON, so an
                                // unknown value must not throw out of viewModelScope.
                                PROV_STATUS_ERROR_CODE ->
                                    _state.value = ProvisionUiState.Error(
                                        runCatching { errorMessage(ProvError.fromCode(event.status.err)) }
                                            .getOrDefault("Device reported an error"),
                                    )
                                else ->
                                    _state.value = ProvisionUiState.InProgress(
                                        runCatching { stateLabel(ProvState.fromCode(event.status.status)) }
                                            .getOrDefault("Working…"),
                                    )
                            }
                        }
                        is SoftApEvent.Failed -> _state.value = ProvisionUiState.Error(event.reason)
                    }
                }
            }
        }
    }

    private fun stateLabel(state: ProvState): String = when (state) {
        ProvState.IDLE -> "Idle"
        ProvState.PROVISIONING -> "Sending credentials..."
        ProvState.CONNECTING -> "Joining Wi-Fi..."
        ProvState.CONNECTED -> "Connected"
        ProvState.ERROR -> "Error"
    }

    private fun errorMessage(error: ProvError): String = when (error) {
        ProvError.NONE -> "Unknown error"
        ProvError.BAD_SSID -> "Invalid Wi-Fi network name"
        ProvError.WIFI_AUTH_FAILED -> "Wi-Fi authentication failed"
        ProvError.NO_IP -> "Connected but never received an IP address"
        ProvError.HARDWAREAPI_REGISTRATION_FAILED -> "Device registration with hardwareapi failed"
        ProvError.BAD_WALLET -> "Invalid Algorand wallet address"
    }
}
