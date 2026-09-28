package com.frynetworks.fryapp.ble

import com.frynetworks.fryapp.provisioning.DeviceCapabilities
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Characteristic `0A` (PROTOCOL.md 11.3): `{"v":1,"proto":2,"caps":[...],"s":..,"e":..,"d":..,
 * "k":0|1,"kc":0|1,"reg":..,"hb":..,"fw":"x.y.z","ota":"valid|pending|rolled_back"}`, at most
 * 160 bytes and never a key. Also reads the same fields from ESP8266 `GET /info`
 * (`"proto"`, `"caps"`, `"keySet"`).
 */
object DeviceStatusJson {

    fun parse(bytes: ByteArray?): DeviceCapabilities {
        if (bytes == null || bytes.isEmpty()) return DeviceCapabilities.PROTO_1
        return parse(bytes.toString(Charsets.UTF_8))
    }

    fun parse(text: String?): DeviceCapabilities {
        val obj = runCatching { JsonParser.parseString(text.orEmpty()).asJsonObject }.getOrNull() ?: return DeviceCapabilities.PROTO_1
        val proto = obj.int("proto") ?: return DeviceCapabilities.PROTO_1
        val caps = obj.get("caps")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }?.toSet().orEmpty()
        val keyPresent = obj.int("k")?.let { it == 1 } ?: obj.bool("keySet")
        return DeviceCapabilities(
            proto = proto,
            caps = caps,
            keyPresent = keyPresent,
            keyConfirmed = obj.int("kc")?.let { it == 1 },
            fw = obj.str("fw"),
            ota = obj.str("ota"),
        )
    }

    private fun JsonObject.int(k: String): Int? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    private fun JsonObject.bool(k: String): Boolean? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString
}
