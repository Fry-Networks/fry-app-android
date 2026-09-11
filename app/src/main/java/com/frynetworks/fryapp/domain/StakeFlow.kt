package com.frynetworks.fryapp.domain

import com.frynetworks.fryapp.data.dashboard.model.Product
import java.math.BigDecimal

private const val CODE_USER_REJECTED = "USER_REJECTED"
private const val CODE_NO_STAKE_AMOUNT = "NO_STAKE_AMOUNT"
private const val CODE_INVALID_PRICE = "INVALID_PRICE"
private const val CODE_INSUFFICIENT_ALGO = "INSUFFICIENT_ALGO"
private const val CODE_INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE"
private const val CODE_CANCELLED = "CANCELLED"

/** Which stake action the sheet is driving (blueprint 5.8: "registration / node / verification(one|two)"). */
sealed interface StakeContext {
    data object Registration : StakeContext
    data object Node : StakeContext
    data class Verification(val tier: StakeTier) : StakeContext
}

/**
 * Stake-sheet state machine (blueprint 5.8), mirroring the dashboard's Stake.tsx modal:
 * compute the USD/token amount -> confirm -> check balances/opt-in -> precheck -> sign+submit
 * the ASA transfer -> verify -> record the stake with the dashboard -> Done.
 *
 * The blueprint names the state sequence (`Loading(product, price) -> Ready(amount, asset, usd)
 * -> CheckingBalances -> OptInRequired -> Precheck -> AwaitingSignature -> Submitting(txId) ->
 * Verifying -> Recording -> Done | Failed`) but, unlike [ClaimFlow], does not enumerate an event
 * set — the events below are this file's own design, one per state transition, each exercised by
 * [com.frynetworks.fryapp.domain.StakeFlowTest]. Two fields were added to the blueprint's literal
 * state signatures because [reduce] needs them to stay pure (no external context lookups):
 * [StakeState.Loading] carries [StakeContext]/`byod`/`asset` (not just `product`/`price`) so it
 * can call [StakeMath] directly, and [StakeState.OptInRequired] carries `assetId`.
 */
sealed interface StakeState {
    /** Product/price loaded; computing the USD/token amount via [StakeMath] is the next step. */
    data class Loading(
        val product: Product,
        val price: BigDecimal,
        val context: StakeContext,
        val byod: Boolean,
        val asset: FryAsset,
    ) : StakeState

    /** The whole-token [amount] (per [StakeMath.tokensFor]) the sheet will stake, at [usd]. */
    /**
     * [amount] is the token amount in display units the sheet will stake: `floor(usd / price)`
     * whole tokens for registration/node, or the product's `stake_one`/`stake_two` token amount for
     * verification (halved to 2 dp for BYOD, so it may be fractional — Stake.tsx:218-229).
     */
    data class Ready(val amount: BigDecimal, val asset: FryAsset, val usd: BigDecimal) : StakeState

    /** Checking asset balance, ALGO buffer, and opt-in status. */
    data object CheckingBalances : StakeState

    /** Not opted in to [assetId]; the sheet offers an opt-in sub-flow. */
    data class OptInRequired(val assetId: Long) : StakeState

    /** `POST /api/stake/precheck` in flight. */
    data object Precheck : StakeState

    /** Awaiting the user's signature on the ASA-transfer stake transaction. */
    data object AwaitingSignature : StakeState

    /** Broadcast; [txId] is known, awaiting on-chain confirmation. */
    data class Submitting(val txId: String) : StakeState

    /** Verifying the confirmed transaction with the dashboard (`/api/algorand/verify-txn`). */
    data object Verifying : StakeState

    /** Recording the stake (`POST /api/stake/{registration|node-staking|verification}`). */
    data object Recording : StakeState

    /** Recorded. [waived] mirrors the dashboard's `waived:true` (still a success). */
    data class Done(val txId: String, val waived: Boolean = false) : StakeState

    data class Failed(
        val code: String,
        val message: String,
        val recoverable: Boolean,
        val retryAfterSeconds: Int? = null,
    ) : StakeState
}

sealed interface StakeEvent {
    /** Compute [StakeState.Ready] from [StakeState.Loading] via [StakeMath]. */
    data object Start : StakeEvent

    /** User tapped `stake_confirm` on the [StakeState.Ready] step. */
    data object AmountConfirmed : StakeEvent

    data class BalancesChecked(
        val hasAssetBalance: Boolean,
        val hasAlgoBuffer: Boolean,
        val optedIn: Boolean,
        val assetId: Long,
    ) : StakeEvent

    data class OptInCompleted(val optedIn: Boolean, val assetId: Long) : StakeEvent
    data object PrecheckPassed : StakeEvent
    data class PrecheckFailed(val code: String, val message: String, val retryAfterSeconds: Int? = null) : StakeEvent
    data object SignRejected : StakeEvent
    data class Submitted(val txId: String) : StakeEvent
    data class SubmitFailed(val code: String, val message: String) : StakeEvent
    data object TxnConfirmed : StakeEvent
    data object Verified : StakeEvent
    data class VerifyFailed(val code: String, val message: String) : StakeEvent
    data class Recorded(val txId: String, val waived: Boolean = false) : StakeEvent
    data class RecordFailed(val code: String, val message: String) : StakeEvent
    data object Cancel : StakeEvent
}

/** Pure reducer: `reduce(currentState, event) -> nextState`. No I/O, no side effects. */
fun reduce(state: StakeState, event: StakeEvent): StakeState {
    if (event is StakeEvent.Cancel) {
        // Nothing to cancel once a terminal state is reached.
        return if (state is StakeState.Done || state is StakeState.Failed) {
            state
        } else {
            StakeState.Failed(CODE_CANCELLED, "Cancelled by user.", recoverable = true)
        }
    }

    return when (state) {
        is StakeState.Loading -> when (event) {
            is StakeEvent.Start -> computeReady(state)
            else -> state
        }

        is StakeState.Ready -> when (event) {
            is StakeEvent.AmountConfirmed -> StakeState.CheckingBalances
            else -> state
        }

        is StakeState.CheckingBalances -> when (event) {
            is StakeEvent.BalancesChecked -> when {
                !event.hasAlgoBuffer -> StakeState.Failed(
                    CODE_INSUFFICIENT_ALGO,
                    "Insufficient ALGO balance for the minimum reserve.",
                    recoverable = true,
                )
                !event.hasAssetBalance -> StakeState.Failed(
                    CODE_INSUFFICIENT_BALANCE,
                    "Insufficient asset balance to cover the stake amount.",
                    recoverable = true,
                )
                !event.optedIn -> StakeState.OptInRequired(event.assetId)
                else -> StakeState.Precheck
            }
            else -> state
        }

        is StakeState.OptInRequired -> when (event) {
            is StakeEvent.OptInCompleted -> if (event.optedIn) {
                StakeState.Precheck
            } else {
                StakeState.OptInRequired(event.assetId)
            }
            is StakeEvent.SignRejected ->
                StakeState.Failed(CODE_USER_REJECTED, "Opt-in was not signed.", recoverable = true)
            else -> state
        }

        is StakeState.Precheck -> when (event) {
            is StakeEvent.PrecheckPassed -> StakeState.AwaitingSignature
            is StakeEvent.PrecheckFailed ->
                StakeState.Failed(event.code, event.message, recoverable = true, retryAfterSeconds = event.retryAfterSeconds)
            else -> state
        }

        is StakeState.AwaitingSignature -> when (event) {
            is StakeEvent.Submitted -> StakeState.Submitting(event.txId)
            is StakeEvent.SignRejected ->
                StakeState.Failed(CODE_USER_REJECTED, "Stake transaction was not signed.", recoverable = true)
            is StakeEvent.SubmitFailed -> StakeState.Failed(event.code, event.message, recoverable = true)
            else -> state
        }

        is StakeState.Submitting -> when (event) {
            is StakeEvent.TxnConfirmed -> StakeState.Verifying
            is StakeEvent.SubmitFailed -> StakeState.Failed(event.code, event.message, recoverable = true)
            else -> state
        }

        is StakeState.Verifying -> when (event) {
            is StakeEvent.Verified -> StakeState.Recording
            is StakeEvent.VerifyFailed -> StakeState.Failed(event.code, event.message, recoverable = true)
            else -> state
        }

        is StakeState.Recording -> when (event) {
            is StakeEvent.Recorded -> StakeState.Done(event.txId, event.waived)
            is StakeEvent.RecordFailed -> StakeState.Failed(event.code, event.message, recoverable = true)
            else -> state
        }

        is StakeState.Done -> state
        is StakeState.Failed -> state
    }
}

private fun computeReady(state: StakeState.Loading): StakeState {
    val noAmount = StakeState.Failed(
        CODE_NO_STAKE_AMOUNT,
        "This product has no stake amount configured for this action.",
        recoverable = false,
    )
    return when (val context = state.context) {
        // Stake.tsx:218-229 — stake_one/stake_two ARE the token amounts (halved for BYOD); the
        // price is only used to show the USD equivalent, never to divide.
        is StakeContext.Verification -> {
            val tokens = StakeMath.verificationAmount(state.product, context.tier, state.byod) ?: return noAmount
            val amount = StakeMath.normalizeTokens(tokens)
            StakeState.Ready(amount, state.asset, StakeMath.usdFor(amount, state.price))
        }
        // Stake.tsx:232-259 — registration/node are USD amounts converted with floor(usd / price).
        StakeContext.Registration, StakeContext.Node -> {
            val usd = (if (context == StakeContext.Registration) StakeMath.registrationUsd(state.product, state.byod)
            else StakeMath.nodeUsd(state.product, state.byod)) ?: return noAmount
            val tokens = StakeMath.tokensFor(usd, state.price)
                ?: return StakeState.Failed(CODE_INVALID_PRICE, "No valid asset price is available.", recoverable = true)
            StakeState.Ready(BigDecimal.valueOf(tokens), state.asset, usd)
        }
    }
}
