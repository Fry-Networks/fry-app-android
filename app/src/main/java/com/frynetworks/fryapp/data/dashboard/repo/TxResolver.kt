package com.frynetworks.fryapp.data.dashboard.repo

sealed interface TxResolution {
    /** Known to a node or an indexer: carry on from this transaction, never send another. [round] is the confirmed round when known. */
    data class Confirmed(val txId: String, val round: Long?) : TxResolution

    /** Provably dead: the only verdict that lets the transfer be built again. */
    data object Absent : TxResolution

    /** Anything else, including every failure. Blocks a rebuild. */
    data object Unknown : TxResolution
}

/** Decides what became of a transfer that may have reached the network. Never throws except for coroutine cancellation. */
interface TxResolver {
    /** [lastValid] is the last round the transaction could still be included in; null never yields [TxResolution.Absent]. */
    suspend fun resolve(txId: String, lastValid: Long?): TxResolution

    /**
     * For a transfer whose id was never learned: finds it by receiver, asset ([assetId] 0 = ALGO payment) and [amountMicro] among
     * [sender]'s transactions since [minRound]. Confirmed only when both indexers name the same single transaction; never Absent.
     */
    suspend fun findTransfer(sender: String, receiver: String, assetId: Long, amountMicro: Long, minRound: Long?): TxResolution
}

/** The resolver a view model has when it is given none: it can never say a transfer is gone. */
object FailClosedTxResolver : TxResolver {
    override suspend fun resolve(txId: String, lastValid: Long?): TxResolution = TxResolution.Unknown

    override suspend fun findTransfer(sender: String, receiver: String, assetId: Long, amountMicro: Long, minRound: Long?): TxResolution =
        TxResolution.Unknown
}
