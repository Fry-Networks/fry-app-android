package com.frynetworks.fryapp.provisioning

/** What a board says about itself: BLE `0A` or ESP8266 `GET /info` (PROTOCOL.md 11.3, 11.6). */
data class DeviceCapabilities(
    val proto: Int,
    val caps: Set<String>,
    val keyPresent: Boolean? = null,
    val keyConfirmed: Boolean? = null,
    val fw: String? = null,
    val ota: String? = null,
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

    fun forBle(caps: DeviceCapabilities): KeyTransport =
        if (caps.keyWrite) KeyTransport.Send else KeyTransport.DeviceKeeps

    fun forSoftAp(caps: DeviceCapabilities, joinedWithSetupCode: Boolean): KeyTransport = when {
        !caps.keyWrite -> KeyTransport.DeviceKeeps
        !joinedWithSetupCode -> KeyTransport.Refuse(OPEN_AP_REFUSAL)
        else -> KeyTransport.Send
    }
}
