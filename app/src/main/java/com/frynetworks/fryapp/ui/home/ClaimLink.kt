package com.frynetworks.fryapp.ui.home

/**
 * Builds the Fry Dashboard claim deep-link. Claims are processed on the Fry Dashboard, never
 * in-app — this app has no privileged API call and never signs or submits anything itself; the
 * claim CTA only ever opens the system browser to a public URL.
 *
 * [CLAIM_URL] carries no query string. The dashboard's `/rewards-claim` page (verified against
 * its own `pages/rewards-claim.tsx`) does not read a `wallet` or `minerKey` parameter, so
 * appending one would only imply a filtering behaviour the page does not have. The wallet is
 * still used here, but only as a "has the user configured one" guard: [build] returns null when
 * there is none, so the caller can route to Settings instead of opening the claim page with
 * nothing to claim against.
 */
object ClaimLink {
    const val CLAIM_URL = "https://dashboard.frynetworks.com/rewards-claim"

    fun build(wallet: String): String? {
        if (wallet.isBlank()) return null
        return CLAIM_URL
    }
}
