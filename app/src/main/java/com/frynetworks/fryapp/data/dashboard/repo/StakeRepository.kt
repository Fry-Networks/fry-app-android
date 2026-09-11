package com.frynetworks.fryapp.data.dashboard.repo

import com.frynetworks.fryapp.data.dashboard.model.PrecheckResponse
import com.frynetworks.fryapp.data.dashboard.model.StakeSubmitResponse
import com.frynetworks.fryapp.data.dashboard.model.WithdrawableResponse
import com.frynetworks.fryapp.domain.StakeContext
import java.math.BigDecimal

/**
 * What the dashboard records after the user's ASA transfer to the stake wallet confirmed.
 * [amount] is in display units (whole tokens, as the dashboard's `amount` field); [assetId] is the
 * ASA the transfer moved. The wire body follows Stake.tsx exactly — the data layer owns key order.
 */
data class StakeSubmitPayload(
    val address: String,
    val minerKey: String,
    val txId: String,
    val amount: BigDecimal,
    val assetId: Long,
)

/**
 * Staking on the dashboard API (all routes signed):
 *  - `POST /api/stake/withdrawable {address,miner_key[,type]}`
 *  - `POST /api/stake/precheck {miner_key,address,context}` (429 -> DashboardException RATE_LIMIT_EXCEEDED, action = Retry-After seconds)
 *  - `POST /api/algorand/verify-txn {address,txId}` (the dashboard's `verifyTransactionRequest` before recording)
 *  - `POST /api/stake/registration | node-staking | verification` with the F9 payload
 *  - `POST /api/stake/r-withdraw | n-withdraw | stake-withdraw {address,miner_key}`
 * Stake wallet (never changed by the app): `UKVAN7ORIUX7Y6QJFYQ4YGQAZD3RAC7QTDB73S2E5MSILUWAA7FJ6N7WLU`.
 */
interface StakeRepository {
    suspend fun withdrawable(address: String, minerKey: String, context: StakeContext): WithdrawableResponse

    suspend fun precheck(minerKey: String, address: String, context: StakeContext): PrecheckResponse

    suspend fun verifyTxn(address: String, txId: String)

    suspend fun submit(context: StakeContext, payload: StakeSubmitPayload): StakeSubmitResponse

    suspend fun withdraw(context: StakeContext, address: String, minerKey: String): StakeSubmitResponse

    companion object {
        const val STAKE_WALLET = "UKVAN7ORIUX7Y6QJFYQ4YGQAZD3RAC7QTDB73S2E5MSILUWAA7FJ6N7WLU"
        /** Claim fee receiver (user_pays mode): 1000 microALGO, Claim.tsx parity. */
        const val CLAIM_FEE_WALLET = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"
        const val CLAIM_FEE_MICROALGO = 1_000L
        /** Minimum spendable ALGO the dashboard requires before a claim/stake (0.002 ALGO). */
        const val MIN_ALGO_BUFFER_MICRO = 2_000L
    }
}
