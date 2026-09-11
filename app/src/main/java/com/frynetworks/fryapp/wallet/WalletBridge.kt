package com.frynetworks.fryapp.wallet

import kotlinx.coroutines.flow.Flow

/** Wallet apps the bridge can drive (the official Pera/Defly web SDKs run inside the bridge page). */
enum class WalletVendor(val id: String, val packageName: String, val displayName: String) {
    PERA("pera", "com.algorand.android", "Pera"),
    DEFLY("defly", "io.blockshake.defly.app", "Defly");

    val playStoreUrl: String get() = "https://play.google.com/store/apps/details?id=$packageName"

    companion object {
        fun fromId(id: String?): WalletVendor? = entries.firstOrNull { it.id == id }
    }
}

data class WalletAccount(val address: String, val vendor: WalletVendor)

/** One transaction in a group handed to the wallet; `sign=false` entries are shown but not signed. */
data class TxnToSign(val txnB64: String, val sign: Boolean)

data class TxnSummary(
    val type: String,
    val sender: String?,
    val receiver: String?,
    val amount: Long?,
    val assetId: Long?,
    val note: String?,
    val txId: String?,
)

enum class BridgeErrorCode {
    USER_REJECTED, PENDING_REQUEST, NOT_CONNECTED, SESSION_EXPIRED, TIMEOUT,
    WALLET_NOT_INSTALLED, NETWORK, BRIDGE_RESET, INVALID_ARGS, UNKNOWN;

    companion object {
        fun fromWire(code: String?): BridgeErrorCode = entries.firstOrNull { it.name == code } ?: UNKNOWN
    }
}

class BridgeException(
    val code: BridgeErrorCode,
    message: String,
    val detail: String? = null,
) : Exception(message)

sealed interface BridgeEvent {
    /** The wallet app must be brought to the foreground with this URI (wc:, perawallet-wc://, defly-wc://). */
    data class OpenUri(val uri: String, val vendor: WalletVendor?) : BridgeEvent
    data object Ready : BridgeEvent
    data object Disconnected : BridgeEvent
}

/**
 * Kotlin-facing contract of the invisible signing bridge. Every call maps to one JSON-RPC
 * request to the bridge page; failures surface as [BridgeException].
 */
interface WalletBridge {
    val events: Flow<BridgeEvent>

    suspend fun connect(vendor: WalletVendor): WalletAccount
    suspend fun reconnect(vendor: WalletVendor): WalletAccount?
    suspend fun disconnect()

    suspend fun buildPayment(sender: String, receiver: String, amountMicro: Long, noteUtf8: String?): String
    suspend fun buildAssetTransfer(sender: String, receiver: String, assetId: Long, amountMicro: Long, noteUtf8: String?): String
    suspend fun buildOptIn(sender: String, assetId: Long): String

    /** Returns signed base64 per entry, `null` where `sign=false`. */
    suspend fun signTxns(groups: List<List<TxnToSign>>): List<List<String?>>
    suspend fun submit(signedB64: List<String>, waitRounds: Int = 4): List<String>
    suspend fun decodeTxn(txnB64: String): TxnSummary
}
