package com.frynetworks.fryapp.data.dashboard.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

/** Read-only GETs on the public mainnet indexers; the caller passes the full URL so one interface serves both hosts. */
interface IndexerApi {
    @GET
    suspend fun get(@Url url: String): Response<ResponseBody>
}
