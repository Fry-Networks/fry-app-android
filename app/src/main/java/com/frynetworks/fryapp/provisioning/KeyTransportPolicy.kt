package com.frynetworks.fryapp.provisioning

import com.frynetworks.fryapp.ble.ProvError

/** What a board says about itself: BLE `0A` or ESP8266 `GET /info` (PROTOCOL.md 11.3, 11.6). */
data class DeviceCapabilities(
    val proto: Int,
    val caps: Set<String>,
    val keyPresent: Boolean? = null,
    val keyConfirmed: Boolean? = null,
    val fw: String? = null,
    val ota: String? = null,
    /** `0A` `"s"` and `"d"`: the board's current state and error detail; null when not reported. */
    val state: Int? = null,
    val detail: Int? = null,
    /** `0A` `"enc"` (PROTOCOL.md 11.9): 1 when the reader's link is encrypted; null before firmware 0.4.1. */
    val enc: Int? = null,
) {
    val keyWrite: Boolean get() = proto >= 2 && CAP_KEY_WRITE in caps
    val errorReset: Boolean get() = proto >= 2 && CAP_ERROR_RESET in caps

    companion object {
        const val CAP_KEY_WRITE = "key_write"
        const val CAP_ERROR_RESET = "error_reset"

        /** No `0A` / no `"proto"`: a protocol-1 board, which keeps the key it minted itself. */
        val PROTO_1 = DeviceCapabilities(proto = 1, caps = emptySet())
    }
}

/** What the BLE session does about the miner key before the Wi-Fi writes (PROTOCOL.md 11.1, 11.8). */
sealed interface KeyPlan {
    /** Write [key] (the owner's, never the board's) to `09`, then the Wi-Fi steps. */
    data class Write(val key: String) : KeyPlan

    /** Nothing to write: a protocol-1 board keeps its own key, or no key was entered. */
    data object NoKeyStep : KeyPlan

    /** 11.8: unencrypted writes would be ignored; report [error] and stop before writing anything. */
    data class Stop(val error: ProvError) : KeyPlan
}

sealed interface KeyTransport {
    /** Send the owner's key on this link. */
    data object Send : KeyTransport

    /** A protocol-1 board (DEVICE_KEEPS): nothing is written; the board's own key is shown instead. */
    data object DeviceKeeps : KeyTransport

    /** Never send the key here; [reason] tells the user what to do instead. */
    data class Refuse(val reason: String) : KeyTransport
}

/**
 * Where the owner's miner key may travel (PROTOCOL.md 11.1): only over an encrypted link. BLE
 * `09` is an encrypted-write characteristic on every v1.1 board, so BLE is safe once the board
 * advertises `key_write`. The ESP8266 open AP (a board that already has a key) is plain HTTP on
 * an open network, so a key is never sent there; the WPA2 setup AP (joined with the setup code)
 * is fine.
 */
object KeyTransportPolicy {

    const val OPEN_AP_REFUSAL = "This board's setup network is open, so Fry will not send a miner key over it. It already has a key; to change it, use the USB web setup."

    /**
     * PROTOCOL.md 11.8: the errors a running board keeps while it ignores unencrypted 01/02/03
     * writes: the API-side ones (4, 6, 9-13) and a key refusal (7, 8) raised after the join.
     */
    val API_SIDE_ERRORS = setOf(4, 6, 7, 8, 9, 10, 11, 12, 13)

    /** Of those, the ones the board recovers from by itself (it keeps retrying): its own copy applies, not "key needed". */
    val SELF_HEALING_ERRORS = setOf(4, 13)
    private const val STATE_ERROR = 4

    /**
     * True when a v1.1 board sits in an API-side error and no owner key is about to be written:
     * over the unencrypted link the Wi-Fi settings would be ignored (11.8), so the user has to
     * enter the FEM- key first (the session pairs before its `09` write, and the board starts a new attempt; PROTOCOL.md 11.9).
     */
    fun keyNeededBeforeWrite(caps: DeviceCapabilities, ownerKey: String?): Boolean =
        ownerKey == null && caps.proto >= 2 && caps.state == STATE_ERROR && caps.detail in API_SIDE_ERRORS

    /**
     * The BLE session's key step, decided before any write. Only [ownerKey] (what the user
     * entered, C-1 valid) can ever be written; the board's own `05` value is never a candidate.
     */
    fun planKeySteps(ownerKey: String?, caps: DeviceCapabilities): KeyPlan = when {
        keyNeededBeforeWrite(caps, ownerKey) -> KeyPlan.Stop(
            if (caps.detail in SELF_HEALING_ERRORS) ProvError.fromCode(caps.detail!!) else ProvError.KEY_REQUIRED,
        )
        ownerKey != null && forBle(caps) == KeyTransport.Send -> KeyPlan.Write(ownerKey)
        else -> KeyPlan.NoKeyStep
    }

    fun forBle(caps: DeviceCapabilities): KeyTransport =
        if (caps.keyWrite) KeyTransport.Send else KeyTransport.DeviceKeeps

    fun forSoftAp(caps: DeviceCapabilities, joinedWithSetupCode: Boolean): KeyTransport = when {
        !caps.keyWrite -> KeyTransport.DeviceKeeps
        !joinedWithSetupCode -> KeyTransport.Refuse(OPEN_AP_REFUSAL)
        else -> KeyTransport.Send
    }
}
