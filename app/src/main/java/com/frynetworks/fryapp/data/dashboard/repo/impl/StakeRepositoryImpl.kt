package com.frynetworks.fryapp.data.dashboard.repo.impl

import java.math.BigDecimal
import com.frynetworks.fryapp.data.dashboard.api.DashboardApi
import com.frynetworks.fryapp.data.dashboard.api.DashboardCalls
import com.frynetworks.fryapp.data.dashboard.api.DashboardErrorCodes
import com.frynetworks.fryapp.data.dashboard.api.toJsonRequestBody
import com.frynetworks.fryapp.data.dashboard.model.DashboardGson
import com.frynetworks.fryapp.data.dashboard.model.PrecheckResponse
import com.frynetworks.fryapp.data.dashboard.model.StakeSubmitResponse
import com.frynetworks.fryapp.data.dashboard.model.WithdrawableResponse
import com.frynetworks.fryapp.data.dashboard.repo.StakeRepository
import com.frynetworks.fryapp.data.dashboard.repo.StakeSubmitPayload
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.network.dashboard.SessionEventBus
import com.frynetworks.fryapp.network.dashboard.jsonBody
import com.google.gson.Gson

/**
 * Retrofit implementation of [StakeRepository]. Wire notes (dashboard snapshot):
 *  - `withdrawable` reads `{address, miner_key}` only (withdrawable.ts:14-25; the contract's optional
 *    `type` is not a server field) and answers `{message:"ok", data:{available, availableIn, legacy}}`
 *    (withdrawable.ts:108-121). It only knows the VERIFICATION stake (`device.staked`,
 *    withdrawable.ts:66-71 answers 401 `UNAUTHORIZED` when there is none), so for the Registration
 *    and Node contexts — which the dashboard withdraws without any availability check
 *    (WithdrawAll.tsx:73-97) — no call is made and the stake is reported withdrawable.
 *  - `precheck` body is `{miner_key, address, context}` with context `registration|node|verification`
 *    (precheck.ts:8-12, Stake.tsx:469-473); success is `{allowed:true}` (precheck.ts:69) and a
 *    429 carries `Retry-After` seconds (precheck.ts:56-58) -> `DashboardException.action`.
 *  - `registration`/`node-staking` take `{miner_key, address, txId, amount, asset_id}` and
 *    `verification` inserts `type` before `asset_id` (Stake.tsx:555-573). `amount` is a JS number
 *    of tokens (registration.ts:77 `typeof amount !== 'number'`) — whole for registration/node,
 *    possibly fractional for BYOD verification (Stake.tsx:225 halves to 2 dp); `asset_id` is a string.
 *  - `verify-txn` answers 200 `{success:false}` when the txn is not found (verify-txn.ts:91-93).
 *  - the three withdraw routes answer `{message:"ok", txId}` with no `success` flag
 *    (r-withdraw.ts:184, n-withdraw.ts:177, stake-withdraw.ts:213).
 */
class StakeRepositoryImpl(
    private val api: DashboardApi,
    bus: SessionEventBus? = null,
    private val gson: Gson = DashboardGson.instance,
    private val now: () -> Long = System::currentTimeMillis,
) : StakeRepository {

    private val calls = DashboardCalls(bus, gson)

    override suspend fun withdrawable(address: String, minerKey: String, context: StakeContext): WithdrawableResponse {
        if (context !is StakeContext.Verification) {
            return WithdrawableResponse(success = true, withdrawable = true, lockRemainingSeconds = 0L)
        }
        val element = try {
            calls.execute(expireSessionOn401 = false) {
                api.withdrawable(jsonBody { "address" to address; "miner_key" to minerKey }.toJsonRequestBody())
            }
        } catch (e: DashboardException) {
            calls.emitExpiredIfSessionError(e)
            throw e
        }
        val obj = DashboardCalls.objectOrNull(element)
        val data = DashboardCalls.objectOrNull(obj?.get("data"))
        if (data != null && data.has("available")) {
            val available = DashboardCalls.booleanOrNull(data, "available") ?: false
            val availableIn = DashboardCalls.longOrNull(data, "availableIn")
            val remaining = if (available || availableIn == null) 0L else maxOf(0L, (availableIn - now() + 999L) / 1000L)
            return WithdrawableResponse(success = true, withdrawable = available, lockRemainingSeconds = remaining)
        }
        return calls.parse(element, WithdrawableResponse::class.java)
    }

    override suspend fun precheck(minerKey: String, address: String, context: StakeContext): PrecheckResponse {
        val element = calls.execute {
            api.precheck(jsonBody { "miner_key" to minerKey; "address" to address; "context" to contextWire(context) }.toJsonRequestBody())
        }
        val obj = DashboardCalls.objectOrNull(element)
        val allowed = DashboardCalls.booleanOrNull(obj, "allowed") ?: DashboardCalls.booleanOrNull(obj, "success")
        return PrecheckResponse(success = allowed, message = DashboardCalls.stringOrNull(obj, "message"))
    }

    override suspend fun verifyTxn(address: String, txId: String) {
        val element = calls.execute { api.verifyTxn(jsonBody { "address" to address; "txId" to txId }.toJsonRequestBody()) }
        val obj = DashboardCalls.objectOrNull(element)
        if (DashboardCalls.booleanOrNull(obj, "success") != true) {
            throw DashboardException(
                code = DashboardErrorCodes.TXN_VERIFY_FAILED,
                message = DashboardCalls.stringOrNull(obj, "message") ?: "Transaction verification failed",
                httpStatus = 200,
            )
        }
    }

    override suspend fun submit(context: StakeContext, payload: StakeSubmitPayload): StakeSubmitResponse {
        val amount = jsTokens(payload)
        val body = jsonBody {
            "miner_key" to payload.minerKey
            "address" to payload.address
            "txId" to payload.txId
            "amount" to amount
            if (context is StakeContext.Verification) "type" to context.tier.code
            "asset_id" to payload.assetId.toString()
        }.toJsonRequestBody()
        val response = calls.call(StakeSubmitResponse::class.java) {
            when (context) {
                StakeContext.Registration -> api.stakeRegistration(body)
                StakeContext.Node -> api.stakeNode(body)
                is StakeContext.Verification -> api.stakeVerification(body)
            }
        }
        return response.copy(success = response.success ?: true)
    }

    override suspend fun withdraw(context: StakeContext, address: String, minerKey: String): StakeSubmitResponse {
        val body = jsonBody { "address" to address; "miner_key" to minerKey }.toJsonRequestBody()
        val response = calls.call(StakeSubmitResponse::class.java) {
            when (context) {
                StakeContext.Registration -> api.withdrawRegistration(body)
                StakeContext.Node -> api.withdrawNode(body)
                is StakeContext.Verification -> api.withdrawVerification(body)
            }
        }
        return response.copy(success = response.success ?: true)
    }

    /**
     * The dashboard records `amount` as the JS number Stake.tsx staked: whole tokens for
     * registration/node (`floor(usd / price)`) or the product's verification amount, which BYOD
     * halves to 2 dp. Anything finer than the ASA's 6 decimals cannot have been transferred and is
     * rejected before any bytes go on the wire; the canonical writer prints the value like JS.
     */
    private fun jsTokens(payload: StakeSubmitPayload): BigDecimal {
        val stripped = payload.amount.stripTrailingZeros()
        if (stripped.scale() > 6) {
            throw DashboardException(DashboardErrorCodes.INVALID_INPUT, "Stake amount has more than 6 decimals: ${payload.amount.toPlainString()}")
        }
        return if (stripped.scale() < 0) stripped.setScale(0) else stripped
    }

    companion object {
        /** precheck.ts:8-12 `CONTEXT_TO_ACTION` keys. */
        fun contextWire(context: StakeContext): String = when (context) {
            StakeContext.Registration -> "registration"
            StakeContext.Node -> "node"
            is StakeContext.Verification -> "verification"
        }
    }
}
