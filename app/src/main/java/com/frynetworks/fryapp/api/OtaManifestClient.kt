package com.frynetworks.fryapp.api

import com.frynetworks.fryapp.BuildConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

data class OtaBuild(val url: String, val sha256: String)

/** PROTOCOL.md section 6. */
data class OtaManifest(
    @SerializedName("firmware_version") val firmwareVersion: String,
    val builds: Map<String, OtaBuild> = emptyMap(),
)

/**
 * Fetches the public OTA manifest referenced by [BuildConfig.OTA_MANIFEST_URL]. Zero-auth by
 * design — a GitHub release asset, never a hardwareapi endpoint, and never sent a bearer
 * token — so this client uses its own bare [OkHttpClient] rather than the one wired up for
 * [HardwareApi].
 */
@Singleton
class OtaManifestClient @Inject constructor() {
    private val client = OkHttpClient()
    private val gson = Gson()

    suspend fun fetchManifest(): OtaManifest? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(BuildConfig.OTA_MANIFEST_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                gson.fromJson(body, OtaManifest::class.java)
            }
        } catch (e: Exception) {
            null
        }
    }
}
