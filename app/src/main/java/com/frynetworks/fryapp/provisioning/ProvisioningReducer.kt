package com.frynetworks.fryapp.provisioning

import com.frynetworks.fryapp.ble.FryGattContract
import com.frynetworks.fryapp.ble.ProvError
import com.frynetworks.fryapp.ble.ProvState
import com.frynetworks.fryapp.util.AlgorandAddress
import java.util.UUID

/** One BLE characteristic write, produced by [ProvisioningReducer.plan]. */
data class WriteStep(val characteristic: UUID, val value: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WriteStep) return false
        return characteristic == other.characteristic && value.contentEquals(other.value)
    }

    override fun hashCode(): Int = 31 * characteristic.hashCode() + value.contentHashCode()
}

/** Decoded BLE status-characteristic payload. */
data class ProvStatus(val state: ProvState, val error: ProvError = ProvError.NONE)

/**
 * Pure functions translating the PROTOCOL.md BLE contract into UI-consumable state and
 * outbound writes. No I/O here — [com.frynetworks.fryapp.ble.BleProvisioner] performs the
 * actual GATT operations and calls into this reducer for decisions.
 */
object ProvisioningReducer {

    /**
     * Decodes the Status characteristic (PROTOCOL.md section 2): byte 0 is the state; a
     * second byte carries the error code and is only meaningful when state == ERROR.
     */
    fun fromStatusBytes(bytes: ByteArray): ProvStatus {
        require(bytes.isNotEmpty()) { "Status payload must not be empty" }
        val state = ProvState.fromCode(bytes[0].toInt() and 0xFF)
        val error = if (state == ProvState.ERROR && bytes.size >= 2) {
            ProvError.fromCode(bytes[1].toInt() and 0xFF)
        } else {
            ProvError.NONE
        }
        return ProvStatus(state, error)
    }

    /**
     * Same decode as [fromStatusBytes] but returns null instead of throwing when the peripheral
     * sends a state or error code this build does not know.
     *
     * The strict variant is right for our own tests and for code we control. It is the wrong
     * shape for the two live paths that read bytes straight off a peripheral, because the
     * firmware and the app are developed independently: a protocol revision skew, a firmware
     * bug, or a single flipped bit in the first payload byte would otherwise throw out of a
     * binder callback and take the process down. Unknown status is a reason to stop trusting
     * the payload, not a reason to crash.
     */
    fun fromStatusBytesOrNull(bytes: ByteArray): ProvStatus? =
        runCatching { fromStatusBytes(bytes) }.getOrNull()

    /**
     * Builds the write plan for provisioning a device: SSID, then password, then wallet
     * (PROTOCOL.md section 1 — writing WALLET commits provisioning, so it must be last).
     * Validates the wallet BEFORE building any step, so an invalid wallet never emits a
     * single write.
     */
    fun plan(ssid: String, pass: String, wallet: String): List<WriteStep> {
        require(AlgorandAddress.isValid(wallet)) { "Invalid Algorand wallet address" }
        return listOf(
            WriteStep(FryGattContract.CHAR_WIFI_SSID, ssid.toByteArray(Charsets.UTF_8)),
            WriteStep(FryGattContract.CHAR_WIFI_PASS, pass.toByteArray(Charsets.UTF_8)),
            WriteStep(FryGattContract.CHAR_WALLET, wallet.toByteArray(Charsets.UTF_8)),
        )
    }
}
