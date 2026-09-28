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
import com.frynetworks.fryapp.data.keys.DashboardKeyChecker
import com.frynetworks.fryapp.data.keys.KeyCheckResult
import com.frynetworks.fryapp.data.keys.KeyChecker
import com.frynetworks.fryapp.domain.MinerKeyFormat
import com.frynetworks.fryapp.domain.MinerKeyInput
import com.frynetworks.fryapp.provisioning.DeviceCapabilities
import com.frynetworks.fryapp.provisioning.DashboardHandoffWatcher
import com.frynetworks.fryapp.provisioning.HandoffOutcome
import com.frynetworks.fryapp.provisioning.HandoffWatcher
import com.frynetworks.fryapp.update.InstallInhibitor
import com.frynetworks.fryapp.util.AlgorandAddress
import com.frynetworks.fryapp.wifi.SoftApEvent
import com.frynetworks.fryapp.wifi.WifiProvisioner
import com.frynetworks.fryapp.wifi.capabilities
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ProvisionUiState {
    data object Idle : ProvisionUiState()
    data class InProgress(val message: String) : ProvisionUiState()
    data class Success(val minerKey: String) : ProvisionUiState()
    data class Error(val reason: String) : ProvisionUiState()

    /** The board took the settings and left the phone to join Wi-Fi; [message] says what is known. */
    data class Handoff(val minerKey: String, val message: String) : ProvisionUiState()
}

private const val PROV_STATUS_CONNECTING_CODE = 2
private const val PROV_STATUS_CONNECTED_CODE = 3
private const val PROV_STATUS_ERROR_CODE = 4

/** Collaborators added in v0.4; the v0.3.1 wiring (and its tests) runs without them. */
class ProvisionServices(
    val handoff: HandoffWatcher? = null,
    val keyChecker: KeyChecker? = null,
    /** Held while a session runs so the app never updates itself mid-provisioning. */
    val inhibitor: InstallInhibitor? = null,
) {
    @Inject constructor(handoff: DashboardHandoffWatcher, keyChecker: DashboardKeyChecker, inhibitor: InstallInhibitor) :
        this(handoff as HandoffWatcher?, keyChecker as KeyChecker?, inhibitor as InstallInhibitor?)

    companion object {
        val NONE = ProvisionServices()
    }
}

/** The owner's miner key (C-1) and, for a keyless ESP8266, the setup code of its WPA2 AP. */
data class KeyStep(val minerKey: String?, val setupCode: String?, val acknowledgedActiveElsewhere: Boolean = false) {
    companion object {
        val NONE = KeyStep(null, null)
    }
}

sealed interface KeyCheckUi {
    data object Idle : KeyCheckUi
    data class Checking(val minerKey: String) : KeyCheckUi
    data class Done(val minerKey: String, val result: KeyCheckResult) : KeyCheckUi
}

/** A protocol-1 board kept the key it minted itself instead of the one the user entered. */
data class KeyNotice(val boardKey: String, val message: String)

@HiltViewModel
class ProvisionViewModel @Inject constructor(
    private val bleProvisioner: BleProvisioner,
    private val wifiProvisioner: WifiProvisioner,
    private val repository: DeviceRepository,
    private val settingsRepository: SettingsRepository,
    private val services: ProvisionServices,
) : ViewModel() {

    /** The v0.3.1 collaborators only (no handoff check, key check or install inhibit). */
    constructor(
        bleProvisioner: BleProvisioner,
        wifiProvisioner: WifiProvisioner,
        repository: DeviceRepository,
        settingsRepository: SettingsRepository,
    ) : this(bleProvisioner, wifiProvisioner, repository, settingsRepository, ProvisionServices.NONE)

    private val _state = MutableStateFlow<ProvisionUiState>(ProvisionUiState.Idle)
    val state = _state.asStateFlow()

    private val _keyCheck = MutableStateFlow<KeyCheckUi>(KeyCheckUi.Idle)
    val keyCheck = _keyCheck.asStateFlow()

    private val _keyNotice = MutableStateFlow<KeyNotice?>(null)
    val keyNotice = _keyNotice.asStateFlow()

    private var keyCheckJob: Job? = null

    private var holdsInhibitor = false

    init {
        services.inhibitor?.let { inhibitor ->
            viewModelScope.launch {
                state.collect { current ->
                    val busy = current is ProvisionUiState.InProgress
                    if (busy && !holdsInhibitor) inhibitor.acquire()
                    if (!busy && holdsInhibitor) inhibitor.release()
                    holdsInhibitor = busy
                }
            }
        }
    }

    override fun onCleared() {
        if (holdsInhibitor) services.inhibitor?.release()
        holdsInhibitor = false
    }

    /** True when this build can check keys against the dashboard (contract C-3). */
    val canCheckKeys: Boolean get() = services.keyChecker != null

    fun defaultWallet(): String = settingsRepository.getDefaultWallet()

    /** Asks the dashboard who owns [minerKey] (already C-1 valid); the answer gates [submit]. */
    fun checkKey(minerKey: String) {
        val checker = services.keyChecker ?: return
        keyCheckJob?.cancel()
        _keyCheck.value = KeyCheckUi.Checking(minerKey)
        keyCheckJob = viewModelScope.launch { _keyCheck.value = KeyCheckUi.Done(minerKey, checker.check(minerKey)) }
    }

    fun clearKeyCheck() {
        keyCheckJob?.cancel()
        _keyCheck.value = KeyCheckUi.Idle
    }

    fun submit(address: String, transport: String, ssid: String, pass: String, wallet: String) =
        submit(address, transport, ssid, pass, wallet, KeyStep.NONE)

    fun submit(address: String, transport: String, ssid: String, pass: String, wallet: String, keyStep: KeyStep) {
        if (_state.value is ProvisionUiState.InProgress) return
        // Never let address validation itself take the process down (it once did: the
        // SHA-512/256 provider is absent on Android). A validator failure reads as invalid.
        val walletOk = runCatching { AlgorandAddress.isValid(wallet) }.getOrDefault(false)
        if (!walletOk) {
            _state.value = ProvisionUiState.Error("Invalid Algorand wallet address")
            return
        }
        val ownerKey = when (val parsed = keyStep.minerKey?.let { MinerKeyFormat.parse(it) } ?: MinerKeyInput.Empty) {
            MinerKeyInput.Empty -> null
            is MinerKeyInput.Valid -> parsed.key
            is MinerKeyInput.LegacyIot -> {
                _state.value = ProvisionUiState.Error(MinerKeyFormat.LEGACY_GUIDANCE + ".")
                return
            }
            is MinerKeyInput.Invalid -> {
                _state.value = ProvisionUiState.Error(parsed.reason)
                return
            }
        }
        val checked = (_keyCheck.value as? KeyCheckUi.Done)?.takeIf { it.minerKey == ownerKey }?.result as? KeyCheckResult.Checked
        if (checked != null && checked.blocksSetup) {
            _state.value = ProvisionUiState.Error(checked.message ?: KEY_BLOCKED)
            return
        }
        if (checked != null && checked.needsAcknowledgement && !keyStep.acknowledgedActiveElsewhere) {
            _state.value = ProvisionUiState.Error(KEY_ACK_REQUIRED)
            return
        }
        val setupCode = keyStep.setupCode?.trim()?.takeIf { it.isNotEmpty() }
        _keyNotice.value = null

        _state.value = ProvisionUiState.InProgress("Connecting...")
        viewModelScope.launch {
            // Any throw from a provisioner flow (SecurityException, GATT/network failures,
            // parse errors) becomes an Error state; an uncaught one here kills the process.
            try {
            var minerKey = ""
            var chip = ""
            var fwVersion = ""
            var name = ""
            var caps = DeviceCapabilities.PROTO_1
            // The key the board will hold: the owner's once a v1.1 board takes it, else its own.
            fun ownerKeyTaken(): Boolean = ownerKey != null && caps.keyWrite
            fun noteIfBoardKeepsItsKey() {
                if (ownerKey != null && !caps.keyWrite && minerKey.isNotEmpty() && minerKey != ownerKey) {
                    _keyNotice.value = KeyNotice(minerKey, DEVICE_KEEPS_NOTICE)
                }
            }

            suspend fun persist(status: Int) {
                repository.upsert(
                    Device(
                        minerKey = minerKey,
                        name = name,
                        chip = chip,
                        fwVersion = fwVersion,
                        wallet = wallet,
                        transport = transport,
                        lastSeen = System.currentTimeMillis(),
                        status = status,
                    )
                )
            }

            suspend fun persistAndSucceed() {
                if (minerKey.isEmpty()) {
                    // Success with no key would navigate to "device/" and crash the nav host.
                    _state.value = ProvisionUiState.Error("Device connected but did not report a miner key")
                    return
                }
                persist(PROV_STATUS_CONNECTED_CODE)
                _state.value = ProvisionUiState.Success(minerKey)
            }

            // The board committed the settings and dropped the link to join Wi-Fi (BLE) or took
            // its setup AP down (SoftAP). Ask the backend whether it arrived.
            suspend fun handOff() {
                if (ownerKeyTaken()) minerKey = ownerKey.orEmpty()
                if (minerKey.isEmpty()) {
                    _state.value = ProvisionUiState.Error(HANDOFF_NO_KEY)
                    return
                }
                persist(PROV_STATUS_CONNECTING_CODE)
                val watcher = services.handoff
                if (watcher == null) {
                    _state.value = ProvisionUiState.Handoff(minerKey, HANDOFF_UNCONFIRMED)
                    return
                }
                _state.value = ProvisionUiState.InProgress(HANDOFF_WAITING)
                when (watcher.awaitOnline(minerKey)) {
                    HandoffOutcome.ONLINE -> persistAndSucceed()
                    HandoffOutcome.NOT_SEEN -> _state.value = ProvisionUiState.Handoff(minerKey, HANDOFF_NOT_SEEN)
                    HandoffOutcome.SIGNED_OUT -> _state.value = ProvisionUiState.Handoff(minerKey, HANDOFF_SIGNED_OUT)
                }
            }

            if (transport == Transport.BLE) {
                val flow = if (ownerKey == null) {
                    bleProvisioner.provision(address, ssid, pass, wallet)
                } else {
                    bleProvisioner.provision(address, ssid, pass, wallet, ownerKey)
                }
                flow.collect { event ->
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
                                    if (ownerKeyTaken()) {
                                        // Wait for the read-back of `05` before calling it done.
                                        _state.value = ProvisionUiState.InProgress(KEY_READ_BACK)
                                    } else {
                                        noteIfBoardKeepsItsKey()
                                        persistAndSucceed()
                                    }
                                    return@collect
                                }
                                ProvState.ERROR ->
                                    _state.value = ProvisionUiState.Error(ProvisionErrorCopy.forError(event.status.error, caps.errorReset))
                            }
                        }
                        is ProvisionEvent.Failed -> _state.value = ProvisionUiState.Error(ProvisionErrorCopy.forFailure(event.reason))
                        is ProvisionEvent.Handoff -> handOff()
                        is ProvisionEvent.Capabilities -> caps = event.caps
                        is ProvisionEvent.KeyReadBack -> {
                            val readBack = event.minerKey
                            if (readBack == null || readBack == ownerKey) {
                                minerKey = ownerKey.orEmpty()
                                persistAndSucceed()
                            } else {
                                _state.value = ProvisionUiState.Error(keyMismatch(readBack))
                            }
                        }
                    }
                }
            } else {
                val flow = if (ownerKey == null && setupCode == null) {
                    wifiProvisioner.provision(address, ssid, pass, wallet)
                } else {
                    wifiProvisioner.provision(address, ssid, pass, wallet, ownerKey, setupCode)
                }
                flow.collect { event ->
                    when (event) {
                        is SoftApEvent.Info -> {
                            caps = event.info.capabilities()
                            // A v1.1 board masks its key over the AP: never keep "FEM-AB…" as a key.
                            minerKey = event.info.minerKey?.takeUnless { MinerKeyFormat.isMasked(it) } ?: minerKey
                            if (ownerKeyTaken() && setupCode != null) minerKey = ownerKey.orEmpty()
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
                                    noteIfBoardKeepsItsKey()
                                    val masked = event.status.minerKey?.takeIf { MinerKeyFormat.isMasked(it) }
                                    if (minerKey.isEmpty() && masked != null) {
                                        // A keyed v1.1 board on its open AP: only the masked key is known here.
                                        _state.value = ProvisionUiState.Handoff(masked, CONNECTED_MASKED_KEY)
                                    } else {
                                        persistAndSucceed()
                                    }
                                    return@collect
                                }
                                // Codes come straight from the device's /status JSON, so an
                                // unknown value must not throw out of viewModelScope.
                                PROV_STATUS_ERROR_CODE -> {
                                    // v1.1: `detail` (6–13) refines the legacy `err` 4.
                                    val detail = event.status.detail ?: 0
                                    val code = if (detail >= ProvError.KEY_REQUIRED.code) detail else event.status.err
                                    _state.value = ProvisionUiState.Error(ProvisionErrorCopy.forError(ProvError.fromCode(code), caps.errorReset))
                                }
                                else ->
                                    _state.value = ProvisionUiState.InProgress(
                                        runCatching { stateLabel(ProvState.fromCode(event.status.status)) }
                                            .getOrDefault("Working…"),
                                    )
                            }
                        }
                        is SoftApEvent.Failed -> _state.value = ProvisionUiState.Error(ProvisionErrorCopy.forFailure(event.reason))
                        is SoftApEvent.Refused ->
                            _state.value = ProvisionUiState.Error(ProvisionErrorCopy.forRefusal(event.httpCode, event.err))
                        is SoftApEvent.Handoff -> handOff()
                    }
                }
            }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ProvisionUiState.Error(e.message ?: "Provisioning failed")
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

    companion object {
        const val HANDOFF_WAITING = "The board is joining your Wi-Fi. Waiting for it to reach Fry (up to 3 minutes)…"
        const val HANDOFF_UNCONFIRMED = "The board took the settings and left Bluetooth to join your Wi-Fi. Check the Miners tab in a few minutes to see it come online."
        const val HANDOFF_NOT_SEEN = "The board took the settings and left to join your Wi-Fi, but Fry has not seen it yet. If it does not appear within 10 minutes, check the Wi-Fi name and password and set it up again."
        const val HANDOFF_SIGNED_OUT = "The board took the settings and left to join your Wi-Fi. Sign in to see whether it reached Fry."
        const val HANDOFF_NO_KEY = "The board left to join your Wi-Fi without reporting its miner key. Check the dashboard in a few minutes."
        const val KEY_ACK_REQUIRED = "This key is active on another install. Tick the box to confirm what happens to the other install, then try again."
        const val KEY_BLOCKED = "This key cannot be set up for your wallet."
        const val KEY_READ_BACK = "Connected. Checking the key on the board…"
        const val DEVICE_KEEPS_NOTICE = "This board runs older firmware that keeps the key it made itself, so the key you entered was not written. Add this board key on the dashboard instead:"
        const val CONNECTED_MASKED_KEY = "The board is connected with the key it already had. Find it on the dashboard by the key shown here."

        fun keyMismatch(readBack: String): String =
            "The board reports a different key (${MinerKeyFormat.mask(readBack)}) than the one you entered. Nothing was changed on the dashboard; restart the board and set it up again."
    }
}
