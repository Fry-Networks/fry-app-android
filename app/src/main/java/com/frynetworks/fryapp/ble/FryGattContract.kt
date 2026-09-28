package com.frynetworks.fryapp.ble

import java.util.UUID

/**
 * Fry Device Provisioning Protocol v1 — BLE GATT contract.
 * Byte-identical constants to PROTOCOL.md section 1/2 in Fry-Networks/fry-firmware.
 * This file does not itself change the protocol; it mirrors it.
 */
object FryGattContract {

    val SERVICE_FRY: UUID = UUID.fromString("46525900-0001-4000-8000-4652594e4554")

    val CHAR_WIFI_SSID: UUID = UUID.fromString("46525901-0001-4000-8000-4652594e4554")
    val CHAR_WIFI_PASS: UUID = UUID.fromString("46525902-0001-4000-8000-4652594e4554")
    val CHAR_WALLET: UUID = UUID.fromString("46525903-0001-4000-8000-4652594e4554")
    val CHAR_DEVICE_NAME: UUID = UUID.fromString("46525904-0001-4000-8000-4652594e4554")
    val CHAR_MINER_KEY: UUID = UUID.fromString("46525905-0001-4000-8000-4652594e4554")
    val CHAR_STATUS: UUID = UUID.fromString("46525906-0001-4000-8000-4652594e4554")
    val CHAR_FW_VERSION: UUID = UUID.fromString("46525907-0001-4000-8000-4652594e4554")
    val CHAR_CHIP_TYPE: UUID = UUID.fromString("46525908-0001-4000-8000-4652594e4554")

    /** PROTOCOL.md 11.3 (v1.1): owner miner key write (encrypted write, exactly 36 bytes). */
    val CHAR_MINER_KEY_WRITE: UUID = UUID.fromString("46525909-0001-4000-8000-4652594e4554")

    /** PROTOCOL.md 11.3 (v1.1): device status JSON; absent on a protocol-1 board. */
    val CHAR_DEVICE_STATUS: UUID = UUID.fromString("4652590a-0001-4000-8000-4652594e4554")

    /** Client Characteristic Configuration Descriptor — enables notifications. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Central requests this MTU per PROTOCOL.md section 1. */
    const val REQUESTED_MTU: Int = 185

    /** Every BLE connection starts here; this is what we are on if the peripheral refuses. */
    const val DEFAULT_ATT_MTU: Int = 23

    /** ATT opcode + attribute handle overhead on a single write. */
    const val ATT_WRITE_HEADER_BYTES: Int = 3
}

/** Status state machine — PROTOCOL.md section 2, shared with ESP8266 GET /status. */
enum class ProvState(val code: Int) {
    IDLE(0),
    PROVISIONING(1),
    CONNECTING(2),
    CONNECTED(3),
    ERROR(4);

    companion object {
        fun fromCode(code: Int): ProvState =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown provisioning status code: $code")
    }
}

/** Error codes — PROTOCOL.md section 2, only meaningful when state == ERROR. */
enum class ProvError(val code: Int) {
    NONE(0),
    BAD_SSID(1),
    WIFI_AUTH_FAILED(2),
    NO_IP(3),
    HARDWAREAPI_REGISTRATION_FAILED(4),
    BAD_WALLET(5),

    // PROTOCOL.md v1.1 (append-only). Firmware sends these in status byte 2 with legacy byte 1 = 4,
    // so an older app still reads "registration failed".
    KEY_REQUIRED(6),
    BAD_KEY(7),
    KEY_LOCKED(8),
    REG_UNAUTHORIZED(9),
    REG_FORBIDDEN(10),
    REG_KEY_IN_USE(11),
    REG_REJECTED(12),
    UNREACHABLE(13);

    companion object {
        fun fromCode(code: Int): ProvError =
            entries.firstOrNull { it.code == code } ?: NONE
    }
}
