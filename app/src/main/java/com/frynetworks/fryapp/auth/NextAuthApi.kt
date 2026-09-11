package com.frynetworks.fryapp.auth

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST

data class CsrfResponse(@SerializedName("csrfToken") val csrfToken: String? = null)

/** NextAuth answers the credentials callback with `{url}` when `X-Auth-Return-Redirect: 1` is sent. */
data class CallbackResponse(val url: String? = null)

data class CaptureFingerprintResponse(
    val success: Boolean? = null,
    val fingerprint: String? = null,
    @SerializedName("userAgent") val userAgent: String? = null,
)

data class CheckUserResponse(@SerializedName("isNew") val isNew: Boolean? = null)

/**
 * The dashboard's NextAuth v4 surface used by the app. Cookies (`__Host-next-auth.csrf-token`,
 * `__Secure-next-auth.session-token`, `__Secure-next-auth.callback-url`) travel in the OkHttp
 * cookie jar; nothing here needs the security headers.
 */
interface NextAuthApi {

    @GET("api/auth/csrf")
    suspend fun csrf(): CsrfResponse

    /** Credentials-provider sign-in (`WalletAuthProvider`): a signed nonce txn proves wallet ownership. */
    @FormUrlEncoded
    @Headers("X-Auth-Return-Redirect: 1")
    @POST("api/auth/callback/wallet")
    suspend fun walletCallback(
        @Field("csrfToken") csrfToken: String,
        @Field("callbackUrl") callbackUrl: String,
        @Field("json") json: String,
        @Field("address") address: String,
        @Field("signedTxn") signedTxn: String,
        @Field("nonce") nonce: String,
        @Field("email") email: String?,
        @Field("first_name") firstName: String?,
        @Field("last_name") lastName: String?,
    ): CallbackResponse

    @POST("api/auth/capture-fingerprint")
    suspend fun captureFingerprint(@Body body: RequestBody): CaptureFingerprintResponse

    /** NextAuth `update()`: `{csrfToken, data}` copied into the JWT by the dashboard's jwt callback. */
    @POST("api/auth/session")
    suspend fun updateSession(@Body body: RequestBody): JsonObject

    /** `{}` when there is no session. */
    @GET("api/auth/session")
    suspend fun session(): JsonObject

    @FormUrlEncoded
    @POST("api/auth/signout")
    suspend fun signOut(@Field("csrfToken") csrfToken: String, @Field("json") json: String): Response<ResponseBody>

    @POST("api/check-user")
    suspend fun checkUser(@Body body: RequestBody): CheckUserResponse

    companion object {
        fun create(client: OkHttpClient, baseUrl: String): NextAuthApi =
            Retrofit.Builder()
                .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(NextAuthApi::class.java)
    }
}
