package com.frynetworks.fryapp.data.pending

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ASA_OPTIN kind in the store: one exact key per sender and asset, never seen by a stake or claim-fee lookup. */
class AsaOptInStoreTest {

    private val sender = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val other = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
    private val miner = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"

    private fun optIn(asset: Long = 2_485_202_024L, who: String = sender, state: TransferState = TransferState.PRE_SUBMIT) = PendingTransfer(
        kind = TransferKind.ASA_OPTIN, sender = who, minerKey = "-", row = asset.toString(), assetId = asset, amount = "0", amountMicro = 0L,
        receiver = who, txId = "OPTINTX", firstValid = 10L, lastValid = 1_010L, builtRound = 9L, state = state, createdAt = 1_700_000_000_000L,
    )

    private fun stake() = optIn().copy(kind = TransferKind.STAKE, minerKey = miner, row = "registration", assetId = 1L)

    @Test
    fun `an opt-in record round trips under sender|ASA_OPTIN|-|assetId`() {
        val prefs = PendingTransferStoreTest.FakePrefs()
        assertTrue(SharedPrefsPendingTransferStore(prefs).put(optIn()))
        assertTrue(prefs.map.containsKey("$sender|ASA_OPTIN|-|2485202024"))
        assertEquals(PendingLookup.Found(optIn()), SharedPrefsPendingTransferStore(prefs).get(sender, TransferKind.ASA_OPTIN, "-", "2485202024"))
        assertFalse((prefs.map["$sender|ASA_OPTIN|-|2485202024"] as String).contains("feeVersion"))
    }

    @Test
    fun `putNew refuses an existing opt-in record of the same asset and takes another asset`() {
        val s = SharedPrefsPendingTransferStore(PendingTransferStoreTest.FakePrefs())
        assertTrue(s.putNew(optIn()))
        assertFalse(s.putNew(optIn()))
        assertTrue(s.putNew(optIn(asset = 1L)))
        assertTrue(InMemoryPendingTransferStore().let { it.putNew(optIn()) && !it.putNew(optIn()) })
    }

    @Test
    fun `a blocked opt-in key blocks only itself`() {
        val s = SharedPrefsPendingTransferStore(PendingTransferStoreTest.FakePrefs())
        s.blockPermanently(sender, TransferKind.ASA_OPTIN, "-", "2485202024")
        assertFalse(s.putNew(optIn()))
        assertEquals(PendingLookup.Unreadable, s.get(sender, TransferKind.ASA_OPTIN, "-", "2485202024"))
        assertTrue(s.putNew(optIn(asset = 1L)))
    }

    @Test
    fun `stake and claim fee lookups never see an opt-in record, and it never blocks a stake`() {
        val s = SharedPrefsPendingTransferStore(PendingTransferStoreTest.FakePrefs())
        s.put(optIn())
        assertEquals(PendingLookup.None, s.find(sender, TransferKind.STAKE))
        assertEquals(PendingLookup.None, s.find(sender, TransferKind.CLAIM_FEE))
        assertTrue(s.putNew(stake()))
        assertTrue(s.find(sender, TransferKind.STAKE) is PendingLookup.Found)
        assertEquals(PendingLookup.None, s.get(other, TransferKind.ASA_OPTIN, "-", "2485202024"))
    }
}
