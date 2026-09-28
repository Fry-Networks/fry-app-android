package com.frynetworks.fryapp.data.keys

import com.frynetworks.fryapp.network.dashboard.jsonBody
import com.frynetworks.fryapp.network.dashboard.JsJson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/** Contract C-3 plus the device lookup the app already uses (method-agnostic on the server). */
interface KeyCheckApi {
    @POST("api/keys/check")
    suspend fun check(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/devices/{minerKey}")
    suspend fun device(@Path("minerKey") minerKey: String, @Body body: RequestBody): Response<ResponseBody>
}

data class KeyInstall(val platform: String?, val lastSeenAgeSeconds: Long?, val holdsLease: Boolean?)

sealed interface KeyCheckResult {
    /** A C-3 answer. [message], [bindingRule] and [consequence] are the dashboard's own words. */
    data class Checked(
        val verdict: String,
        val owner: String?,
        val message: String?,
        val bindingRule: String?,
        val installs: List<KeyInstall>,
        val consequence: String?,
        val espCompatible: Boolean?,
    ) : KeyCheckResult {
        /** The key runs elsewhere: setting it up here needs the owner's explicit acknowledgement. */
        val needsAcknowledgement: Boolean get() = verdict == VERDICT_ACTIVE_ELSEWHERE

        /** Setting up with this key cannot earn for this wallet (someone else's key, or not a FEM- key). */
        val blocksSetup: Boolean get() = verdict in BLOCKING_VERDICTS || espCompatible == false
    }

    /** An older dashboard without `/api/keys/check`: only whether the key exists is known. */
    data class Unverifiable(val existsOnDashboard: Boolean?, val message: String) : KeyCheckResult

    data class Failed(val message: String, val retryAfterSeconds: Long? = null) : KeyCheckResult

    companion object {
        const val VERDICT_ACTIVE_ELSEWHERE = "ok_active_elsewhere"
        val BLOCKING_VERDICTS = setOf("registered_other", "legacy_iot", "invalid_format")
    }
}

/** Asks the dashboard who owns a key before it is written to a board (O-1, contract C-3). */
class KeyCheckClient(private val api: KeyCheckApi) {

    suspend fun check(address: String, minerKey: String): KeyCheckResult = try {
        val body = JsJson.stringify(jsonBody { "address" to address; "miner_key" to minerKey }).toRequestBody(JSON)
        val response = api.check(body)
        response.useText { code, text ->
            when {
                code in 200..299 -> parseChecked(text)
                code == 404 && errorCode(text) == null -> existenceOnly(address, minerKey)
                code == 429 -> KeyCheckResult.Failed(
                    "Too many key checks. Wait a minute and try again.",
                    response.headers()["Retry-After"]?.trim()?.toLongOrNull(),
                )
                code == 401 -> KeyCheckResult.Failed("Sign in again to check this key.")
                else -> KeyCheckResult.Failed(serverMessage(text) ?: "The dashboard could not check this key (HTTP $code).")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        KeyCheckResult.Failed("Could not reach the dashboard to check this key. Check your connection and try again.")
    }

    private suspend fun existenceOnly(address: String, minerKey: String): KeyCheckResult {
        val response = api.device(minerKey, JsJson.stringify(jsonBody { "address" to address }).toRequestBody(JSON))
        return response.useText { code, _ ->
            when (code) {
                in 200..299 -> KeyCheckResult.Unverifiable(true, "Unverifiable: this key is on the dashboard, but this dashboard cannot tell who runs it. Set up only a key you own.")
                404 -> KeyCheckResult.Unverifiable(false, "Unverifiable: the dashboard has no record of this key yet. Check it for typos; a new key appears once a board registers with it.")
                else -> KeyCheckResult.Unverifiable(null, "Unverifiable: the dashboard could not look this key up right now.")
            }
        }
    }

    private fun parseChecked(text: String): KeyCheckResult {
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return KeyCheckResult.Failed("The dashboard sent an unreadable key check.")
        val verdict = obj.str("verdict") ?: return KeyCheckResult.Failed("The dashboard sent an unreadable key check.")
        val installs = obj.get("installs")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.obj() }?.map {
            KeyInstall(it.str("platform"), it.long("last_seen_age_s"), it.bool("holds_lease"))
        }.orEmpty()
        return KeyCheckResult.Checked(
            verdict = verdict,
            owner = obj.str("owner"),
            message = obj.str("message"),
            bindingRule = obj.get("binding_rule")?.obj()?.str("text"),
            installs = installs,
            consequence = obj.str("consequence"),
            espCompatible = obj.get("device_compatible")?.obj()?.bool("esp_firmware"),
        )
    }

    private fun errorCode(text: String): String? = runCatching { JsonParser.parseString(text).asJsonObject.str("code") }.getOrNull()

    private fun serverMessage(text: String): String? = runCatching { JsonParser.parseString(text).asJsonObject.str("message") }.getOrNull()

    private inline fun <T> Response<ResponseBody>.useText(block: (Int, String) -> T): T {
        val body = if (isSuccessful) body() else errorBody()
        val text = body?.use { runCatching { it.string() }.getOrNull() }.orEmpty()
        return block(code(), text)
    }

    private fun JsonElement.obj(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.long(k: String): Long? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
    private fun JsonObject.bool(k: String): Boolean? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
