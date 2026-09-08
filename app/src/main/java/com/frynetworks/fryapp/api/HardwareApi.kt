package com.frynetworks.fryapp.api

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class VersionsResponse(
    val latestVersion: String? = null,
    val minSupportedVersion: String? = null,
)

/**
 * GET-only per PROTOCOL.md section 5. The firmware — not this app — owns the write calls
 * (installations, leases, PoC submissions) and the fleet bootstrap token. The app never
 * embeds a fleet-wide write credential; it only ever reads public version/reward config.
 */
interface HardwareApi {
    @GET("versions/{minerCode}")
    suspend fun getVersions(
        @Path("minerCode") minerCode: String,
        @Query("platform") platform: String,
    ): VersionsResponse
}
