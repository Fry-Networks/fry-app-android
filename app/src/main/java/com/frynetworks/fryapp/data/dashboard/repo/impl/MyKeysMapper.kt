package com.frynetworks.fryapp.data.dashboard.repo.impl

import com.frynetworks.fryapp.data.dashboard.model.IotCredentials
import com.frynetworks.fryapp.data.dashboard.model.MyKeysResponse
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken

/**
 * `GET /api/my-keys` nests portal credentials per device as
 * `iotCredentials: { [collection]: { miner_type, api_type, credentials, credentials_saved_at, ... } }`
 * (my-keys.ts:126-150), while [MyKeysResponse] models a flat `credentials: [ {portal, ...} ]` list.
 * This fills the list from the real shape (portal = collection name) when the DTO parse left it
 * empty, so both the modelled fixture shape and the live answer produce the same result.
 */
internal object MyKeysMapper {

    private val MAP_TYPE = object : TypeToken<Map<String, Any?>>() {}.type

    fun attachIotCredentials(parsed: MyKeysResponse, raw: JsonElement, gson: Gson): MyKeysResponse {
        val devices = raw.takeIf { it.isJsonObject }?.asJsonObject?.get("devices")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return parsed
        val byKey = HashMap<String, List<IotCredentials>>()
        for (entry in devices) {
            val device = entry.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val key = device.get("miner_key")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val iot = device.get("iotCredentials")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val list = iot.entrySet().mapNotNull { (portal, value) ->
                val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val credentials: Map<String, Any?> = obj.get("credentials")?.takeIf { it.isJsonObject }
                    ?.let { gson.fromJson<Map<String, Any?>>(it, MAP_TYPE) } ?: emptyMap()
                IotCredentials(
                    portal = portal,
                    credentials = credentials,
                    credentialsSavedAt = obj.get("credentials_saved_at")?.takeIf { it.isJsonPrimitive }?.asString,
                )
            }
            if (list.isNotEmpty()) byKey[key] = list
        }
        if (byKey.isEmpty()) return parsed
        return parsed.copy(
            devices = parsed.devices.map { device ->
                if (device.credentials.isEmpty()) byKey[device.minerKey]?.let { device.copy(credentials = it) } ?: device else device
            },
        )
    }
}
