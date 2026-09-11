package com.frynetworks.fryapp.data.dashboard.repo

import com.frynetworks.fryapp.data.Device
import com.frynetworks.fryapp.data.Transport
import com.frynetworks.fryapp.data.dashboard.model.MinerSummary
import com.frynetworks.fryapp.data.dashboard.model.RewardSummary
import com.frynetworks.fryapp.domain.MinerFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** [MinerMerge.mergeMiners] is the pure join behind the Miners tab: sources, status precedence, names, order. */
class MinerMergeTest {

    private val now = 1_757_548_800_000L
    private val fem = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val rdn = "RDN-BCDEFGHIJKLMNOPQRSTUVWXYZ0123456"
    private val iot = "IOT-741755294F897EAF140D2E9FB79222A6"

    private fun remote(key: String, status: String? = "active", nickname: String? = null, node: Boolean = false) =
        MinerSummary(minerKey = key, nickname = nickname, status = status, node = node)

    private fun local(key: String, name: String = "Porch", lastSeen: Long = now - 60_000L) =
        Device(minerKey = key, name = name, chip = "ESP32", fwVersion = "0.2.0", wallet = "ADDR", transport = Transport.BLE, lastSeen = lastSeen, status = 1)

    @Test
    fun `a device only on the phone is LOCAL with NOT_ON_DASHBOARD and its local online state`() {
        val items = MinerMerge.mergeMiners(emptyList(), listOf(local(iot)), emptyMap(), now)
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals(MinerSource.LOCAL, item.source)
        assertEquals(MinerStatus.NOT_ON_DASHBOARD, item.status)
        assertEquals(true, item.localOnline)
        assertNull(item.remote)
        assertEquals(iot, item.local?.minerKey)
        assertEquals(MinerFamily.IOT, item.family)
    }

    @Test
    fun `a stale local device is reported offline`() {
        val items = MinerMerge.mergeMiners(emptyList(), listOf(local(iot, lastSeen = now - 16L * 60L * 1000L)), emptyMap(), now)
        assertEquals(false, items[0].localOnline)
    }

    @Test
    fun `a miner only on the dashboard is REMOTE with a null local online state`() {
        val items = MinerMerge.mergeMiners(listOf(remote(fem, "active", "Backyard FEM")), emptyList(), emptyMap(), now)
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals(MinerSource.REMOTE, item.source)
        assertEquals(MinerStatus.ACTIVE, item.status)
        assertNull(item.localOnline)
        assertNull(item.local)
        assertEquals(fem, item.remote?.minerKey)
    }

    @Test
    fun `a key on both sides is BOTH and the dashboard status wins over the local placeholder`() {
        val items = MinerMerge.mergeMiners(listOf(remote(iot, "pending")), listOf(local(iot)), emptyMap(), now)
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals(MinerSource.BOTH, item.source)
        assertEquals(MinerStatus.PENDING, item.status)
        assertEquals(true, item.localOnline)
        assertEquals(iot, item.remote?.minerKey)
        assertEquals(iot, item.local?.minerKey)
    }

    @Test
    fun `display name prefers nickname, then local name, then the short key`() {
        val both = MinerMerge.mergeMiners(listOf(remote(iot, nickname = "Kitchen")), listOf(local(iot, name = "Porch")), emptyMap(), now)
        assertEquals("Kitchen", both[0].displayName)

        val blankNickname = MinerMerge.mergeMiners(listOf(remote(iot, nickname = "   ")), listOf(local(iot, name = "Porch")), emptyMap(), now)
        assertEquals("Porch", blankNickname[0].displayName)

        val remoteOnly = MinerMerge.mergeMiners(listOf(remote(fem)), emptyList(), emptyMap(), now)
        assertEquals("FEM-ABCDEF", remoteOnly[0].displayName)

        val localBlankName = MinerMerge.mergeMiners(emptyList(), listOf(local(iot, name = "")), emptyMap(), now)
        assertEquals("IOT-741755", localBlankName[0].displayName)
    }

    @Test
    fun `shortKey is PREFIX dash first six characters and passes through keys without a dash`() {
        assertEquals("FEM-ABCDEF", MinerListItem.shortKey(fem))
        assertEquals("IOT-741755", MinerListItem.shortKey(iot))
        assertEquals("NODASH", MinerListItem.shortKey("NODASH"))
        assertEquals("FEM-", MinerListItem.shortKey("FEM-"))
        assertEquals("X-AB", MinerListItem.shortKey("X-AB"))
    }

    @Test
    fun `rows sort by status rank, then family order, then display name case-insensitively`() {
        val items = MinerMerge.mergeMiners(
            remote = listOf(
                remote(fem, "active", "zeta"),
                remote("FEM-BBBBBBBBBB", "active", "alpha"),
                remote(rdn, "pending"),
                remote("BM-CCCCCCCC", "migrated"),
                remote("ISM-DDDDDDDD", "unregistered"),
                remote("IHAQM-EEEEEEEE", "active", "Beta"),
            ),
            local = listOf(local(iot)),
            summaries = emptyMap(),
            nowMillis = now,
        )
        assertEquals(
            listOf("ISM-DDDDDDDD", rdn, iot, "FEM-BBBBBBBBBB", fem, "IHAQM-EEEEEEEE", "BM-CCCCCCCC"),
            items.map { it.minerKey },
        )
        assertEquals(listOf(0, 1, 2, 3, 3, 3, 4), items.map { it.status.rank })
    }

    @Test
    fun `reward summaries are attached by key on every source`() {
        val summary = RewardSummary(claimable = BigDecimal("4.25"))
        val items = MinerMerge.mergeMiners(listOf(remote(fem)), listOf(local(iot)), mapOf(fem to summary, iot to summary), now)
        assertSame(summary, items.first { it.minerKey == fem }.rewards)
        assertSame(summary, items.first { it.minerKey == iot }.rewards)
        assertNull(MinerMerge.mergeMiners(listOf(remote(fem)), emptyList(), emptyMap(), now)[0].rewards)
    }

    @Test
    fun `an unknown dashboard status maps to UNKNOWN and node comes from the family or the dashboard flag`() {
        val items = MinerMerge.mergeMiners(listOf(remote(fem, "something-new", node = true), remote(rdn, "active")), emptyList(), emptyMap(), now)
        val femItem = items.first { it.minerKey == fem }
        assertEquals(MinerStatus.UNKNOWN, femItem.status)
        assertTrue(femItem.isNode)
        assertTrue(items.first { it.minerKey == rdn }.isNode)
        assertFalse(MinerMerge.mergeMiners(listOf(remote(fem)), emptyList(), emptyMap(), now)[0].isNode)
    }

    @Test
    fun `rows without a miner key and duplicate keys are dropped`() {
        val items = MinerMerge.mergeMiners(
            listOf(remote(fem), MinerSummary(minerKey = null), MinerSummary(minerKey = "  "), remote(fem, "pending")),
            listOf(local(fem), local(fem)),
            emptyMap(),
            now,
        )
        assertEquals(1, items.size)
        assertEquals(MinerStatus.ACTIVE, items[0].status)
        assertEquals(MinerSource.BOTH, items[0].source)
    }
}
