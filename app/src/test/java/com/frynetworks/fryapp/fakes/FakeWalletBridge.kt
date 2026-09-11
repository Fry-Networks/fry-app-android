package com.frynetworks.fryapp.fakes

import com.frynetworks.fryapp.wallet.BridgeErrorCode
import com.frynetworks.fryapp.wallet.BridgeEvent
import com.frynetworks.fryapp.wallet.BridgeException
import com.frynetworks.fryapp.wallet.TxnSummary
import com.frynetworks.fryapp.wallet.TxnToSign
import com.frynetworks.fryapp.wallet.WalletAccount
import com.frynetworks.fryapp.wallet.WalletBridge
import com.frynetworks.fryapp.wallet.WalletVendor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Scripted wallet bridge: [reconnectAddress] / [connectAddress] drive the connection step,
 * [signBehaviour] decides whether a signature is approved, rejected, or times out, and every
 * built/signed/submitted transaction is recorded so tests can assert the exact sequence.
 */
class FakeWalletBridge : WalletBridge {
    val eventFlow = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 8)
    override val events: Flow<BridgeEvent> = eventFlow

    /** Address returned by [reconnect]; null = no persisted wallet session. */
    var reconnectAddress: String? = null
    /** Address returned by a full [connect]; null = throws USER_REJECTED. */
    var connectAddress: String? = null
    var reconnectError: BridgeException? = null

    /** Per-call sign behaviour, consumed in order; the last entry repeats. */
    var signBehaviour: List<SignBehaviour> = listOf(SignBehaviour.Approve)
    var submitError: BridgeException? = null
    var submitTxIds: List<String> = listOf("SUBMITTEDTX")

    val built = mutableListOf<String>()
    val signRequests = mutableListOf<List<List<TxnToSign>>>()
    val submitted = mutableListOf<List<String>>()
    var disconnects = 0
    private var signCalls = 0
    private var buildCounter = 0

    sealed interface SignBehaviour {
        data object Approve : SignBehaviour
        data object Reject : SignBehaviour
        data object Timeout : SignBehaviour
        data object ReturnNull : SignBehaviour
    }

    override suspend fun connect(vendor: WalletVendor): WalletAccount {
        eventFlow.tryEmit(BridgeEvent.OpenUri("wc:fake@1", vendor))
        val address = connectAddress ?: throw BridgeException(BridgeErrorCode.USER_REJECTED, "Connection rejected")
        return WalletAccount(address, vendor)
    }

    override suspend fun reconnect(vendor: WalletVendor): WalletAccount? {
        reconnectError?.let { throw it }
        return reconnectAddress?.let { WalletAccount(it, vendor) }
    }

    override suspend fun disconnect() {
        disconnects++
    }

    override suspend fun buildPayment(sender: String, receiver: String, amountMicro: Long, noteUtf8: String?): String =
        record("pay|$sender|$receiver|$amountMicro|${noteUtf8 ?: ""}")

    override suspend fun buildAssetTransfer(sender: String, receiver: String, assetId: Long, amountMicro: Long, noteUtf8: String?): String =
        record("axfer|$sender|$receiver|$assetId|$amountMicro|${noteUtf8 ?: ""}")

    override suspend fun buildOptIn(sender: String, assetId: Long): String = record("optin|$sender|$assetId")

    private fun record(txn: String): String {
        buildCounter++
        val encoded = "$txn#$buildCounter"
        built += encoded
        return encoded
    }

    override suspend fun signTxns(groups: List<List<TxnToSign>>): List<List<String?>> {
        signRequests += groups
        eventFlow.tryEmit(BridgeEvent.OpenUri("perawallet-wc://", WalletVendor.PERA))
        val behaviour = signBehaviour.getOrNull(signCalls) ?: signBehaviour.last()
        signCalls++
        return when (behaviour) {
            SignBehaviour.Approve -> groups.map { group -> group.map { if (it.sign) "signed(${it.txnB64})" else null } }
            SignBehaviour.Reject -> throw BridgeException(BridgeErrorCode.USER_REJECTED, "Confirmation Failed(4100): rejected")
            SignBehaviour.Timeout -> throw BridgeException(BridgeErrorCode.TIMEOUT, "signTxns timed out")
            SignBehaviour.ReturnNull -> groups.map { group -> group.map { null } }
        }
    }

    override suspend fun submit(signedB64: List<String>, waitRounds: Int): List<String> {
        submitted += signedB64
        submitError?.let { throw it }
        return submitTxIds
    }

    override suspend fun decodeTxn(txnB64: String): TxnSummary {
        val parts = txnB64.substringBefore('#').split('|')
        return when (parts.firstOrNull()) {
            "pay" -> TxnSummary("pay", parts[1], parts[2], parts[3].toLong(), null, parts.getOrNull(4)?.ifEmpty { null }, null)
            "axfer" -> TxnSummary("axfer", parts[1], parts[2], parts[4].toLong(), parts[3].toLong(), parts.getOrNull(5)?.ifEmpty { null }, null)
            "optin" -> TxnSummary("axfer", parts[1], parts[1], 0L, parts[2].toLong(), null, null)
            else -> TxnSummary("unknown", null, null, null, null, null, null)
        }
    }
}
