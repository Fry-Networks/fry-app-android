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
    /** Claim fees only: 2 for a record written by 0.4.8 or later, 1 for one written by 0.4.7 (which says nothing about the claim it paid for). */
    val feeVersion: Int = FEE_VERSION,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val FEE_VERSION = 2
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

    /** Writes (or overwrites) the record under its own key. */
    fun put(record: PendingTransfer): Boolean

    /**
     * Writes [record] only when nothing is recorded yet, atomically with every other call: for a stake any record of the sender
     * (every miner and context) refuses it, for a claim fee a record under the same key does. False = refused or not committed.
     */
    fun putNew(record: PendingTransfer): Boolean

    fun remove(sender: String, kind: TransferKind, minerKey: String, row: String): Boolean

    /**
     * For the rest of this process: this key reads as [PendingLookup.Unreadable], cannot be removed and takes no [putNew]. Used when
     * the record cannot be trusted and could not be rewritten, so no transfer may be rebuilt.
     */
    fun blockPermanently(sender: String, kind: TransferKind, minerKey: String, row: String)
}

private fun storageKey(sender: String, kind: TransferKind, minerKey: String, row: String) = "$sender|${kind.name}|$minerKey|$row"

private fun storagePrefix(sender: String, kind: TransferKind) = "$sender|${kind.name}|"

/** True when [blocked] holds the record's own key, or (for a stake, which blocks every miner) any key of its sender. */
private fun isBlocked(record: PendingTransfer, blocked: Set<String>): Boolean = when (record.kind) {
    TransferKind.STAKE -> blocked.any { it.startsWith(storagePrefix(record.sender, record.kind)) }
    TransferKind.CLAIM_FEE -> storageKey(record.sender, record.kind, record.minerKey, record.row) in blocked
}

/** SharedPreferences "fry_pending_transfer". Every write and clear is a synchronous `commit()`, never `apply()`. */
class SharedPrefsPendingTransferStore(private val prefs: SharedPreferences) : PendingTransferStore {
    private val blocked = HashSet<String>()

    @Synchronized
    override fun find(sender: String, kind: TransferKind): PendingLookup {
        val prefix = storagePrefix(sender, kind)
        if (blocked.any { it.startsWith(prefix) }) return PendingLookup.Unreadable
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
        if (key in blocked) return PendingLookup.Unreadable
        if (!prefs.contains(key)) return PendingLookup.None
        return decode(key, prefs.getString(key, null))?.let { PendingLookup.Found(it) } ?: PendingLookup.Unreadable
    }

    @Synchronized
    override fun put(record: PendingTransfer): Boolean =
        prefs.edit().putString(storageKey(record.sender, record.kind, record.minerKey, record.row), encode(record).toString()).commit()

    @Synchronized
    override fun putNew(record: PendingTransfer): Boolean {
        val key = storageKey(record.sender, record.kind, record.minerKey, record.row)
        if (isBlocked(record, blocked)) return false
        val taken = when (record.kind) {
            TransferKind.STAKE -> prefs.all.keys.any { it.startsWith(storagePrefix(record.sender, record.kind)) }
            TransferKind.CLAIM_FEE -> prefs.contains(key)
        }
        if (taken) return false
        return prefs.edit().putString(key, encode(record).toString()).commit()
    }

    @Synchronized
    override fun remove(sender: String, kind: TransferKind, minerKey: String, row: String): Boolean {
        val key = storageKey(sender, kind, minerKey, row)
        if (key in blocked) return false
        return prefs.edit().remove(key).commit()
    }

    @Synchronized
    override fun blockPermanently(sender: String, kind: TransferKind, minerKey: String, row: String) {
        blocked += storageKey(sender, kind, minerKey, row)
    }

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
        if (r.kind == TransferKind.CLAIM_FEE) addProperty("feeVersion", r.feeVersion)
    }

    /** null for anything that is not exactly a schema-1 record stored under the key its own fields give. */
    private fun decode(key: String, text: String?): PendingTransfer? = try {
        val o = JsonParser.parseString(text ?: return null).asJsonObject
        if (o.get("schemaVersion")?.asInt != PendingTransfer.SCHEMA_VERSION) {
            null
        } else {
            val kind = TransferKind.valueOf(o.get("kind").asString)
            val record = PendingTransfer(
                kind = kind,
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
                // A claim fee without the field was written by 0.4.7.
                feeVersion = if (kind == TransferKind.CLAIM_FEE) o.get("feeVersion")?.asInt ?: 1 else PendingTransfer.FEE_VERSION,
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
    private val blocked = HashSet<String>()

    @Synchronized
    override fun find(sender: String, kind: TransferKind): PendingLookup {
        val prefix = storagePrefix(sender, kind)
        if (blocked.any { it.startsWith(prefix) }) return PendingLookup.Unreadable
        return records.entries.firstOrNull { it.key.startsWith(prefix) }?.let { PendingLookup.Found(it.value) } ?: PendingLookup.None
    }

    @Synchronized
    override fun get(sender: String, kind: TransferKind, minerKey: String, row: String): PendingLookup {
        val key = storageKey(sender, kind, minerKey, row)
        if (key in blocked) return PendingLookup.Unreadable
        return records[key]?.let { PendingLookup.Found(it) } ?: PendingLookup.None
    }

    @Synchronized
    override fun put(record: PendingTransfer): Boolean {
        records[storageKey(record.sender, record.kind, record.minerKey, record.row)] = record
        return true
    }

    @Synchronized
    override fun putNew(record: PendingTransfer): Boolean {
        val key = storageKey(record.sender, record.kind, record.minerKey, record.row)
        if (isBlocked(record, blocked)) return false
        val taken = when (record.kind) {
            TransferKind.STAKE -> records.keys.any { it.startsWith(storagePrefix(record.sender, record.kind)) }
            TransferKind.CLAIM_FEE -> key in records
        }
        if (taken) return false
        records[key] = record
        return true
    }

    @Synchronized
    override fun remove(sender: String, kind: TransferKind, minerKey: String, row: String): Boolean {
        val key = storageKey(sender, kind, minerKey, row)
        if (key in blocked) return false
        records.remove(key)
        return true
    }

    @Synchronized
    override fun blockPermanently(sender: String, kind: TransferKind, minerKey: String, row: String) {
        blocked += storageKey(sender, kind, minerKey, row)
    }
}
