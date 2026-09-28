package com.frynetworks.fryapp.auth

import com.frynetworks.fryapp.network.dashboard.JsJson
import com.frynetworks.fryapp.network.dashboard.jsonBody
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/** `POST /api/auth/nonce {address}` → `{nonce, expiresAt}`: the dashboard's single-use login nonce. */
interface LoginNonceApi {
    @POST("api/auth/nonce")
    suspend fun loginNonce(@Body body: RequestBody): Response<ResponseBody>
}

/** The dashboard has the nonce route but could not issue one (5xx, 429, network, malformed reply). */
class LoginNonceUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Supplies the nonce the sign-in proof signs. The dashboard (registration_portal lib/auth.ts, D8)
 * accepts only a nonce it issued for this address, once, within 5 minutes. A 404 means a dashboard
 * from before the nonce store, which still accepts a locally generated nonce. Every other failure
 * throws [LoginNonceUnavailableException]: a local nonce there would only yield a proof the server
 * rejects as "the dashboard did not accept the wallet signature".
 */
class LoginNonceProvider(
    private val api: LoginNonceApi,
    private val legacy: NonceGenerator = SecureNonceGenerator(),
) {

    suspend fun nonceFor(address: String): String {
        val body = JsJson.stringify(jsonBody { "address" to address }).toRequestBody(JSON)
        val response = try {
            api.loginNonce(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LoginNonceUnavailableException("Could not reach the dashboard for a sign-in nonce.", e)
        }
        try {
            if (response.code() == 404) return legacy.next()
            if (!response.isSuccessful) {
                throw LoginNonceUnavailableException("The dashboard could not issue a sign-in nonce (HTTP ${response.code()}). Try again in a minute.")
            }
            val text = response.body()?.string().orEmpty()
            val nonce = runCatching { JsonParser.parseString(text).asJsonObject["nonce"]?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
            return nonce?.takeIf { it.isNotBlank() }
                ?: throw LoginNonceUnavailableException("The dashboard sent an unreadable sign-in nonce.")
        } finally {
            response.body()?.close()
            response.errorBody()?.close()
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
