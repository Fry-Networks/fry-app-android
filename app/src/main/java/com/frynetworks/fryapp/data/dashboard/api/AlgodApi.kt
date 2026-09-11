package com.frynetworks.fryapp.data.dashboard.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** Read-only algod v2 routes on the public node (`DashboardConfig.ALGOD_URL`); no API token. */
interface AlgodApi {

    /** `exclude=all` skips the asset/app arrays — only `amount`, `auth-addr`, etc. come back. */
    @GET("v2/accounts/{address}")
    suspend fun account(@Path("address") address: String, @Query("exclude") exclude: String): Response<ResponseBody>

    /** 404 when [address] is not opted in to [assetId]. */
    @GET("v2/accounts/{address}/assets/{assetId}")
    suspend fun assetHolding(@Path("address") address: String, @Path("assetId") assetId: Long): Response<ResponseBody>

    @GET("v2/transactions/pending/{txid}")
    suspend fun pendingTransaction(@Path("txid") txId: String, @Query("format") format: String): Response<ResponseBody>

    @GET("v2/status")
    suspend fun status(): Response<ResponseBody>

    /** Blocks server-side until a block after [round] lands. */
    @GET("v2/status/wait-for-block-after/{round}")
    suspend fun waitForBlockAfter(@Path("round") round: Long): Response<ResponseBody>
}
