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

    /**
     * Where [txId] stands. The default is [TxStatus.Unknown], which never allows a stake to be rebuilt; an
     * implementation reports [TxStatus.Absent] only when it can prove the transaction is in neither the pool nor the chain.
     */
    suspend fun txStatus(txId: String): TxStatus = TxStatus.Unknown

    /** The node's latest round, or null when unknown (which keeps a broadcast stake from ever being rebuilt). */
    suspend fun currentRound(): Long? = null

    /** True only when the node answered a definitive 404 for [txId] in its pending pool; false for anything else, errors included. */
    suspend fun poolHasNoRecord(txId: String): Boolean = false
}

sealed interface TxStatus {
    data class Confirmed(val round: Long) : TxStatus

    /** Known to the pending pool with no confirmed round yet. */
    data object InPool : TxStatus

    /** The pool reported an error for it. Not proof that it can no longer land. */
    data class Rejected(val reason: String) : TxStatus

    /** In neither the pool nor the chain. */
    data object Absent : TxStatus

    data object Unknown : TxStatus
}
