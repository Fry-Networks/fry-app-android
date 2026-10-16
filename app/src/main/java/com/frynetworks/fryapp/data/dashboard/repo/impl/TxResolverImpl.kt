package com.frynetworks.fryapp.data.dashboard.repo.impl

import com.frynetworks.fryapp.data.dashboard.api.IndexerApi
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.TxResolution
import com.frynetworks.fryapp.data.dashboard.repo.TxResolver
import com.frynetworks.fryapp.data.dashboard.repo.TxStatus
import com.frynetworks.fryapp.network.dashboard.DashboardConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ResponseBody

/**
 * [TxResolution.Absent] needs ALL of: algod's pool answered 404 for the txid and algod's round is past `lastValid`; BOTH indexers
 * answered a definitive JSON 404 ("no transaction found") for it; BOTH indexers' health reports a healthy database and a round past
 * `lastValid`. Any 200 naming the txid is [TxResolution.Confirmed]. A 5xx, 429, timeout, malformed body, disagreement, lagging
 * round or network error is never read as a 404.
 */
class TxResolverImpl(
    private val algod: AlgodRepository,
    private val indexer: IndexerApi,
    private val indexerUrls: List<String> = listOf(DashboardConfig.INDEXER_ALGONODE_URL, DashboardConfig.INDEXER_NODELY_URL),
    private val callTimeoutMillis: Long = CALL_TIMEOUT_MILLIS,
    /** Where the network calls run; the timeout is measured on its clock. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TxResolver {

    init {
        require(indexerUrls.size == 2 && indexerUrls.distinct().size == 2 && indexerUrls.all { it.startsWith("https://") }) {
            "exactly two distinct https indexers are required"
        }
    }

    private sealed interface Reply {
        data class Ok(val json: JsonObject) : Reply

        /** A 404 whose JSON body says the transaction is not known. */
        data object NotFound : Reply

        data object Bad : Reply
    }

    override suspend fun resolve(txId: String, lastValid: Long?): TxResolution {
        if (!TXID.matches(txId)) return TxResolution.Unknown
        val status = guarded { algod.txStatus(txId) } ?: TxStatus.Unknown
        when (status) {
            is TxStatus.Confirmed -> return TxResolution.Confirmed(txId, status.round)
            TxStatus.InPool, is TxStatus.Rejected -> return TxResolution.Unknown
            TxStatus.Absent, TxStatus.Unknown -> Unit
        }
        val polled = coroutineScope {
            indexerUrls.map { base -> async { get("$base/v2/transactions/$txId", mustName = txId) to get("$base/health") } }.awaitAll()
        }
        for ((tx, _) in polled) {
            val json = (tx as? Reply.Ok)?.json ?: continue
            val transaction = json.objOrNull("transaction") ?: continue
            if (transaction.strOrNull("id") == txId) {
                return TxResolution.Confirmed(txId, transaction.numOrNull("confirmed-round"))
            }
        }
        if (lastValid == null) return TxResolution.Unknown
        if (polled.any { it.first !is Reply.NotFound }) return TxResolution.Unknown
        if (polled.any { (healthRound(it.second) ?: Long.MIN_VALUE) <= lastValid }) return TxResolution.Unknown
        if (guarded { algod.poolHasNoRecord(txId) } != true) return TxResolution.Unknown
        val round = guarded { algod.currentRound() } ?: return TxResolution.Unknown
        return if (round > lastValid) TxResolution.Absent else TxResolution.Unknown
    }

    override suspend fun findTransfer(sender: String, receiver: String, assetId: Long, amountMicro: Long, minRound: Long?): TxResolution {
        if (minRound == null || minRound < 0 || !ADDRESS.matches(sender) || !ADDRESS.matches(receiver)) return TxResolution.Unknown
        val type = if (assetId == 0L) "pay" else "axfer"
        val asset = if (assetId == 0L) "" else "&asset-id=$assetId"
        val matches = coroutineScope {
            indexerUrls.map { base ->
                async {
                    val reply = get("$base/v2/accounts/$sender/transactions?min-round=$minRound&limit=$PAGE&tx-type=$type$asset")
                    matching((reply as? Reply.Ok)?.json, receiver, assetId, amountMicro)
                }
            }.awaitAll()
        }
        val first = matches.firstOrNull() ?: return TxResolution.Unknown
        if (first.size != 1 || matches.any { it != first }) return TxResolution.Unknown
        return TxResolution.Confirmed(first.single(), null)
    }

    /** The ids of the transfers on a page that match; null (never "no match, all clear") when the page is unusable or may be cut short. */
    private fun matching(page: JsonObject?, receiver: String, assetId: Long, amountMicro: Long): Set<String>? {
        val list = page?.get("transactions")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (list.size() >= PAGE) return null
        val ids = HashSet<String>()
        for (element in list) {
            val tx = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val id = tx.strOrNull("id") ?: return null
            val body = tx.objOrNull(if (assetId == 0L) "payment-transaction" else "asset-transfer-transaction") ?: continue
            val sameAsset = assetId == 0L || body.numOrNull("asset-id") == assetId
            if (sameAsset && body.strOrNull("receiver") == receiver && body.numOrNull("amount") == amountMicro) ids += id
        }
        return ids
    }

    private fun healthRound(reply: Reply): Long? {
        val json = (reply as? Reply.Ok)?.json ?: return null
        val db = json.get("db-available")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        if (db != true) return null
        val migrating = json.get("is-migrating")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        if (migrating != false) return null
        return json.numOrNull("round")
    }

    /** One GET, never throwing and never longer than the call timeout. */
    /** [mustName], when given, is a txid a 404 message has to name to count as a definitive not-found. */
    private suspend fun get(url: String, mustName: String? = null): Reply = withContext(dispatcher) {
        try {
            withTimeoutOrNull(callTimeoutMillis) { read(url, mustName) } ?: Reply.Bad
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Reply.Bad
        }
    }

    private suspend fun read(url: String, mustName: String?): Reply {
        val response = indexer.get(url)
        try {
            val text = readCapped(if (response.isSuccessful) response.body() else response.errorBody()) ?: return Reply.Bad
            val json = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return Reply.Bad
            return when (response.code()) {
                200 -> Reply.Ok(json)
                404 -> {
                    val message = json.strOrNull("message")
                    if (message?.contains("no transaction found") == true && (mustName == null || message.contains(mustName))) Reply.NotFound else Reply.Bad
                }
                else -> Reply.Bad
            }
        } finally {
            response.errorBody()?.close()
            response.body()?.close()
        }
    }

    /** The body as text, or null when it is missing or longer than [MAX_BODY_BYTES]. */
    private fun readCapped(body: ResponseBody?): String? {
        val source = body?.source() ?: return null
        source.request(MAX_BODY_BYTES + 1L)
        if (source.buffer.size > MAX_BODY_BYTES) return null
        return source.buffer.readUtf8()
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.objOrNull(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.strOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.numOrNull(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let { runCatching { it.asLong }.getOrNull() }

    companion object {
        const val CALL_TIMEOUT_MILLIS = 15_000L

        /** An indexer reply longer than this is not read: it is Unknown, whatever it says. */
        const val MAX_BODY_BYTES = 64 * 1024
        private const val PAGE = 100
        private val TXID = Regex("[A-Z2-7]{52}")
        private val ADDRESS = Regex("[A-Z2-7]{58}")
    }
}
