package com.frynetworks.fryapp.data.dashboard.api

import com.frynetworks.fryapp.data.dashboard.model.ApiErrorBody
import com.frynetworks.fryapp.data.dashboard.model.DashboardGson
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.network.dashboard.JsonBody
import com.frynetworks.fryapp.network.dashboard.SessionEvent
import com.frynetworks.fryapp.network.dashboard.SessionEventBus
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException

/** `application/json` with no charset parameter — exactly the header the dashboard's fetch sends. */
val JSON_MEDIA_TYPE: MediaType = "application/json".toMediaType()

/**
 * The canonical (`JSON.stringify`-identical) bytes of this body as an OkHttp request body. Built
 * from the UTF-8 bytes (not the String overload, which appends `; charset=utf-8` to the media type)
 * so the wire carries exactly `Content-Type: application/json`, like the dashboard's own fetch.
 */
fun JsonBody.toJsonRequestBody(): RequestBody = canonical().toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE)

/** Error codes this layer synthesises when the dashboard/algod envelope does not carry one. */
object DashboardErrorCodes {
    const val NETWORK_ERROR = "NETWORK_ERROR"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val SESSION_REQUIRED = "SESSION_REQUIRED"
    const val WALLET_MISMATCH = "WALLET_MISMATCH"
    const val RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED"
    const val BAD_RESPONSE = "BAD_RESPONSE"
    const val DEVICE_NOT_FOUND = "DEVICE_NOT_FOUND"
    const val TXN_VERIFY_FAILED = "TXN_VERIFY_FAILED"
    const val TXN_NOT_CONFIRMED = "TXN_NOT_CONFIRMED"
    const val TXN_REJECTED = "TXN_REJECTED"
    const val ALGOD_ERROR = "ALGOD_ERROR"
    const val INVALID_INPUT = "INVALID_INPUT"
}

/**
 * Executes dashboard calls and maps every failure to [DashboardException]:
 *  - `IOException` (no network, timeout, TLS)      -> `NETWORK_ERROR`, httpStatus 0
 *  - non-2xx with an `ApiErrorBody` envelope        -> the envelope's code/message/action
 *  - non-2xx without one                             -> `UNAUTHORIZED` (401) / `RATE_LIMIT_EXCEEDED` (429) / `HTTP_<n>`
 *  - 429                                             -> `action` = the `Retry-After` header (seconds) when present
 *  - 401                                             -> additionally emits [SessionEvent.Expired] unless the
 *    envelope says `WALLET_MISMATCH` (a body/session address disagreement, not a dead session)
 * A 2xx body is returned parsed ([JsonNull] when empty); a 2xx that is not JSON is `BAD_RESPONSE`.
 */
class DashboardCalls(
    private val bus: SessionEventBus? = null,
    private val gson: com.google.gson.Gson = DashboardGson.instance,
) {

    suspend fun execute(
        expireSessionOn401: Boolean = true,
        call: suspend () -> Response<ResponseBody>,
    ): JsonElement {
        val response = try {
            call()
        } catch (e: IOException) {
            throw DashboardException(DashboardErrorCodes.NETWORK_ERROR, e.message ?: "Network error")
        }
        if (!response.isSuccessful) {
            val text = runCatching { response.errorBody()?.string() }.getOrNull().orEmpty()
            throw mapError(response.code(), response.headers()["Retry-After"], text, expireSessionOn401)
        }
        val text = runCatching { response.body()?.string() }.getOrNull().orEmpty()
        return parseJson(text, response.code())
    }

    /** Builds the exception for a non-2xx response (and raises the session event for a dead session). */
    fun mapError(status: Int, retryAfter: String?, body: String, expireSessionOn401: Boolean = true): DashboardException {
        val envelope = runCatching { gson.fromJson(body, ApiErrorBody::class.java) }.getOrNull()
        val code = envelope?.code?.takeIf { it.isNotBlank() } ?: when (status) {
            401 -> DashboardErrorCodes.UNAUTHORIZED
            429 -> DashboardErrorCodes.RATE_LIMIT_EXCEEDED
            else -> "HTTP_$status"
        }
        val message = envelope?.message?.takeIf { it.isNotBlank() } ?: "HTTP $status"
        val retrySeconds = retryAfter?.trim()?.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
        val action = if (status == 429 && retrySeconds != null) retrySeconds else envelope?.action
        if (status == 401 && expireSessionOn401 && code != DashboardErrorCodes.WALLET_MISMATCH) {
            bus?.emit(SessionEvent.Expired)
        }
        return DashboardException(code = code, message = message, action = action, httpStatus = status)
    }

    /** Emits [SessionEvent.Expired] for a 401 whose envelope names a missing session (used by callers that opted out of the automatic emit). */
    fun emitExpiredIfSessionError(e: DashboardException) {
        if (e.httpStatus == 401 && e.code == DashboardErrorCodes.SESSION_REQUIRED) bus?.emit(SessionEvent.Expired)
    }

    private fun parseJson(text: String, status: Int): JsonElement {
        if (text.isBlank()) return JsonNull.INSTANCE
        return try {
            JsonParser.parseString(text)
        } catch (e: RuntimeException) {
            throw DashboardException(DashboardErrorCodes.BAD_RESPONSE, "Dashboard answered $status with a non-JSON body", httpStatus = status)
        }
    }

    /** Parses [element] into [type] with the shared dashboard Gson; a non-object/array body is `BAD_RESPONSE`. */
    fun <T> parse(element: JsonElement, type: Class<T>): T {
        if (element.isJsonNull) throw DashboardException(DashboardErrorCodes.BAD_RESPONSE, "Dashboard answered with an empty body")
        return try {
            gson.fromJson(element, type)
        } catch (e: RuntimeException) {
            throw DashboardException(DashboardErrorCodes.BAD_RESPONSE, "Dashboard answer did not match ${type.simpleName}: ${e.message}")
        }
    }

    suspend fun <T> call(type: Class<T>, expireSessionOn401: Boolean = true, call: suspend () -> Response<ResponseBody>): T =
        parse(execute(expireSessionOn401, call), type)

    companion object {
        /** The object form of [element], or null when it is not a JSON object. */
        fun objectOrNull(element: JsonElement?): JsonObject? = element?.takeIf { it.isJsonObject }?.asJsonObject

        fun stringOrNull(obj: JsonObject?, key: String): String? =
            obj?.get(key)?.takeIf { it.isJsonPrimitive }?.asString

        fun booleanOrNull(obj: JsonObject?, key: String): Boolean? =
            obj?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

        fun longOrNull(obj: JsonObject?, key: String): Long? =
            obj?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
    }
}
