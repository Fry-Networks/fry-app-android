package com.frynetworks.fryapp.domain

import java.math.BigDecimal

/** One `{asset_id, amount}` entry from a claim preview/envelope response (display-unit amount). */
data class ClaimTotal(val assetId: Long, val amount: BigDecimal)

private const val MIN_CLAIM_ALGO_MICRO = 2000L
private const val CODE_USER_REJECTED = "USER_REJECTED"
private const val CODE_INSUFFICIENT_ALGO = "INSUFFICIENT_ALGO"
private const val CODE_REWARD_ALREADY_CLAIMING = "REWARD_ALREADY_CLAIMING"
private const val CODE_CLAIM_GROUP_EXPIRED = "CLAIM_GROUP_EXPIRED"
private const val MODE_USER_PAYS = "user_pays"

/**
 * Claim-sheet state machine (blueprint 5.7), mirroring the dashboard's Claim.tsx modal:
 * opt-in check -> preview totals -> ALGO balance check -> fee payment -> claim envelope ->
 * (custodial: straight to confirm) or (user_pays: sign the group, then confirm).
 *
 * Two deliberate additions beyond the blueprint's literal event/state list, both needed for a
 * coherent pure reducer and both exercised by [com.frynetworks.fryapp.domain.ClaimFlowTest]:
 *  - [ClaimEvent.OptInChecked] carries [ClaimEvent.OptInChecked.assetId] (the blueprint's
 *    signature only lists `optedIn`) so [ClaimState.OptInRequired] and the resumed
 *    [ClaimState.CheckingOptIn] can carry the asset id forward without external state.
 *  - [ClaimEvent.PreviewConfirmed] (not listed in the blueprint's event enumeration) is the
 *    user's tap on the sheet's primary `claim_confirm` button while reviewing
 *    [ClaimState.Preview]; the blueprint's state list requires a [ClaimState.CheckingBalance]
 *    step between `Preview` and `PayingFee`, which needs *some* triggering event to reach.
 */
sealed interface ClaimState {
    /** Nothing in flight; the sheet is closed or has not been opened yet. */
    data object Idle : ClaimState

    /** Checking whether the wallet is opted in to the miner's reward asset. */
    data class CheckingOptIn(val assetId: Long) : ClaimState

    /** Not opted in; the sheet shows the `claim_optin` button for [assetId]. */
    data class OptInRequired(val assetId: Long) : ClaimState

    /** The opt-in transaction was submitted; awaiting confirmation/recheck. */
    data class OptingIn(val assetId: Long) : ClaimState

    /** Preview totals loaded from `POST /api/rewards/claim {preview:true}`. */
    data class Preview(val totals: List<ClaimTotal>) : ClaimState

    /** Checking the wallet's ALGO balance covers the network fee. */
    data object CheckingBalance : ClaimState

    /** Awaiting/submitting the 1000 µALGO fee payment to the reward wallet. */
    data object PayingFee : ClaimState

    /** Requesting the claim envelope (`POST /api/rewards/claim`, no `preview`). */
    data object RequestingEnvelope : ClaimState

    /** `user_pays` mode: awaiting the user's signature on the claim group. */
    data class AwaitingSignature(val groupId: String, val expiresAtMillis: Long) : ClaimState

    /** Awaiting `POST /api/rewards/confirm` (both the custodial poll and user_pays confirm). */
    data object Confirming : ClaimState

    /** Claimed. [claimedAt] may be `null` on the legacy confirm response shape. */
    data class Done(val txId: String, val claimedAt: Long?) : ClaimState

    data class Failed(val code: String, val message: String, val recoverable: Boolean) : ClaimState
}

sealed interface ClaimEvent {
    data class Start(val assetId: Long) : ClaimEvent
    data class OptInChecked(val optedIn: Boolean, val assetId: Long) : ClaimEvent
    data object OptInSubmitted : ClaimEvent
    data class PreviewLoaded(val totals: List<ClaimTotal>) : ClaimEvent
    data class PreviewFailed(val code: String, val message: String) : ClaimEvent

    /** User tapped `claim_confirm` on the [ClaimState.Preview] step — see class-level doc. */
    data object PreviewConfirmed : ClaimEvent

    data class BalanceChecked(val microAlgo: Long) : ClaimEvent
    data class FeePaid(val txId: String) : ClaimEvent
    data object FeeRejected : ClaimEvent

    /**
     * The claim-envelope response. Either [mode] is `"user_pays"` (with [groupId],
     * [unsignedUserLeg], [unsignedServerLegs], [expiresAtMillis] populated) or the response
     * carried a ready-made [txIdIfCustodial] (custodial mode — [mode] is `"custodial"`).
     */
    data class EnvelopeReceived(
        val mode: String,
        val groupId: String?,
        val unsignedUserLeg: String?,
        val unsignedServerLegs: List<String>?,
        val txIdIfCustodial: String?,
        val expiresAtMillis: Long?,
    ) : ClaimEvent

    data class EnvelopeFailed(val code: String, val message: String) : ClaimEvent
    data class Signed(val signedUserLegB64: String) : ClaimEvent
    data object SignRejected : ClaimEvent
    data class Confirmed(val txId: String, val claimedAt: Long?) : ClaimEvent
    data class ConfirmFailed(val code: String, val message: String) : ClaimEvent
    data object Cancel : ClaimEvent
}

/** Pure reducer: `reduce(currentState, event) -> nextState`. No I/O, no side effects. */
fun reduce(state: ClaimState, event: ClaimEvent): ClaimState {
    // Cancel is always available and always safe: reset to Idle regardless of where we are.
    if (event is ClaimEvent.Cancel) return ClaimState.Idle

    return when (state) {
        is ClaimState.Idle -> when (event) {
            is ClaimEvent.Start -> ClaimState.CheckingOptIn(event.assetId)
            else -> state
        }

        is ClaimState.CheckingOptIn -> when (event) {
            is ClaimEvent.OptInChecked -> if (event.optedIn) {
                // Already opted in: stay here while the caller now fetches the preview.
                ClaimState.CheckingOptIn(event.assetId)
            } else {
                ClaimState.OptInRequired(event.assetId)
            }
            is ClaimEvent.PreviewLoaded -> ClaimState.Preview(event.totals)
            is ClaimEvent.PreviewFailed -> ClaimState.Failed(event.code, event.message, recoverable = true)
            else -> state
        }

        is ClaimState.OptInRequired -> when (event) {
            is ClaimEvent.OptInSubmitted -> ClaimState.OptingIn(state.assetId)
            else -> state
        }

        is ClaimState.OptingIn -> when (event) {
            is ClaimEvent.OptInChecked -> if (event.optedIn) {
                ClaimState.CheckingOptIn(event.assetId)
            } else {
                ClaimState.OptInRequired(event.assetId)
            }
            is ClaimEvent.SignRejected ->
                ClaimState.Failed(CODE_USER_REJECTED, "Opt-in was not signed.", recoverable = true)
            else -> state
        }

        is ClaimState.Preview -> when (event) {
            is ClaimEvent.PreviewConfirmed -> ClaimState.CheckingBalance
            else -> state
        }

        is ClaimState.CheckingBalance -> when (event) {
            is ClaimEvent.BalanceChecked -> if (event.microAlgo >= MIN_CLAIM_ALGO_MICRO) {
                ClaimState.PayingFee
            } else {
                ClaimState.Failed(
                    CODE_INSUFFICIENT_ALGO,
                    "Insufficient ALGO balance to pay the network fee.",
                    recoverable = true,
                )
            }
            else -> state
        }

        is ClaimState.PayingFee -> when (event) {
            is ClaimEvent.FeePaid -> ClaimState.RequestingEnvelope
            is ClaimEvent.FeeRejected ->
                ClaimState.Failed(CODE_USER_REJECTED, "Fee payment was not signed.", recoverable = true)
            else -> state
        }

        is ClaimState.RequestingEnvelope -> when (event) {
            is ClaimEvent.EnvelopeReceived -> when {
                event.txIdIfCustodial != null -> ClaimState.Confirming
                event.mode == MODE_USER_PAYS && event.groupId != null && event.expiresAtMillis != null ->
                    ClaimState.AwaitingSignature(event.groupId, event.expiresAtMillis)
                else ->
                    ClaimState.Failed("INVALID_ENVELOPE", "The claim envelope was malformed.", recoverable = true)
            }
            is ClaimEvent.EnvelopeFailed -> ClaimState.Failed(
                event.code,
                event.message,
                recoverable = event.code != CODE_REWARD_ALREADY_CLAIMING,
            )
            else -> state
        }

        is ClaimState.AwaitingSignature -> when (event) {
            is ClaimEvent.Signed -> ClaimState.Confirming
            is ClaimEvent.SignRejected ->
                ClaimState.Failed(CODE_USER_REJECTED, "Claim signature was not approved.", recoverable = true)
            else -> state
        }

        is ClaimState.Confirming -> when (event) {
            is ClaimEvent.Confirmed -> ClaimState.Done(event.txId, event.claimedAt)
            is ClaimEvent.ConfirmFailed -> {
                val message = if (event.code == CODE_CLAIM_GROUP_EXPIRED &&
                    !event.message.contains("again", ignoreCase = true)
                ) {
                    "${event.message} Start again."
                } else {
                    event.message
                }
                ClaimState.Failed(event.code, message, recoverable = true)
            }
            else -> state
        }

        is ClaimState.Done -> state
        is ClaimState.Failed -> state
    }
}
