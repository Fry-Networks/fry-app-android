package com.frynetworks.fryapp.data.pending

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The binding store and the one new field of the fee record: written per record, top-level schema 1, read back exactly. */
class ClaimFeeBindingStoreTest {

    private val sender = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val miner = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val key = "$sender|CLAIM_FEE|$miner|all"
    private val prefs = PendingTransferStoreTest.FakePrefs()
    private fun store() = SharedPrefsClaimFeeBindingStore(prefs)
    private fun get() = store().get(sender, miner, "all")

    @Test
    fun `a binding round trips through a new store over the same preferences`() {
        val bound = ClaimFeeBinding(ClaimFeeBinding.USER_PAYS, "CLAIMTX", "G1", 1_500L, rebound = true)
        assertTrue(store().put(sender, miner, "all", bound))
        assertEquals(BindingLookup.Found(bound), get())
        val custodial = ClaimFeeBinding(ClaimFeeBinding.CUSTODIAL, "CUSTODIALTX", null, null, consumed = true)
        store().put(sender, miner, "all", custodial)
        assertEquals(BindingLookup.Found(custodial), get())
        val unbound = ClaimFeeBinding(ClaimFeeBinding.UNBOUND, null, null, null, rebound = true)
        store().put(sender, miner, "all", unbound)
        assertEquals(BindingLookup.Found(unbound), get())
    }

    @Test
    fun `nothing stored is None and remove empties the key`() {
        assertEquals(BindingLookup.None, get())
        store().put(sender, miner, "all", ClaimFeeBinding(ClaimFeeBinding.CUSTODIAL, "T", null, null))
        assertTrue(store().remove(sender, miner, "all"))
        assertEquals(BindingLookup.None, get())
    }

    @Test
    fun `a failed commit is reported and nothing else is written`() {
        prefs.commitResult = false
        assertFalse(store().put(sender, miner, "all", ClaimFeeBinding(ClaimFeeBinding.CUSTODIAL, "T", null, null)))
    }

    @Test
    fun `an unknown schema, an unknown mode or garbage is unreadable`() {
        store().put(sender, miner, "all", ClaimFeeBinding(ClaimFeeBinding.CUSTODIAL, "T", null, null))
        val good = prefs.map[key] as String
        for (bad in listOf(good.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), good.replace("custodial", "other"), "{nope", "[]")) {
            prefs.map[key] = bad
            assertEquals(bad, BindingLookup.Unreadable, get())
        }
    }

    @Test
    fun `the fee record writes feeVersion 2 for a claim fee only and a record without it is legacy`() {
        val feePrefs = PendingTransferStoreTest.FakePrefs()
        fun record(kind: TransferKind) = PendingTransfer(
            kind = kind, sender = sender, minerKey = miner, row = "all", assetId = 0L, amount = "1000", amountMicro = 1_000L,
            receiver = "RCV", txId = "FEETX", firstValid = 10L, lastValid = 1_010L, builtRound = 9L, state = TransferState.PRE_SUBMIT,
            createdAt = 1_700_000_000_000L,
        )
        val feeStore = SharedPrefsPendingTransferStore(feePrefs)
        feeStore.put(record(TransferKind.CLAIM_FEE))
        val text = feePrefs.map[key] as String
        assertTrue(text, text.contains("\"schemaVersion\":1") && text.contains("\"feeVersion\":2"))
        assertEquals(PendingLookup.Found(record(TransferKind.CLAIM_FEE)), feeStore.get(sender, TransferKind.CLAIM_FEE, miner, "all"))
        feePrefs.map[key] = text.replace(",\"feeVersion\":2", "")
        val legacy = (feeStore.get(sender, TransferKind.CLAIM_FEE, miner, "all") as PendingLookup.Found).record
        assertEquals(1, legacy.feeVersion)
        feeStore.put(record(TransferKind.STAKE))
        assertFalse("stake JSON is unchanged", (feePrefs.map["$sender|STAKE|$miner|all"] as String).contains("feeVersion"))
    }
}
