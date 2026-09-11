package com.frynetworks.fryapp.domain

import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.ProductReward
import com.frynetworks.fryapp.data.dashboard.model.ProductStake
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StakeFlowTest {

    private val product = Product(
        key = "FEM",
        reward = ProductReward(stake = ProductStake(register = BigDecimal("40.00"), node = BigDecimal("100.00"))),
    )
    private val price = BigDecimal("0.05")
    private val assetId = FryAsset.TFRY.id

    private fun loadingRegistration(byod: Boolean = false) = StakeState.Loading(
        product = product,
        price = price,
        context = StakeContext.Registration,
        byod = byod,
        asset = FryAsset.TFRY,
    )

    // --- Loading -> Ready ---

    @Test
    fun `Loading plus Start computes Ready from StakeMath registrationUsd and tokensFor`() {
        val next = reduce(loadingRegistration(), StakeEvent.Start)
        // 40.00 usd / 0.05 price = 800 tokens
        assertEquals(StakeState.Ready(amount = 800L, asset = FryAsset.TFRY, usd = BigDecimal("40.00")), next)
    }

    @Test
    fun `Loading plus Start halves the usd amount for BYOD`() {
        val next = reduce(loadingRegistration(byod = true), StakeEvent.Start)
        assertEquals(StakeState.Ready(amount = 400L, asset = FryAsset.TFRY, usd = BigDecimal("20.00")), next)
    }

    @Test
    fun `Loading plus Start computes node stake amounts for the Node context`() {
        val loading = StakeState.Loading(product, price, StakeContext.Node, byod = false, asset = FryAsset.FNODE)
        val next = reduce(loading, StakeEvent.Start)
        assertEquals(StakeState.Ready(amount = 2000L, asset = FryAsset.FNODE, usd = BigDecimal("100.00")), next)
    }

    @Test
    fun `Loading plus Start fails when the product has no configured amount for the context`() {
        val emptyProduct = Product(key = "FEM", reward = ProductReward(stake = ProductStake()))
        val loading = StakeState.Loading(emptyProduct, price, StakeContext.Registration, byod = false, asset = FryAsset.TFRY)
        val next = reduce(loading, StakeEvent.Start)
        assertTrue(next is StakeState.Failed)
        next as StakeState.Failed
        assertEquals("NO_STAKE_AMOUNT", next.code)
        assertTrue(!next.recoverable)
    }

    @Test
    fun `Loading plus Start fails on a non-positive price`() {
        val loading = StakeState.Loading(product, BigDecimal.ZERO, StakeContext.Registration, byod = false, asset = FryAsset.TFRY)
        val next = reduce(loading, StakeEvent.Start)
        assertTrue(next is StakeState.Failed)
        next as StakeState.Failed
        assertEquals("INVALID_PRICE", next.code)
        assertTrue(next.recoverable)
    }

    // --- Ready -> CheckingBalances ---

    @Test
    fun `Ready plus AmountConfirmed moves to CheckingBalances`() {
        val ready = StakeState.Ready(800L, FryAsset.TFRY, BigDecimal("40.00"))
        assertEquals(StakeState.CheckingBalances, reduce(ready, StakeEvent.AmountConfirmed))
    }

    // --- CheckingBalances ---

    @Test
    fun `CheckingBalances with everything satisfied and already opted in moves to Precheck`() {
        val next = reduce(
            StakeState.CheckingBalances,
            StakeEvent.BalancesChecked(hasAssetBalance = true, hasAlgoBuffer = true, optedIn = true, assetId = assetId),
        )
        assertEquals(StakeState.Precheck, next)
    }

    @Test
    fun `CheckingBalances not opted in moves to OptInRequired`() {
        val next = reduce(
            StakeState.CheckingBalances,
            StakeEvent.BalancesChecked(hasAssetBalance = true, hasAlgoBuffer = true, optedIn = false, assetId = assetId),
        )
        assertEquals(StakeState.OptInRequired(assetId), next)
    }

    @Test
    fun `CheckingBalances with insufficient ALGO buffer fails recoverable`() {
        val next = reduce(
            StakeState.CheckingBalances,
            StakeEvent.BalancesChecked(hasAssetBalance = true, hasAlgoBuffer = false, optedIn = true, assetId = assetId),
        )
        assertTrue(next is StakeState.Failed)
        next as StakeState.Failed
        assertEquals("INSUFFICIENT_ALGO", next.code)
        assertTrue(next.recoverable)
    }

    @Test
    fun `CheckingBalances with insufficient asset balance fails recoverable`() {
        val next = reduce(
            StakeState.CheckingBalances,
            StakeEvent.BalancesChecked(hasAssetBalance = false, hasAlgoBuffer = true, optedIn = true, assetId = assetId),
        )
        assertTrue(next is StakeState.Failed)
        next as StakeState.Failed
        assertEquals("INSUFFICIENT_BALANCE", next.code)
        assertTrue(next.recoverable)
    }

    @Test
    fun `CheckingBalances checks the ALGO buffer before the asset balance`() {
        // Both missing: ALGO buffer is checked first (matches MINIMUM_ALGO_BUFFER parity ordering).
        val next = reduce(
            StakeState.CheckingBalances,
            StakeEvent.BalancesChecked(hasAssetBalance = false, hasAlgoBuffer = false, optedIn = true, assetId = assetId),
        )
        next as StakeState.Failed
        assertEquals("INSUFFICIENT_ALGO", next.code)
    }

    // --- OptInRequired ---

    @Test
    fun `OptInRequired plus OptInCompleted true moves to Precheck`() {
        val next = reduce(StakeState.OptInRequired(assetId), StakeEvent.OptInCompleted(optedIn = true, assetId = assetId))
        assertEquals(StakeState.Precheck, next)
    }

    @Test
    fun `OptInRequired plus OptInCompleted false stays OptInRequired for retry`() {
        val next = reduce(StakeState.OptInRequired(assetId), StakeEvent.OptInCompleted(optedIn = false, assetId = assetId))
        assertEquals(StakeState.OptInRequired(assetId), next)
    }

    @Test
    fun `OptInRequired plus SignRejected fails recoverable`() {
        val next = reduce(StakeState.OptInRequired(assetId), StakeEvent.SignRejected)
        next as StakeState.Failed
        assertEquals("USER_REJECTED", next.code)
        assertTrue(next.recoverable)
    }

    // --- Precheck ---

    @Test
    fun `Precheck plus PrecheckPassed moves to AwaitingSignature`() {
        assertEquals(StakeState.AwaitingSignature, reduce(StakeState.Precheck, StakeEvent.PrecheckPassed))
    }

    @Test
    fun `Precheck plus PrecheckFailed 429 carries Retry-After and is recoverable`() {
        val next = reduce(StakeState.Precheck, StakeEvent.PrecheckFailed("RATE_LIMIT_EXCEEDED", "Too many requests", retryAfterSeconds = 30))
        next as StakeState.Failed
        assertEquals("RATE_LIMIT_EXCEEDED", next.code)
        assertTrue(next.recoverable)
        assertEquals(30, next.retryAfterSeconds)
    }

    // --- AwaitingSignature -> Submitting ---

    @Test
    fun `AwaitingSignature plus Submitted moves to Submitting with the txId`() {
        assertEquals(StakeState.Submitting("TXID1"), reduce(StakeState.AwaitingSignature, StakeEvent.Submitted("TXID1")))
    }

    @Test
    fun `AwaitingSignature plus SignRejected fails recoverable`() {
        val next = reduce(StakeState.AwaitingSignature, StakeEvent.SignRejected)
        next as StakeState.Failed
        assertEquals("USER_REJECTED", next.code)
        assertTrue(next.recoverable)
    }

    @Test
    fun `AwaitingSignature plus SubmitFailed fails recoverable`() {
        val next = reduce(StakeState.AwaitingSignature, StakeEvent.SubmitFailed("NETWORK_ERROR", "broadcast failed"))
        next as StakeState.Failed
        assertEquals("NETWORK_ERROR", next.code)
        assertTrue(next.recoverable)
    }

    // --- Submitting -> Verifying ---

    @Test
    fun `Submitting plus TxnConfirmed moves to Verifying`() {
        assertEquals(StakeState.Verifying, reduce(StakeState.Submitting("TXID1"), StakeEvent.TxnConfirmed))
    }

    @Test
    fun `Submitting plus SubmitFailed fails recoverable (rejected after broadcast)`() {
        val next = reduce(StakeState.Submitting("TXID1"), StakeEvent.SubmitFailed("NETWORK_ERROR", "rejected"))
        next as StakeState.Failed
        assertEquals("NETWORK_ERROR", next.code)
        assertTrue(next.recoverable)
    }

    // --- Verifying -> Recording ---

    @Test
    fun `Verifying plus Verified moves to Recording`() {
        assertEquals(StakeState.Recording, reduce(StakeState.Verifying, StakeEvent.Verified))
    }

    @Test
    fun `Verifying plus VerifyFailed fails recoverable`() {
        val next = reduce(StakeState.Verifying, StakeEvent.VerifyFailed("VERIFY_FAILED", "could not verify"))
        next as StakeState.Failed
        assertEquals("VERIFY_FAILED", next.code)
        assertTrue(next.recoverable)
    }

    // --- Recording -> Done ---

    @Test
    fun `Recording plus Recorded moves to Done with the txId`() {
        val next = reduce(StakeState.Recording, StakeEvent.Recorded("TXID1"))
        assertEquals(StakeState.Done("TXID1", waived = false), next)
    }

    @Test
    fun `Recording plus Recorded with waived true is still a successful Done`() {
        val next = reduce(StakeState.Recording, StakeEvent.Recorded("TXID1", waived = true))
        assertEquals(StakeState.Done("TXID1", waived = true), next)
    }

    @Test
    fun `Recording plus RecordFailed fails recoverable`() {
        val next = reduce(StakeState.Recording, StakeEvent.RecordFailed("RECORD_FAILED", "could not record stake"))
        next as StakeState.Failed
        assertEquals("RECORD_FAILED", next.code)
        assertTrue(next.recoverable)
    }

    // --- Cancel ---

    @Test
    fun `Cancel fails every non-terminal state as CANCELLED recoverable`() {
        val states = listOf(
            StakeState.Ready(800L, FryAsset.TFRY, BigDecimal("40.00")),
            StakeState.CheckingBalances,
            StakeState.OptInRequired(assetId),
            StakeState.Precheck,
            StakeState.AwaitingSignature,
            StakeState.Submitting("TXID1"),
            StakeState.Verifying,
            StakeState.Recording,
        )
        states.forEach { state ->
            val next = reduce(state, StakeEvent.Cancel)
            assertTrue("from $state", next is StakeState.Failed)
            next as StakeState.Failed
            assertEquals("from $state", "CANCELLED", next.code)
            assertTrue("from $state", next.recoverable)
        }
    }

    @Test
    fun `Cancel is a no-op on terminal Done and Failed states`() {
        val done = StakeState.Done("TXID1", waived = false)
        assertEquals(done, reduce(done, StakeEvent.Cancel))
        val failed = StakeState.Failed("X", "y", recoverable = true)
        assertEquals(failed, reduce(failed, StakeEvent.Cancel))
    }

    // --- Unrelated events are safe no-ops ---

    @Test
    fun `an event unrelated to the current state leaves the state unchanged`() {
        assertEquals(StakeState.Precheck, reduce(StakeState.Precheck, StakeEvent.Verified))
        assertEquals(StakeState.Verifying, reduce(StakeState.Verifying, StakeEvent.PrecheckPassed))
    }
}
