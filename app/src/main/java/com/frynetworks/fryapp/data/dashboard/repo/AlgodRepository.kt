package com.frynetworks.fryapp.data.dashboard.repo

/**
 * Read-only algod queries the claim/stake sheets need (public mainnet node from
 * `DashboardConfig.ALGOD_URL`; no token). Balances are on-chain micro units.
 */
interface AlgodRepository {
    suspend fun algoBalanceMicro(address: String): Long

    /** null when the account is not opted in to [assetId]. */
    suspend fun assetBalanceMicro(address: String, assetId: Long): Long?

    suspend fun isOptedIn(address: String, assetId: Long): Boolean = assetBalanceMicro(address, assetId) != null

    /** `auth-addr` of a rekeyed account, else null (Claim.tsx:223 rekey message). */
    suspend fun authAddress(address: String): String?

    /** Blocks until [txId] is confirmed or [rounds] rounds pass; throws DashboardException("TXN_NOT_CONFIRMED"). */
    suspend fun waitConfirmed(txId: String, rounds: Int = 4)
}
