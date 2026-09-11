package com.frynetworks.fryapp.ui.common

/**
 * Dashboard / bridge error code -> user-facing copy. Known codes get a fixed sentence (the
 * dashboard's own wording where it has one); unknown codes fall back to the server's message
 * when present, else a generic line. Never leaks internal identifiers into the copy.
 */
object ErrorCopy {

    const val GENERIC = "Something went wrong. Please try again."

    private val copy: Map<String, String> = mapOf(
        "NO_REWARDS" to "There are no claimable rewards for this miner yet.",
        "REWARD_ON_HOLD" to "These rewards are on hold. Check the miner's status on the dashboard.",
        "WALLET_ASSET_NOT_OPTED_IN" to "Your wallet is not opted in to the reward asset. Opt in, then try again.",
        "REWARD_VAULT_DEPLETED" to "The reward vault is temporarily empty. Please try again later.",
        "CLAIM_GROUP_EXPIRED" to "The claim expired before it was signed. Start again.",
        "REWARD_ALREADY_CLAIMING" to "A claim for this miner is already in progress. Wait for it to finish.",
        "RATE_LIMIT_EXCEEDED" to "Too many requests. Please wait a moment and try again.",
        "WALLET_MISMATCH" to "The connected wallet is not the wallet you signed in with. Reconnect the same wallet.",
        "UNAUTHORIZED" to "Your dashboard session has ended. Please sign in again.",
        "DEVICE_MISMATCH" to "This request came from a different device. Please sign in again.",
        "ACTIVITY_UNAVAILABLE" to "Activity is temporarily unavailable.",
        "NETWORK_ERROR" to "Could not reach the Fry Dashboard. Check your connection and try again.",
        "NETWORK" to "Could not reach the network. Check your connection and try again.",
        "USER_REJECTED" to "The request was rejected in your wallet app.",
        "TIMEOUT" to "Your wallet did not respond in time. Open the wallet app and try again.",
        "PENDING_REQUEST" to "Your wallet still has a pending request. Open the wallet app to finish it.",
        "NOT_CONNECTED" to "Your wallet is not connected. Reconnect and try again.",
        "SESSION_EXPIRED" to "The wallet session expired. Reconnect your wallet.",
        "WALLET_NOT_INSTALLED" to "No supported wallet app is installed. Install Pera or Defly to continue.",
        "BRIDGE_RESET" to "The wallet connection was reset. Please try again.",
        "NOT_WIRED" to "This feature is not available in this build yet.",
        "INSUFFICIENT_ALGO" to "Your wallet needs at least 0.002 ALGO available to pay the network fee.",
        "INSUFFICIENT_BALANCE" to "Your wallet does not hold enough of the stake asset.",
        "TXN_NOT_CONFIRMED" to "The transaction was not confirmed on-chain in time.",
        "CANCELLED" to "Cancelled.",
        "INVALID_ENVELOPE" to "The dashboard returned an unusable claim. Please try again.",
        "INVALID_PRICE" to "No valid asset price is available right now.",
        "NO_STAKE_AMOUNT" to "This product has no stake amount configured for this action.",
        "NO_PRODUCT" to "This miner's product is not in the catalog, so it cannot be staked here.",
        "CONFIRM_PENDING" to "The claim was submitted but is still awaiting confirmation. Check the transaction on the explorer.",
        "INVALID_ADDRESS" to "That is not a valid Algorand address.",
    )

    /** Copy for [code]; falls back to [serverMessage] (when non-blank) then [GENERIC]. */
    fun forCode(code: String?, serverMessage: String? = null): String {
        val known = code?.let { copy[it] }
        if (known != null) return known
        val server = serverMessage?.trim()
        return if (!server.isNullOrEmpty()) server else GENERIC
    }

    fun isKnown(code: String?): Boolean = code != null && copy.containsKey(code)
}
