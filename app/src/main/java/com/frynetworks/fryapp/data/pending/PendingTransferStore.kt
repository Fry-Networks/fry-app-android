package com.frynetworks.fryapp.data.pending

import android.content.SharedPreferences
import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class TransferKind { STAKE, CLAIM_FEE }

enum class TransferState {
    /** Signed and recorded; submit has not reported back (or may not have been reached). The txId is known. */
    PRE_SUBMIT,

    /** Handed to submit with no txId to resume from. Always blocks a rebuild and is never cleared automatically. */
    POSSIBLY_BROADCAST,
}

/**
 * A transfer that may be on the network. Holds no secret: addresses, ids, amounts and rounds only.
 * For a stake [row] is the stake context (`registration`, `node`, `verification:<tier>`); for a claim fee it is the reward row (`all` or its number).
 */
data class PendingTransfer(
    val schemaVersion: Int = SCHEMA_VERSION,
    val kind: TransferKind,
    val sender: String,
    val minerKey: String,
    val row: String,
    val assetId: Long,
    /** Whole-token amount as the dashboard records it (stake) or the micro-ALGO fee (claim), as a plain decimal string. */
    val amount: String,
    val amountMicro: Long,
    val receiver: String,
    val txId: String?,
    val firstValid: Long?,
    val lastValid: Long?,
    val builtRound: Long?,
    val state: TransferState,
    val createdAt: Long,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

sealed interface PendingLookup {
    data object None : PendingLookup

    data class Found(val record: PendingTransfer) : PendingLookup

    /** Something is stored there that cannot be understood (unknown schema, corrupt): treated as pending, never as nothing. */
    data object Unreadable : PendingLookup
}

/**
 * Durable record of a transfer that may already be on the network, keyed by sender + kind + miner + row so one account's
 * record never blocks another's. Nothing in the sign-out, session-clear or wallet-disconnect paths touches it.
 * Writes and clears report whether they reached storage; a false result means the caller must not go on to submit.
 */
interface PendingTransferStore {
    /** Any record of [sender] and [kind] (a stake blocks every further stake of that sender), or [PendingLookup.Unreadable] if one of them is. */
    fun find(sender: String, kind: TransferKind): PendingLookup

    fun get(sender: String, kind: TransferKind, minerKey: String, row: String): PendingLookup

    fun put(record: PendingTransfer): Boolean

    fun remove(sender: String, kind: TransferKind, minerKey: String, row: String): Boolean
}

private fun storageKey(sender: String, kind: TransferKind, minerKey: String, row: String) = "$sender|${kind.name}|$minerKey|$row"

private fun storagePrefix(sender: String, kind: TransferKind) = "$sender|${kind.name}|"

/** SharedPreferences "fry_pending_transfer". Every write and clear is a synchronous `commit()`, never `apply()`. */
class SharedPrefsPendingTransferStore(private val prefs: SharedPreferences) : PendingTransferStore {

    @Synchronized
    override fun find(sender: String, kind: TransferKind): PendingLookup {
        val prefix = storagePrefix(sender, kind)
        var found: PendingTransfer? = null
        for ((key, value) in prefs.all) {
            if (!key.startsWith(prefix)) continue
            val record = decode(key, value as? String) ?: return PendingLookup.Unreadable
            if (found == null) found = record
        }
        return found?.let { PendingLookup.Found(it) } ?: PendingLookup.None
    }

    @Synchronized
    override fun get(sender: String, kind: TransferKind, minerKey: String, row: String): PendingLookup {
        val key = storageKey(sender, kind, minerKey, row)
        if (!prefs.contains(key)) return PendingLookup.None
        return decode(key, prefs.getString(key, null))?.let { PendingLookup.Found(it) } ?: PendingLookup.Unreadable
    }

    @Synchronized
    override fun put(record: PendingTransfer): Boolean =
        prefs.edit().putString(storageKey(record.sender, record.kind, record.minerKey, record.row), encode(record).toString()).commit()

    @Synchronized
    override fun remove(sender: String, kind: TransferKind, minerKey: String, row: String): Boolean =
        prefs.edit().remove(storageKey(sender, kind, minerKey, row)).commit()

    private fun encode(r: PendingTransfer) = JsonObject().apply {
        addProperty("schemaVersion", r.schemaVersion)
        addProperty("kind", r.kind.name)
        addProperty("sender", r.sender)
        addProperty("minerKey", r.minerKey)
        addProperty("row", r.row)
        addProperty("assetId", r.assetId)
        addProperty("amount", r.amount)
        addProperty("amountMicro", r.amountMicro)
        addProperty("receiver", r.receiver)
        r.txId?.let { addProperty("txId", it) }
        r.firstValid?.let { addProperty("fv", it) }
        r.lastValid?.let { addProperty("lv", it) }
        r.builtRound?.let { addProperty("builtRound", it) }
        addProperty("state", r.state.name)
        addProperty("createdAt", r.createdAt)
    }

    /** null for anything that is not exactly a schema-1 record stored under the key its own fields give. */
    private fun decode(key: String, text: String?): PendingTransfer? = try {
        val o = JsonParser.parseString(text ?: return null).asJsonObject
        if (o.get("schemaVersion")?.asInt != PendingTransfer.SCHEMA_VERSION) {
            null
        } else {
            val record = PendingTransfer(
                kind = TransferKind.valueOf(o.get("kind").asString),
                sender = o.get("sender").asString,
                minerKey = o.get("minerKey").asString,
                row = o.get("row").asString,
                assetId = o.get("assetId").asLong,
                amount = o.get("amount").asString,
                amountMicro = o.get("amountMicro").asLong,
                receiver = o.get("receiver").asString,
                txId = o.optString("txId"),
                firstValid = o.optLong("fv"),
                lastValid = o.optLong("lv"),
                builtRound = o.optLong("builtRound"),
                state = TransferState.valueOf(o.get("state").asString),
                createdAt = o.get("createdAt").asLong,
            )
            record.takeIf { storageKey(it.sender, it.kind, it.minerKey, it.row) == key }
        }
    } catch (e: RuntimeException) {
        null
    }

    private fun JsonObject.optString(name: String): String? = get(name)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.optLong(name: String): Long? = get(name)?.takeIf { !it.isJsonNull }?.asLong
}

/** Same contract without persistence: the default for a view model that is given no store. */
class InMemoryPendingTransferStore : PendingTransferStore {
    private val records = LinkedHashMap<String, PendingTransfer>()

    @Synchronized
    override fun find(sender: String, kind: TransferKind): PendingLookup {
        val prefix = storagePrefix(sender, kind)
        return records.entries.firstOrNull { it.key.startsWith(prefix) }?.let { PendingLookup.Found(it.value) } ?: PendingLookup.None
    }

    @Synchronized
    override fun get(sender: String, kind: TransferKind, minerKey: String, row: String): PendingLookup =
        records[storageKey(sender, kind, minerKey, row)]?.let { PendingLookup.Found(it) } ?: PendingLookup.None

    @Synchronized
    override fun put(record: PendingTransfer): Boolean {
        records[storageKey(record.sender, record.kind, record.minerKey, record.row)] = record
        return true
    }

    @Synchronized
    override fun remove(sender: String, kind: TransferKind, minerKey: String, row: String): Boolean {
        records.remove(storageKey(sender, kind, minerKey, row))
        return true
    }
}
