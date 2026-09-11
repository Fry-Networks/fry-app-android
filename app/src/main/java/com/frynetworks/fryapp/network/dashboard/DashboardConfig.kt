package com.frynetworks.fryapp.network.dashboard

import com.frynetworks.fryapp.BuildConfig

/**
 * Constants the dashboard client pins. The User-Agent, Accept and Accept-Language values feed
 * the dashboard's device fingerprint, so they are fixed per build and never derived from the
 * device (a changed header set would be rejected as "a different device").
 */
object DashboardConfig {
    const val BASE_URL: String = BuildConfig.DASHBOARD_BASE
    const val ALGOD_URL: String = BuildConfig.ALGOD_BASE
    const val SIGNATURE_SECRET: String = BuildConfig.REQUEST_SIGNATURE_SECRET

    /** Version-scoped so a UA change happens exactly once per app update. */
    const val USER_AGENT: String = "FryApp/" + BuildConfig.VERSION_NAME + " (Android; wallet-bridge)"
    const val ACCEPT: String = "application/json, text/plain, */*"
    const val ACCEPT_LANGUAGE: String = "en-US,en;q=0.9"

    /** Routes guarded by `enforceWalletApiSecurity` (client token + request signature + fingerprint). */
    val SIGNED_PATHS: Set<String> = setOf(
        "/api/rewards/claim",
        "/api/rewards/confirm",
        "/api/rewards/boost",
        "/api/rewards/get-asset-totals",
        "/api/rewards/get-reward-summary",
        "/api/rewards/get-reward-summary-batch",
        "/api/rewards/get-rewards-page",
        "/api/stake/precheck",
        "/api/stake/registration",
        "/api/stake/node-staking",
        "/api/stake/verification",
        "/api/stake/r-withdraw",
        "/api/stake/n-withdraw",
        "/api/stake/stake-withdraw",
        "/api/stake/withdrawable",
        "/api/fee/pay-withdraw",
        "/api/fee/verify-pay",
        "/api/events/claim-free-fem",
    )

    /** Header names the dashboard hashes into the device fingerprint (must never vary). */
    val FINGERPRINT_HEADERS: List<String> = listOf("User-Agent", "Accept-Language", "Accept-Encoding", "Accept", "sec-ch-ua", "sec-ch-ua-mobile")
}
