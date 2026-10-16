package com.frynetworks.fryapp.data.pending

import android.content.SharedPreferences
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What a paid claim fee is tied to. [mode] is `user_pays` or `custodial`; [claimTxId] is the user leg's id (user_pays) or the custodial
 * transaction's id; [claimLastValid] is the user leg's last valid round (null = unknown, so it can never be proven absent).
 * [mode] `unbound` keeps only the [rebound] flag of a fee whose envelope was never signed.
 */
data class ClaimFeeBinding(
    val mode: String,
    val claimTxId: String?,
    val groupId: String?,
    val claimLastValid: Long?,
    /** The fee has been moved to a second envelope once; it is never moved again. */
    val rebound: Boolean = false,
    /** The claim completed: the fee is spent and may only be removed, never reused. */
    val consumed: Boolean = false,
) {
    companion object {
        const val USER_PAYS = "user_pays"
        const val CUSTODIAL = "custodial"
        const val UNBOUND = "unbound"

        /** A user leg was signed whose id could not be computed: the fee is never reused, cleared or moved, only consumed or supported. */
        const val UNTRACKED = "user_pays_untracked"
    }
}

sealed interface BindingLookup {
    data object None : BindingLookup

    data class Found(val binding: ClaimFeeBinding) : BindingLookup

    /** Something is stored there that cannot be understood: never treated as "no binding". */
    data object Unreadable : BindingLookup
}

/**
 * Where each paid claim fee (in [PendingTransferStore], kind CLAIM_FEE) is tied to its claim, keyed like the fee record itself
 * (sender, miner key, reward row). Kept apart from the fee record so the record's own writes stay as they were in 0.4.7.
 * Writes report whether they reached storage; false means the caller must not go on to sign.
 */
interface ClaimFeeBindingStore {
    fun get(sender: String, minerKey: String, row: String): BindingLookup

    fun put(sender: String, minerKey: String, row: String, binding: ClaimFeeBinding): Boolean

    fun remove(sender: String, minerKey: String, row: String): Boolean
}

private fun bindingKey(sender: String, minerKey: String, row: String) = "$sender|CLAIM_FEE|$minerKey|$row"

/** SharedPreferences "fry_claim_fee_binding". Every write and clear is a synchronous `commit()`, never `apply()`. */
class SharedPrefsClaimFeeBindingStore(private val prefs: SharedPreferences) : ClaimFeeBindingStore {

    @Synchronized
    override fun get(sender: String, minerKey: String, row: String): BindingLookup {
        val key = bindingKey(sender, minerKey, row)
        if (!prefs.contains(key)) return BindingLookup.None
        return decode(prefs.getString(key, null))?.let { BindingLookup.Found(it) } ?: BindingLookup.Unreadable
    }

    @Synchronized
    override fun put(sender: String, minerKey: String, row: String, binding: ClaimFeeBinding): Boolean =
        prefs.edit().putString(bindingKey(sender, minerKey, row), encode(binding).toString()).commit()

    @Synchronized
    override fun remove(sender: String, minerKey: String, row: String): Boolean =
        prefs.edit().remove(bindingKey(sender, minerKey, row)).commit()

    private fun encode(b: ClaimFeeBinding) = JsonObject().apply {
        addProperty("schemaVersion", SCHEMA_VERSION)
        addProperty("mode", b.mode)
        b.claimTxId?.let { addProperty("claimTxId", it) }
        b.groupId?.let { addProperty("groupId", it) }
        b.claimLastValid?.let { addProperty("claimLastValid", it) }
        if (b.rebound) addProperty("rebound", true)
        if (b.consumed) addProperty("consumed", true)
    }

    /** null for anything that is not exactly a schema-1 binding of a known mode. */
    private fun decode(text: String?): ClaimFeeBinding? = try {
        val o = JsonParser.parseString(text ?: return null).asJsonObject
        val mode = o.get("mode").asString
        if (o.get("schemaVersion")?.asInt != SCHEMA_VERSION || mode !in MODES) {
            null
        } else {
            ClaimFeeBinding(
                mode = mode,
                claimTxId = o.optString("claimTxId"),
                groupId = o.optString("groupId"),
                claimLastValid = o.get("claimLastValid")?.takeIf { !it.isJsonNull }?.asLong,
                rebound = o.get("rebound")?.asBoolean ?: false,
                consumed = o.get("consumed")?.asBoolean ?: false,
            )
        }
    } catch (e: RuntimeException) {
        null
    }

    private fun JsonObject.optString(name: String): String? = get(name)?.takeIf { !it.isJsonNull }?.asString

    private companion object {
        const val SCHEMA_VERSION = 1
        val MODES = setOf(ClaimFeeBinding.USER_PAYS, ClaimFeeBinding.CUSTODIAL, ClaimFeeBinding.UNBOUND, ClaimFeeBinding.UNTRACKED)
    }
}

/** Same contract without persistence: the default for a view model that is given no store. */
class InMemoryClaimFeeBindingStore : ClaimFeeBindingStore {
    private val entries = LinkedHashMap<String, ClaimFeeBinding>()

    @Synchronized
    override fun get(sender: String, minerKey: String, row: String): BindingLookup =
        entries[bindingKey(sender, minerKey, row)]?.let { BindingLookup.Found(it) } ?: BindingLookup.None

    @Synchronized
    override fun put(sender: String, minerKey: String, row: String, binding: ClaimFeeBinding): Boolean {
        entries[bindingKey(sender, minerKey, row)] = binding
        return true
    }

    @Synchronized
    override fun remove(sender: String, minerKey: String, row: String): Boolean {
        entries.remove(bindingKey(sender, minerKey, row))
        return true
    }
}
