package com.frynetworks.fryapp.data.dashboard.model

import com.google.gson.annotations.SerializedName

/** `POST /api/devices/batch` response — `devices` is keyed by `miner_key`. */
data class BatchDevicesResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("devices") val devices: Map<String, DeviceDetail> = emptyMap(),
)
