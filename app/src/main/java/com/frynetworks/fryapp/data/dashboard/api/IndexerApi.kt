package com.frynetworks.fryapp.data.dashboard.api

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

/** Read-only GETs on the public mainnet indexers; the caller passes the full URL so one interface serves both hosts. */
interface IndexerApi {
    @GET
    suspend fun get(@Url url: String): Response<ResponseBody>
}

/**
 * The client for everything that decides whether a transfer may be sent again: a hard 15 s bound on the whole call (body included)
 * and no redirects, so a forged answer cannot be served from another host.
 */
fun boundedIndexerClient(base: OkHttpClient): OkHttpClient =
    base.newBuilder().callTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
