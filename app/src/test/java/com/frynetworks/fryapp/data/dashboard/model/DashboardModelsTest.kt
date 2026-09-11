package com.frynetworks.fryapp.data.dashboard.model

import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every dashboard response fixture must parse into its DTO: unknown fields are tolerated,
 * amount-bearing fields are exact [BigDecimal] (never routed through [Double]), and
 * [RewardStatus] never throws on an unrecognised value.
 */
class DashboardModelsTest {

    private val gson = DashboardGson.instance

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/dashboard/$name.json")!!.readText()

    private inline fun <reified T> parse(fixtureName: String): T =
        gson.fromJson(fixture(fixtureName), T::class.java)

    // --- devices/list ---

    @Test
    fun `miner-list fixture parses into MinerListResponse`() {
        val response = parse<MinerListResponse>("miner-list")
        assertEquals(true, response.success)
        assertEquals(2, response.minerKeys.size)
        val fem = response.minerKeys[0]
        assertEquals("FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345", fem.minerKey)
        assertEquals("Backyard FEM", fem.nickname)
        assertEquals("active", fem.status)
        assertTrue(fem.isRegistered)
        assertFalse(fem.virtual)
        val rdn = response.minerKeys[1]
        assertNull(rdn.nickname)
        assertTrue(rdn.node)
        assertTrue(rdn.byod)
    }

    // --- devices/{miner_key} ---

    @Test
    fun `device-detail fixture parses the typed subset and stashes unmapped fields in raw`() {
        val response = parse<DeviceDetailResponse>("device-detail")
        val device = requireNotNull(response.device)
        assertEquals("FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345", device.minerKey)
        assertEquals("HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU", device.address)
        assertEquals("BYOD-LICENSE-0042", device.byod)
        assertTrue(device.isRegistered)
        assertEquals(0, BigDecimal("20.5").compareTo(device.staked?.amount))
        assertEquals("two", device.staked?.type)
        assertEquals(1, device.staked?.history?.size)
        assertEquals(0, BigDecimal("40.0").compareTo(device.registration?.amount))
        assertNull(device.node)
        assertEquals(40.7128, device.position?.lat)
        assertEquals(true, device.isActive)
        assertNull(device.rewardBlockReason)
        // The typed subset doesn't model this field, but raw must still carry it.
        assertTrue(device.raw!!.has("some_future_field_not_yet_modelled"))
        assertEquals("tolerated", device.raw!!.get("some_future_field_not_yet_modelled").asString)
    }

    // --- devices/batch ---

    @Test
    fun `batch-devices fixture parses a devices map keyed by miner_key`() {
        val response = parse<BatchDevicesResponse>("batch-devices")
        assertEquals(2, response.devices.size)
        val fem = requireNotNull(response.devices["FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"])
        assertEquals("Backyard FEM", fem.nickname)
        val rdn = requireNotNull(response.devices["RDN-BCDEFGHIJKLMNOPQRSTUVWXYZ0123456"])
        assertTrue(rdn.virtual)
        assertEquals(0, BigDecimal("100.0").compareTo(rdn.node?.amount))
    }

    // --- devices/status-summary ---

    @Test
    fun `status-summary fixture parses total and online counts`() {
        val response = parse<StatusSummaryResponse>("status-summary")
        assertEquals(2, response.total)
        assertEquals(1, response.online)
    }

    // --- hardware/status ---

    @Test
    fun `hardware-status fixture parses a statuses map keyed by miner_key`() {
        val response = parse<HardwareStatusResponse>("hardware-status")
        assertEquals(2, response.statuses.size)
        assertTrue(requireNotNull(response.statuses["FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"]).linked!!)
        assertEquals("NOT_LINKED", response.statuses["RDN-BCDEFGHIJKLMNOPQRSTUVWXYZ0123456"]?.reason)
    }

    // --- my-keys ---

    @Test
    fun `my-keys fixture parses devices and byodLicenses`() {
        val response = parse<MyKeysResponse>("my-keys")
        assertEquals(2, response.devices.size)
        assertEquals(listOf("BYOD-LICENSE-0042"), response.byodLicenses)
        val withCreds = response.devices[1]
        assertEquals(1, withCreds.credentials.size)
        assertEquals("purpleair", withCreds.credentials[0].portal)
    }

    // --- products/get-product ---

    @Test
    fun `product fixture parses reward stake and token amounts exactly`() {
        val root = JsonParser.parseString(fixture("product")).asJsonObject
        val products: List<Product> = gson.fromJson(root.get("data"), object : TypeToken<List<Product>>() {}.type)
        assertEquals(1, products.size)
        val product = products[0]
        assertEquals("FEM", product.key)
        assertEquals(0, BigDecimal("40.0").compareTo(product.reward?.stake?.register))
        assertEquals(0, BigDecimal("100.0").compareTo(product.reward?.stake?.node))
        assertEquals(0, BigDecimal("5.0").compareTo(product.reward?.stake?.stakeOne))
        assertEquals(0, BigDecimal("20.0").compareTo(product.reward?.stake?.stakeTwo))
        assertEquals("fNODE", product.reward?.tokens?.node)
    }

    // --- rewards/get-asset-totals ---

    @Test
    fun `asset-totals fixture parses fnode and tfry buckets exactly`() {
        val response = parse<AssetTotalsResponse>("asset-totals")
        assertEquals(0, BigDecimal("12.5").compareTo(response.totals?.fnode?.pending))
        assertEquals(0, BigDecimal("9.99").compareTo(response.totals?.tfry?.claimable))
        assertEquals("2026-09-11T00:05:00.000Z", response.nextUnlockAt)
        assertEquals(1757548800000L, response.serverTime)
    }

    // --- rewards/get-reward-summary(-batch) ---

    @Test
    fun `reward-summary fixture parses the summary block exactly`() {
        val response = parse<RewardSummaryResponse>("reward-summary")
        assertEquals(0, BigDecimal("4.25").compareTo(response.summary?.claimable))
        assertEquals("2026-04-20T00:05:00.000Z", response.summary?.firstRewardAt)
    }

    @Test
    fun `reward-summary-batch fixture parses a summaries map and tolerates a null firstRewardAt`() {
        val response = parse<RewardSummaryBatchResponse>("reward-summary-batch")
        assertEquals(2, response.summaries.size)
        val rdn = requireNotNull(response.summaries["RDN-BCDEFGHIJKLMNOPQRSTUVWXYZ0123456"])
        assertNull(rdn.firstRewardAt)
        assertEquals(0, BigDecimal.ZERO.compareTo(rdn.pending))
    }

    // --- rewards/get-rewards-page ---

    @Test
    fun `rewards-page fixture parses items with exact BigDecimal amounts`() {
        val response = parse<RewardsPageResponse>("rewards-page")
        assertEquals(3, response.items.size)
        assertEquals(1, response.totalPages)
        assertEquals(0, BigDecimal("4.25").compareTo(response.items[0].amount))
        assertEquals(RewardStatus.CLAIMABLE, response.items[0].status)
        assertEquals(RewardStatus.ON_HOLD, response.items[1].status)
        assertTrue(response.items[1].onHold)
    }

    @Test
    fun `RewardStatus deserialization is lenient — an unrecognised status maps to UNKNOWN`() {
        val response = parse<RewardsPageResponse>("rewards-page")
        assertEquals(RewardStatus.UNKNOWN, response.items[2].status)
    }

    // --- activity/recent ---

    @Test
    fun `activity fixture parses events including one with no amount asset`() {
        val response = parse<ActivityResponse>("activity")
        assertEquals(2, response.events.size)
        assertEquals("reward_unlocked", response.events[0].type)
        assertEquals(0, BigDecimal("4.25").compareTo(response.events[0].amount))
        assertEquals("registered", response.events[1].type)
        assertNull(response.events[1].amount)
    }

    // --- price/get ---

    @Test
    fun `price fixture parses a prices map with exact BigDecimal values`() {
        val response = parse<PriceResponse>("price")
        assertEquals(0, BigDecimal("0.05").compareTo(response.prices["2681521901"]))
        assertEquals(0, BigDecimal("0.12").compareTo(response.prices["2485202024"]))
    }

    // --- stake/* ---

    @Test
    fun `withdrawable fixture parses`() {
        val response = parse<WithdrawableResponse>("withdrawable")
        assertTrue(response.withdrawable)
        assertEquals(0, BigDecimal("20.5").compareTo(response.amount))
    }

    @Test
    fun `precheck fixture parses`() {
        val response = parse<PrecheckResponse>("precheck")
        assertEquals(true, response.success)
    }

    @Test
    fun `stake-submit fixture parses with waived false`() {
        val response = parse<StakeSubmitResponse>("stake-submit")
        assertFalse(response.waived)
        assertEquals("STAKESUBMITTXID0000000000000000000000000000000000000000", response.txId)
    }

    // --- rewards/claim + confirm ---

    @Test
    fun `claim-preview fixture parses totals exactly`() {
        val response = parse<ClaimPreviewResponse>("claim-preview")
        assertTrue(response.preview)
        assertEquals(1, response.totals.size)
        assertEquals(0, BigDecimal("4.25").compareTo(response.totals[0].amount))
    }

    @Test
    fun `claim-envelope-user-pays fixture parses the user_pays mode with a null txId`() {
        val response = parse<ClaimEnvelopeResponse>("claim-envelope-user-pays")
        assertEquals("user_pays", response.mode)
        assertEquals("GROUP-1700000000000-abc123", response.groupId)
        assertEquals(1, response.unsignedServerLegs.size)
        assertNull(response.txId)
    }

    @Test
    fun `claim-custodial fixture parses the custodial mode with a txId and null groupId`() {
        val response = parse<ClaimEnvelopeResponse>("claim-custodial")
        assertEquals("custodial", response.mode)
        assertNull(response.groupId)
        assertEquals("CUSTODIALTXID000000000000000000000000000000000000000000", response.txId)
    }

    @Test
    fun `confirm-result fixture parses ok, txId and claimedAt`() {
        val response = parse<ConfirmResultResponse>("confirm-result")
        assertEquals(true, response.ok)
        assertEquals("2026-09-10T14:22:00.000Z", response.claimedAt)
    }

    // --- error envelope ---

    @Test
    fun `error fixture parses into ApiErrorBody`() {
        val error = parse<ApiErrorBody>("error")
        assertEquals(false, error.success)
        assertEquals("REWARD_ON_HOLD", error.code)
        assertEquals(2681521901L, error.assetId)
    }
}
