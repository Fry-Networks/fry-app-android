package com.frynetworks.fryapp.domain

import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.ProductReward
import com.frynetworks.fryapp.data.dashboard.model.ProductStake
import com.frynetworks.fryapp.network.dashboard.JsJson
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StakeMathTest {

    private fun productWith(register: String?, node: String?, stakeOne: String?, stakeTwo: String?) = Product(
        key = "FEM",
        reward = ProductReward(
            stake = ProductStake(
                register = register?.let { BigDecimal(it) },
                node = node?.let { BigDecimal(it) },
                stakeOne = stakeOne?.let { BigDecimal(it) },
                stakeTwo = stakeTwo?.let { BigDecimal(it) },
            ),
        ),
    )

    // --- registrationUsd ---

    @Test
    fun `registrationUsd returns the product's register amount unchanged when not BYOD`() {
        val product = productWith(register = "40.00", node = null, stakeOne = null, stakeTwo = null)
        assertEquals(0, BigDecimal("40.00").compareTo(StakeMath.registrationUsd(product, byod = false)))
    }

    @Test
    fun `registrationUsd halves the amount and rounds to 2 decimals when BYOD`() {
        val product = productWith(register = "40.00", node = null, stakeOne = null, stakeTwo = null)
        assertEquals(0, BigDecimal("20.00").compareTo(StakeMath.registrationUsd(product, byod = true)))
    }

    @Test
    fun `registrationUsd BYOD halving rounds half up to 2 decimals`() {
        // 10.01 / 2 = 5.005 -> rounds to 5.01 (round((usd/2)*100)/100, ties away from zero)
        val product = productWith(register = "10.01", node = null, stakeOne = null, stakeTwo = null)
        assertEquals(0, BigDecimal("5.01").compareTo(StakeMath.registrationUsd(product, byod = true)))
    }

    @Test
    fun `registrationUsd is null when the product has no register amount`() {
        val product = productWith(register = null, node = null, stakeOne = null, stakeTwo = null)
        assertNull(StakeMath.registrationUsd(product, byod = false))
        assertNull(StakeMath.registrationUsd(product, byod = true))
    }

    // --- nodeUsd ---

    @Test
    fun `nodeUsd returns the product's node amount unchanged when not BYOD`() {
        val product = productWith(register = null, node = "100.00", stakeOne = null, stakeTwo = null)
        assertEquals(0, BigDecimal("100.00").compareTo(StakeMath.nodeUsd(product, byod = false)))
    }

    @Test
    fun `nodeUsd halves the amount and rounds to 2 decimals when BYOD`() {
        val product = productWith(register = null, node = "100.00", stakeOne = null, stakeTwo = null)
        assertEquals(0, BigDecimal("50.00").compareTo(StakeMath.nodeUsd(product, byod = true)))
    }

    @Test
    fun `nodeUsd is null when the product has no node amount`() {
        val product = productWith(register = null, node = null, stakeOne = null, stakeTwo = null)
        assertNull(StakeMath.nodeUsd(product, byod = false))
    }

    // --- verificationAmount ---

    @Test
    fun `verificationAmount ONE returns stake_one unchanged when not BYOD`() {
        val product = productWith(register = null, node = null, stakeOne = "5.00", stakeTwo = "20.00")
        assertEquals(0, BigDecimal("5.00").compareTo(StakeMath.verificationAmount(product, StakeTier.ONE, byod = false)))
    }

    @Test
    fun `verificationAmount TWO returns stake_two unchanged when not BYOD`() {
        val product = productWith(register = null, node = null, stakeOne = "5.00", stakeTwo = "20.00")
        assertEquals(0, BigDecimal("20.00").compareTo(StakeMath.verificationAmount(product, StakeTier.TWO, byod = false)))
    }

    @Test
    fun `verificationAmount halves for BYOD per tier`() {
        val product = productWith(register = null, node = null, stakeOne = "5.00", stakeTwo = "20.00")
        assertEquals(0, BigDecimal("2.50").compareTo(StakeMath.verificationAmount(product, StakeTier.ONE, byod = true)))
        assertEquals(0, BigDecimal("10.00").compareTo(StakeMath.verificationAmount(product, StakeTier.TWO, byod = true)))
    }

    @Test
    fun `verificationAmount is null when the tier's stake amount is absent`() {
        val product = productWith(register = null, node = null, stakeOne = null, stakeTwo = null)
        assertNull(StakeMath.verificationAmount(product, StakeTier.ONE, byod = false))
        assertNull(StakeMath.verificationAmount(product, StakeTier.TWO, byod = false))
    }

    // --- tokensFor ---

    @Test
    fun `tokensFor floors usd divided by price`() {
        assertEquals(3L, StakeMath.tokensFor(BigDecimal("10"), BigDecimal("3")))
        assertEquals(3L, StakeMath.tokensFor(BigDecimal("9.99"), BigDecimal("3")))
        assertEquals(4L, StakeMath.tokensFor(BigDecimal("12"), BigDecimal("3")))
    }

    @Test
    fun `tokensFor is exact for whole-number results`() {
        assertEquals(100L, StakeMath.tokensFor(BigDecimal("100.00"), BigDecimal("1.00")))
    }

    @Test
    fun `tokensFor returns null for a zero or negative price`() {
        assertNull(StakeMath.tokensFor(BigDecimal("10"), BigDecimal.ZERO))
        assertNull(StakeMath.tokensFor(BigDecimal("10"), BigDecimal("-5")))
    }

    // --- lockRemaining ---

    @Test
    fun `lockRemaining ONE tier is a 1 day lock`() {
        val stakedAt = Instant.parse("2026-01-01T00:00:00Z")
        val now = Instant.parse("2026-01-01T12:00:00Z")
        assertEquals(Duration.ofHours(12), StakeMath.lockRemaining(stakedAt, StakeTier.ONE, now))
    }

    @Test
    fun `lockRemaining TWO tier is a 180 day lock`() {
        val stakedAt = Instant.parse("2026-01-01T00:00:00Z")
        val now = Instant.parse("2026-01-02T00:00:00Z")
        assertEquals(Duration.ofDays(179), StakeMath.lockRemaining(stakedAt, StakeTier.TWO, now))
    }

    @Test
    fun `lockRemaining clamps to zero once the lock has expired`() {
        val stakedAt = Instant.parse("2026-01-01T00:00:00Z")
        val now = Instant.parse("2027-01-01T00:00:00Z")
        assertEquals(Duration.ZERO, StakeMath.lockRemaining(stakedAt, StakeTier.ONE, now))
        assertEquals(Duration.ZERO, StakeMath.lockRemaining(stakedAt, StakeTier.TWO, now))
    }

    @Test
    fun `lockRemaining is exactly the full lock at the staked instant`() {
        val stakedAt = Instant.parse("2026-01-01T00:00:00Z")
        assertEquals(Duration.ofDays(1), StakeMath.lockRemaining(stakedAt, StakeTier.ONE, stakedAt))
        assertEquals(Duration.ofDays(180), StakeMath.lockRemaining(stakedAt, StakeTier.TWO, stakedAt))
    }

    // --- StakeTier codes ---

    @Test
    fun `StakeTier codes match the dashboard's one two vocabulary`() {
        assertEquals("one", StakeTier.ONE.code)
        assertEquals("two", StakeTier.TWO.code)
        assertEquals(StakeTier.ONE, StakeTier.fromCode("one"))
        assertEquals(StakeTier.TWO, StakeTier.fromCode("two"))
        assertNull(StakeTier.fromCode("three"))
    }

    // --- stakeNote key order (byte-for-byte via JsJson, matching Stake.tsx note construction) ---

    @Test
    fun `stakeNote omits type for a non-verification operation and preserves key order`() {
        val note = StakeMath.stakeNote(
            action = "stake",
            minerKeyShort = "FEM-ABCDEF",
            assetId = 2681521901L,
            from = "SENDERADDR",
            to = "UKVAN7ADDR",
            amount = 40_000_000L,
            operation = "register",
            timestampMillis = 1_700_000_000_000L,
        )
        assertEquals(
            listOf("action", "miner_key", "asset_id", "from", "to", "amount", "operation", "timestamp"),
            note.keys.toList(),
        )
        assertEquals(
            "{\"action\":\"stake\",\"miner_key\":\"FEM-ABCDEF\",\"asset_id\":2681521901,\"from\":\"SENDERADDR\"," +
                "\"to\":\"UKVAN7ADDR\",\"amount\":40000000,\"operation\":\"register\",\"timestamp\":1700000000000}",
            JsJson.stringify(note),
        )
    }

    @Test
    fun `stakeNote includes type only for a verification operation, in F9 key order`() {
        val note = StakeMath.stakeNote(
            action = "stake",
            minerKeyShort = "FEM-ABCDEF",
            assetId = 2681521901L,
            type = "one",
            from = "SENDERADDR",
            to = "UKVAN7ADDR",
            amount = 5_000_000L,
            operation = "verification",
            timestampMillis = 1_700_000_000_000L,
        )
        assertEquals(
            listOf("action", "miner_key", "asset_id", "type", "from", "to", "amount", "operation", "timestamp"),
            note.keys.toList(),
        )
        assertTrue(JsJson.stringify(note).contains("\"type\":\"one\""))
    }
}
