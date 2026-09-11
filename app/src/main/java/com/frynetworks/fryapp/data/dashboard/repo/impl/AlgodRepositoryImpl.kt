package com.frynetworks.fryapp.data.dashboard.repo.impl

import com.frynetworks.fryapp.data.dashboard.api.AlgodApi
import com.frynetworks.fryapp.data.dashboard.api.DashboardCalls
import com.frynetworks.fryapp.data.dashboard.api.DashboardErrorCodes
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException

/**
 * Read-only algod queries over the public node. Failures map to [DashboardException]:
 * `NETWORK_ERROR` for I/O, `ALGOD_ERROR` (with the node's `message`) for any non-2xx except the
 * documented 404s (unfunded account -> 0 / no `auth-addr`; asset holding -> not opted in).
 * [waitConfirmed] mirrors algosdk's `waitForConfirmation`: poll the pending pool, advance one round
 * at a time with `wait-for-block-after`, give up after [rounds] rounds (`TXN_NOT_CONFIRMED`) or
 * as soon as the pool reports a rejection (`TXN_REJECTED`).
 */
class AlgodRepositoryImpl(private val api: AlgodApi) : AlgodRepository {

    override suspend fun algoBalanceMicro(address: String): Long {
        val account = account(address) ?: return 0L
        return DashboardCalls.longOrNull(account, "amount") ?: 0L
    }

    override suspend fun assetBalanceMicro(address: String, assetId: Long): Long? {
        val (status, element) = get { api.assetHolding(address, assetId) }
        if (status == 404) return null
        val holding = DashboardCalls.objectOrNull(DashboardCalls.objectOrNull(element)?.get("asset-holding")) ?: return null
        return DashboardCalls.longOrNull(holding, "amount") ?: 0L
    }

    override suspend fun authAddress(address: String): String? {
        val account = account(address) ?: return null
        return DashboardCalls.stringOrNull(account, "auth-addr")?.takeIf { it.isNotBlank() }
    }

    override suspend fun waitConfirmed(txId: String, rounds: Int) {
        val (_, statusElement) = get { api.status() }
        val startRound = (DashboardCalls.longOrNull(DashboardCalls.objectOrNull(statusElement), "last-round") ?: 0L) + 1
        var current = startRound
        val last = startRound + rounds
        while (current < last) {
            val (_, pending) = get { api.pendingTransaction(txId, "json") }
            val obj = DashboardCalls.objectOrNull(pending)
            val poolError = DashboardCalls.stringOrNull(obj, "pool-error")
            if (!poolError.isNullOrBlank()) {
                throw DashboardException(DashboardErrorCodes.TXN_REJECTED, poolError)
            }
            val confirmedRound = DashboardCalls.longOrNull(obj, "confirmed-round") ?: 0L
            if (confirmedRound > 0L) return
            get { api.waitForBlockAfter(current) }
            current++
        }
        throw DashboardException(DashboardErrorCodes.TXN_NOT_CONFIRMED, "Transaction $txId was not confirmed within $rounds rounds")
    }

    /** `/v2/accounts/{addr}?exclude=all`; null for a 404 (address never funded). */
    private suspend fun account(address: String): JsonObject? {
        val (status, element) = get { api.account(address, "all") }
        if (status == 404) return null
        return DashboardCalls.objectOrNull(element)
    }

    /** Executes an algod GET; 404 is returned to the caller, every other non-2xx throws. */
    private suspend fun get(call: suspend () -> Response<ResponseBody>): Pair<Int, JsonElement> {
        val response = try {
            call()
        } catch (e: IOException) {
            throw DashboardException(DashboardErrorCodes.NETWORK_ERROR, e.message ?: "Network error")
        }
        val status = response.code()
        if (status == 404) {
            response.errorBody()?.close()
            return status to JsonNull.INSTANCE
        }
        if (!response.isSuccessful) {
            val text = runCatching { response.errorBody()?.string() }.getOrNull().orEmpty()
            val message = runCatching { JsonParser.parseString(text).asJsonObject.get("message")?.asString }.getOrNull()
            throw DashboardException(DashboardErrorCodes.ALGOD_ERROR, message ?: "algod answered HTTP $status", httpStatus = status)
        }
        val text = runCatching { response.body()?.string() }.getOrNull().orEmpty()
        if (text.isBlank()) return status to JsonNull.INSTANCE
        val parsed = try {
            JsonParser.parseString(text)
        } catch (e: RuntimeException) {
            throw DashboardException(DashboardErrorCodes.BAD_RESPONSE, "algod answered $status with a non-JSON body", httpStatus = status)
        }
        return status to parsed
    }
}
