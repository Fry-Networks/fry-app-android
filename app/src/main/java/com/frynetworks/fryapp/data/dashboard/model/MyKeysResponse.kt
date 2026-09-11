package com.frynetworks.fryapp.data.dashboard.model

import com.google.gson.annotations.SerializedName

/** `GET /api/my-keys` response (blueprint 5.5 Miner Keys screen). */
data class MyKeysResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("devices") val devices: List<MyKeyDevice> = emptyList(),
    @SerializedName("byodLicenses") val byodLicenses: List<String> = emptyList(),
)

data class MyKeyDevice(
    @SerializedName("miner_key") val minerKey: String? = null,
    @SerializedName("nickname") val nickname: String? = null,
    @SerializedName("is_registered") val isRegistered: Boolean = false,
    @SerializedName("credentials") val credentials: List<IotCredentials> = emptyList(),
)

/** One IoT-portal credential set (masked in the UI, revealed on demand and auto-hidden). */
data class IotCredentials(
    @SerializedName("portal") val portal: String? = null,
    @SerializedName("credentials") val credentials: Map<String, Any?> = emptyMap(),
    @SerializedName("credentials_saved_at") val credentialsSavedAt: String? = null,
)
