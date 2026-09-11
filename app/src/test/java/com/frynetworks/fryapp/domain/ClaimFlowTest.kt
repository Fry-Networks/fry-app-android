package com.frynetworks.fryapp.domain

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimFlowTest {

    private val assetId = FryAsset.TFRY.id
    private val totals = listOf(ClaimTotal(assetId, BigDecimal("12.34")))

    // --- Start / opt-in ---

    @Test
    fun `Idle plus Start moves to CheckingOptIn carrying the asset id`() {
        val next = reduce(ClaimState.Idle, ClaimEvent.Start(assetId))
        assertEquals(ClaimState.CheckingOptIn(assetId), next)
    }

    @Test
    fun `CheckingOptIn plus OptInChecked true stays in CheckingOptIn awaiting the preview`() {
        val next = reduce(ClaimState.CheckingOptIn(assetId), ClaimEvent.OptInChecked(optedIn = true, assetId = assetId))
        assertEquals(ClaimState.CheckingOptIn(assetId), next)
    }

    @Test
    fun `CheckingOptIn plus OptInChecked false moves to OptInRequired`() {
        val next = reduce(ClaimState.CheckingOptIn(assetId), ClaimEvent.OptInChecked(optedIn = false, assetId = assetId))
        assertEquals(ClaimState.OptInRequired(assetId), next)
    }

    @Test
    fun `OptInRequired plus OptInSubmitted moves to OptingIn`() {
        val next = reduce(ClaimState.OptInRequired(assetId), ClaimEvent.OptInSubmitted)
        assertEquals(ClaimState.OptingIn(assetId), next)
    }

    @Test
    fun `OptingIn plus OptInChecked true (recheck passed) resumes CheckingOptIn`() {
        val next = reduce(ClaimState.OptingIn(assetId), ClaimEvent.OptInChecked(optedIn = true, assetId = assetId))
        assertEquals(ClaimState.CheckingOptIn(assetId), next)
    }

    @Test
    fun `OptingIn plus OptInChecked false (recheck still missing) returns to OptInRequired`() {
        val next = reduce(ClaimState.OptingIn(assetId), ClaimEvent.OptInChecked(optedIn = false, assetId = assetId))
        assertEquals(ClaimState.OptInRequired(assetId), next)
    }

    @Test
    fun `OptingIn plus SignRejected fails recoverable`() {
        val next = reduce(ClaimState.OptingIn(assetId), ClaimEvent.SignRejected)
        assertTrue(next is ClaimState.Failed)
        next as ClaimState.Failed
        assertEquals("USER_REJECTED", next.code)
        assertTrue(next.recoverable)
    }

    // --- Preview ---

    @Test
    fun `CheckingOptIn plus PreviewLoaded moves to Preview with totals`() {
        val next = reduce(ClaimState.CheckingOptIn(assetId), ClaimEvent.PreviewLoaded(totals))
        assertEquals(ClaimState.Preview(totals), next)
    }

    @Test
    fun `CheckingOptIn plus PreviewFailed fails recoverable for every known preview error code`() {
        listOf("NO_REWARDS", "REWARD_ON_HOLD", "WALLET_ASSET_NOT_OPTED_IN", "REWARD_VAULT_DEPLETED").forEach { code ->
            val next = reduce(ClaimState.CheckingOptIn(assetId), ClaimEvent.PreviewFailed(code, "message for $code"))
            assertTrue("code=$code", next is ClaimState.Failed)
            next as ClaimState.Failed
            assertEquals(code, next.code)
            assertTrue("code=$code should be recoverable", next.recoverable)
        }
    }

    @Test
    fun `Preview plus PreviewConfirmed moves to CheckingBalance`() {
        val next = reduce(ClaimState.Preview(totals), ClaimEvent.PreviewConfirmed)
        assertEquals(ClaimState.CheckingBalance, next)
    }

    // --- Balance / fee ---

    @Test
    fun `CheckingBalance with sufficient ALGO moves to PayingFee`() {
        val next = reduce(ClaimState.CheckingBalance, ClaimEvent.BalanceChecked(microAlgo = 2000L))
        assertEquals(ClaimState.PayingFee, next)
    }

    @Test
    fun `CheckingBalance below 2000 micro-ALGO fails as INSUFFICIENT_ALGO`() {
        val next = reduce(ClaimState.CheckingBalance, ClaimEvent.BalanceChecked(microAlgo = 1999L))
        assertTrue(next is ClaimState.Failed)
        next as ClaimState.Failed
        assertEquals("INSUFFICIENT_ALGO", next.code)
        assertTrue(next.recoverable)
    }

    @Test
    fun `PayingFee plus FeePaid moves to RequestingEnvelope`() {
        val next = reduce(ClaimState.PayingFee, ClaimEvent.FeePaid(txId = "FEETXID"))
        assertEquals(ClaimState.RequestingEnvelope, next)
    }

    @Test
    fun `PayingFee plus FeeRejected fails recoverable as USER_REJECTED`() {
        val next = reduce(ClaimState.PayingFee, ClaimEvent.FeeRejected)
        assertTrue(next is ClaimState.Failed)
        next as ClaimState.Failed
        assertEquals("USER_REJECTED", next.code)
        assertTrue(next.recoverable)
    }

    // --- Envelope ---

    @Test
    fun `RequestingEnvelope with a user_pays envelope moves to AwaitingSignature`() {
        val next = reduce(
            ClaimState.RequestingEnvelope,
            ClaimEvent.EnvelopeReceived(
                mode = "user_pays",
                groupId = "GROUP1",
                unsignedUserLeg = "b64user",
                unsignedServerLegs = listOf("b64server1"),
                txIdIfCustodial = null,
                expiresAtMillis = 1_700_000_300_000L,
            ),
        )
        assertEquals(ClaimState.AwaitingSignature("GROUP1", 1_700_000_300_000L), next)
    }

    @Test
    fun `RequestingEnvelope with a custodial txId moves straight to Confirming`() {
        val next = reduce(
            ClaimState.RequestingEnvelope,
            ClaimEvent.EnvelopeReceived(
                mode = "custodial",
                groupId = null,
                unsignedUserLeg = null,
                unsignedServerLegs = null,
                txIdIfCustodial = "CUSTODIALTXID",
                expiresAtMillis = null,
            ),
        )
        assertEquals(ClaimState.Confirming, next)
    }

    @Test
    fun `RequestingEnvelope plus EnvelopeFailed REWARD_ALREADY_CLAIMING fails non-recoverable`() {
        val next = reduce(
            ClaimState.RequestingEnvelope,
            ClaimEvent.EnvelopeFailed("REWARD_ALREADY_CLAIMING", "These rewards are already being claimed."),
        )
        assertTrue(next is ClaimState.Failed)
        next as ClaimState.Failed
        assertEquals("REWARD_ALREADY_CLAIMING", next.code)
        assertTrue("REWARD_ALREADY_CLAIMING must not be recoverable", !next.recoverable)
    }

    @Test
    fun `RequestingEnvelope plus EnvelopeFailed with any other code fails recoverable`() {
        val next = reduce(ClaimState.RequestingEnvelope, ClaimEvent.EnvelopeFailed("NETWORK_ERROR", "network blip"))
        assertTrue(next is ClaimState.Failed)
        next as ClaimState.Failed
        assertEquals("NETWORK_ERROR", next.code)
        assertTrue(next.recoverable)
    }

    // --- Signature / confirm ---

    @Test
    fun `AwaitingSignature plus Signed moves to Confirming`() {
        val next = reduce(ClaimState.AwaitingSignature("GROUP1", 1_700_000_300_000L), ClaimEvent.Signed("signedb64"))
        assertEquals(ClaimState.Confirming, next)
    }

    @Test
    fun `AwaitingSignature plus SignRejected fails recoverable as USER_REJECTED`() {
        val next = reduce(ClaimState.AwaitingSignature("GROUP1", 1_700_000_300_000L), ClaimEvent.SignRejected)
        assertTrue(next is ClaimState.Failed)
        next as ClaimState.Failed
        assertEquals("USER_REJECTED", next.code)
        assertTrue(next.recoverable)
    }

    @Test
    fun `Confirming plus Confirmed moves to Done with txId and claimedAt`() {
        val next = reduce(ClaimState.Confirming, ClaimEvent.Confirmed("FINALTXID", claimedAt = 1_700_000_400_000L))
        assertEquals(ClaimState.Done("FINALTXID", 1_700_000_400_000L), next)
    }

    @Test
    fun `Confirming plus Confirmed tolerates a null claimedAt (custodial legacy confirm)`() {
        val next = reduce(ClaimState.Confirming, ClaimEvent.Confirmed("FINALTXID", claimedAt = null))
        assertEquals(ClaimState.Done("FINALTXID", null), next)
    }

    @Test
    fun `Confirming plus ConfirmFailed CLAIM_GROUP_EXPIRED fails recoverable with Start-again guidance`() {
        val next = reduce(ClaimState.Confirming, ClaimEvent.ConfirmFailed("CLAIM_GROUP_EXPIRED", "The claim group expired."))
        assertTrue(next is ClaimState.Failed)
        next as ClaimState.Failed
        assertEquals("CLAIM_GROUP_EXPIRED", next.code)
        assertTrue(next.recoverable)
        assertTrue(next.message.contains("Start again", ignoreCase = true))
    }

    // --- Cancel is always available and always resets to Idle ---

    @Test
    fun `Cancel resets every non-terminal state to Idle`() {
        val states = listOf(
            ClaimState.CheckingOptIn(assetId),
            ClaimState.OptInRequired(assetId),
            ClaimState.OptingIn(assetId),
            ClaimState.Preview(totals),
            ClaimState.CheckingBalance,
            ClaimState.PayingFee,
            ClaimState.RequestingEnvelope,
            ClaimState.AwaitingSignature("GROUP1", 1_700_000_300_000L),
            ClaimState.Confirming,
        )
        states.forEach { state ->
            assertEquals("from $state", ClaimState.Idle, reduce(state, ClaimEvent.Cancel))
        }
    }

    @Test
    fun `Cancel also resets terminal Done and Failed states to Idle`() {
        assertEquals(ClaimState.Idle, reduce(ClaimState.Done("TX", null), ClaimEvent.Cancel))
        assertEquals(ClaimState.Idle, reduce(ClaimState.Failed("X", "y", true), ClaimEvent.Cancel))
    }

    // --- Unrelated events are safe no-ops ---

    @Test
    fun `an event unrelated to the current state leaves the state unchanged`() {
        assertEquals(ClaimState.Idle, reduce(ClaimState.Idle, ClaimEvent.FeeRejected))
        assertEquals(ClaimState.PayingFee, reduce(ClaimState.PayingFee, ClaimEvent.Signed("noop")))
        val done = ClaimState.Done("TX", null)
        assertEquals(done, reduce(done, ClaimEvent.FeePaid("ignored")))
    }
}
