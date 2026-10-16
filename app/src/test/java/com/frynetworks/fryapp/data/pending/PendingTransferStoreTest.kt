package com.frynetworks.fryapp.data.pending

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingTransferStoreTest {

    /** In-memory SharedPreferences whose commit() can be made to fail; apply() is counted so a test can prove it is never used. */
    class FakePrefs : SharedPreferences {
        val map = HashMap<String, Any?>()
        var commitResult = true

        /** The next this many commits fail, then they succeed again. */
        var failCommits = 0
        var applyCalls = 0
        var commitCalls = 0

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun contains(key: String) = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val puts = HashMap<String, Any?>()
            private val removes = HashSet<String>()
            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { removes += key }
            override fun clear() = apply { removes += map.keys }
            override fun commit(): Boolean {
                commitCalls++
                if (failCommits > 0) {
                    failCommits--
                    return false
                }
                if (!commitResult) return false
                map.putAll(puts)
                removes.forEach { map.remove(it) }
                return true
            }
            override fun apply() {
                applyCalls++
                map.putAll(puts)
                removes.forEach { map.remove(it) }
            }
        }
    }

    private val a = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val b = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
    private val miner = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"

    private fun record(
        sender: String = a,
        kind: TransferKind = TransferKind.STAKE,
        minerKey: String = miner,
        row: String = "registration",
        txId: String? = "TXID1",
        state: TransferState = TransferState.PRE_SUBMIT,
    ) = PendingTransfer(
        kind = kind, sender = sender, minerKey = minerKey, row = row, assetId = 2_681_521_901L, amount = "5", amountMicro = 5_000_000L,
        receiver = "RCV", txId = txId, firstValid = 10L, lastValid = 1_010L, builtRound = 9L, state = state, createdAt = 1_700_000_000_000L,
    )

    private fun store(prefs: FakePrefs = FakePrefs()) = SharedPrefsPendingTransferStore(prefs)

    @Test
    fun `a record survives a new store over the same preferences`() {
        val prefs = FakePrefs()
        assertTrue(store(prefs).put(record()))
        val found = store(prefs).find(a, TransferKind.STAKE)
        assertEquals(PendingLookup.Found(record()), found)
    }

    @Test
    fun `nothing stored is None`() {
        assertEquals(PendingLookup.None, store().find(a, TransferKind.STAKE))
        assertEquals(PendingLookup.None, store().get(a, TransferKind.CLAIM_FEE, miner, "1"))
    }

    @Test
    fun `a record of one sender is never found for another sender`() {
        val s = store()
        s.put(record(sender = a))
        assertEquals(PendingLookup.None, s.find(b, TransferKind.STAKE))
        assertTrue(s.find(a, TransferKind.STAKE) is PendingLookup.Found)
    }

    @Test
    fun `kinds do not see each other`() {
        val s = store()
        s.put(record(kind = TransferKind.CLAIM_FEE, row = "3"))
        assertEquals(PendingLookup.None, s.find(a, TransferKind.STAKE))
        assertTrue(s.get(a, TransferKind.CLAIM_FEE, miner, "3") is PendingLookup.Found)
        assertEquals(PendingLookup.None, s.get(a, TransferKind.CLAIM_FEE, miner, "4"))
    }

    @Test
    fun `put overwrites the record under the same key`() {
        val s = store()
        s.put(record(txId = null, state = TransferState.POSSIBLY_BROADCAST))
        s.put(record(txId = "LATER"))
        assertEquals("LATER", (s.find(a, TransferKind.STAKE) as PendingLookup.Found).record.txId)
    }

    @Test
    fun `remove deletes only the named record and reports success`() {
        val s = store()
        s.put(record(row = "registration"))
        s.put(record(kind = TransferKind.CLAIM_FEE, row = "1"))
        assertTrue(s.remove(a, TransferKind.STAKE, miner, "registration"))
        assertEquals(PendingLookup.None, s.find(a, TransferKind.STAKE))
        assertTrue(s.get(a, TransferKind.CLAIM_FEE, miner, "1") is PendingLookup.Found)
    }

    @Test
    fun `a failed commit on put reports false and stores nothing`() {
        val prefs = FakePrefs().also { it.commitResult = false }
        assertFalse(store(prefs).put(record()))
        assertEquals(PendingLookup.None, store(FakePrefs().also { it.map.putAll(prefs.map) }).find(a, TransferKind.STAKE))
    }

    @Test
    fun `a failed commit on remove reports false and keeps the record`() {
        val prefs = FakePrefs()
        val s = store(prefs)
        s.put(record())
        prefs.commitResult = false
        assertFalse(s.remove(a, TransferKind.STAKE, miner, "registration"))
        assertTrue(s.find(a, TransferKind.STAKE) is PendingLookup.Found)
    }

    @Test
    fun `writes and clears use commit and never apply`() {
        val prefs = FakePrefs()
        val s = store(prefs)
        s.put(record())
        s.remove(a, TransferKind.STAKE, miner, "registration")
        assertEquals(0, prefs.applyCalls)
        assertEquals(2, prefs.commitCalls)
    }

    @Test
    fun `an unknown schema version is Unreadable and so blocks`() {
        val prefs = FakePrefs()
        val s = store(prefs)
        s.put(record())
        val key = prefs.map.keys.single()
        prefs.map[key] = (prefs.map[key] as String).replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        assertEquals(PendingLookup.Unreadable, s.find(a, TransferKind.STAKE))
        assertEquals(PendingLookup.Unreadable, s.get(a, TransferKind.STAKE, miner, "registration"))
    }

    @Test
    fun `a corrupt record is Unreadable`() {
        val prefs = FakePrefs()
        val s = store(prefs)
        s.put(record())
        prefs.map[prefs.map.keys.single()] = "{not json"
        assertEquals(PendingLookup.Unreadable, s.find(a, TransferKind.STAKE))
    }

    @Test
    fun `an unreadable record of another sender does not block this sender`() {
        val prefs = FakePrefs()
        val s = store(prefs)
        s.put(record(sender = b))
        prefs.map[prefs.map.keys.single()] = "garbage"
        assertEquals(PendingLookup.None, s.find(a, TransferKind.STAKE))
    }

    @Test
    fun `the persisted record carries no secret fields`() {
        val prefs = FakePrefs()
        store(prefs).put(record())
        val json = prefs.map.values.single() as String
        listOf("mnemonic", "seed", "password", "token", "secret", "privateKey", "signed").forEach {
            assertFalse("record must not mention $it: $json", json.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `the in-memory store behaves like the persistent one`() {
        val s = InMemoryPendingTransferStore()
        assertTrue(s.put(record()))
        assertEquals(PendingLookup.Found(record()), s.find(a, TransferKind.STAKE))
        assertEquals(PendingLookup.None, s.find(b, TransferKind.STAKE))
        assertTrue(s.remove(a, TransferKind.STAKE, miner, "registration"))
        assertEquals(PendingLookup.None, s.find(a, TransferKind.STAKE))
    }

    @Test
    fun `putNew writes when nothing is there`() {
        val s = store()
        assertTrue(s.putNew(record()))
        assertTrue(s.find(a, TransferKind.STAKE) is PendingLookup.Found)
    }

    @Test
    fun `putNew refuses a second stake record of the same sender even for another miner`() {
        val s = store()
        assertTrue(s.putNew(record(minerKey = miner, txId = "FIRST")))
        assertFalse(s.putNew(record(minerKey = "FEM-OTHER0000000000000000000000000000", txId = "SECOND")))
        assertEquals("FIRST", (s.find(a, TransferKind.STAKE) as PendingLookup.Found).record.txId)
    }

    @Test
    fun `putNew refuses the same key with a different txid and leaves the first intact`() {
        val s = store()
        assertTrue(s.putNew(record(kind = TransferKind.CLAIM_FEE, row = "3", txId = "FIRST")))
        assertFalse(s.putNew(record(kind = TransferKind.CLAIM_FEE, row = "3", txId = "SECOND")))
        assertEquals("FIRST", (s.get(a, TransferKind.CLAIM_FEE, miner, "3") as PendingLookup.Found).record.txId)
    }

    @Test
    fun `putNew allows another claim row and another sender`() {
        val s = store()
        assertTrue(s.putNew(record(kind = TransferKind.CLAIM_FEE, row = "3")))
        assertTrue(s.putNew(record(kind = TransferKind.CLAIM_FEE, row = "4")))
        assertTrue(s.putNew(record(sender = b)))
    }

    @Test
    fun `putNew reports a failed commit and stores nothing`() {
        val prefs = FakePrefs().also { it.commitResult = false }
        assertFalse(store(prefs).putNew(record()))
        assertTrue(prefs.map.isEmpty())
    }

    @Test
    fun `a permanently blocked key reads as Unreadable and cannot be removed in this process`() {
        val prefs = FakePrefs()
        val s = store(prefs)
        s.put(record())
        s.blockPermanently(a, TransferKind.STAKE, miner, "registration")
        assertEquals(PendingLookup.Unreadable, s.find(a, TransferKind.STAKE))
        assertEquals(PendingLookup.Unreadable, s.get(a, TransferKind.STAKE, miner, "registration"))
        assertFalse(s.remove(a, TransferKind.STAKE, miner, "registration"))
        assertTrue(prefs.map.isNotEmpty())
        assertEquals(PendingLookup.None, s.find(b, TransferKind.STAKE))
    }

    @Test
    fun `a permanent block holds even when the record was never written`() {
        val s = store()
        s.blockPermanently(a, TransferKind.CLAIM_FEE, miner, "all")
        assertEquals(PendingLookup.Unreadable, s.get(a, TransferKind.CLAIM_FEE, miner, "all"))
        assertFalse(s.putNew(record(kind = TransferKind.CLAIM_FEE, row = "all")))
    }

    @Test
    fun `the in-memory store has the same putNew and block behaviour`() {
        val s = InMemoryPendingTransferStore()
        assertTrue(s.putNew(record()))
        assertFalse(s.putNew(record(txId = "X")))
        s.blockPermanently(a, TransferKind.STAKE, miner, "registration")
        assertEquals(PendingLookup.Unreadable, s.find(a, TransferKind.STAKE))
        assertFalse(s.remove(a, TransferKind.STAKE, miner, "registration"))
    }
}
