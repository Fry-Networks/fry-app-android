package com.frynetworks.fryapp.data.dashboard.api

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The dashboard's JSON routes, bound to the `@DashboardClient` OkHttp client (cookie jar, pinned
 * headers, `x-request-*` signing on `DashboardConfig.SIGNED_PATHS`, one fingerprint retry).
 *
 * Every body is a pre-canonicalised [RequestBody] built with `jsonBody { ... }.toJsonRequestBody()`
 * — the signing interceptor hashes the exact bytes on the wire, so key order is fixed by the
 * caller and never re-serialised here. Responses come back raw ([ResponseBody]) so the repository
 * layer can map the dashboard's error envelope and the handful of routes whose real shape differs
 * from the modelled DTO (see `DashboardCalls`).
 */
interface DashboardApi {

    // --- devices ---

    @POST("api/devices/list")
    suspend fun listDevices(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/devices/batch")
    suspend fun batchDevices(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/devices/{minerKey}")
    suspend fun deviceDetail(@Path("minerKey") minerKey: String, @Body body: RequestBody): Response<ResponseBody>

    @POST("api/devices/status-summary")
    suspend fun statusSummary(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/hardware/status")
    suspend fun hardwareStatus(@Body body: RequestBody): Response<ResponseBody>

    @GET("api/my-keys")
    suspend fun myKeys(): Response<ResponseBody>

    @POST("api/products/get-product")
    suspend fun getProduct(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/change-name")
    suspend fun changeName(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/devices/save-wallet-info")
    suspend fun saveWalletInfo(@Body body: RequestBody): Response<ResponseBody>

    // --- rewards (signed) ---

    @POST("api/rewards/get-asset-totals")
    suspend fun assetTotals(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/rewards/get-reward-summary-batch")
    suspend fun rewardSummaryBatch(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/rewards/get-reward-summary")
    suspend fun rewardSummary(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/rewards/get-rewards-page")
    suspend fun rewardsPage(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/rewards/claim")
    suspend fun claim(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/rewards/confirm")
    suspend fun confirm(@Body body: RequestBody): Response<ResponseBody>

    // --- unsigned helpers ---

    @POST("api/activity/recent")
    suspend fun recentActivity(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/price/get")
    suspend fun prices(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/algorand/verify-txn")
    suspend fun verifyTxn(@Body body: RequestBody): Response<ResponseBody>

    // --- stake (signed) ---

    @POST("api/stake/withdrawable")
    suspend fun withdrawable(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/stake/precheck")
    suspend fun precheck(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/stake/registration")
    suspend fun stakeRegistration(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/stake/node-staking")
    suspend fun stakeNode(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/stake/verification")
    suspend fun stakeVerification(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/stake/r-withdraw")
    suspend fun withdrawRegistration(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/stake/n-withdraw")
    suspend fun withdrawNode(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/stake/stake-withdraw")
    suspend fun withdrawVerification(@Body body: RequestBody): Response<ResponseBody>
}
