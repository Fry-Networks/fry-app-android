package com.frynetworks.fryapp.ui.home

import java.net.URLEncoder

/**
 * Builds the Fry Dashboard claim deep-link. Claims are processed on the Fry Dashboard, never
 * in-app — this app has no privileged API call and never signs or submits anything itself; the
 * claim CTA only ever opens the system browser to a public URL.
 *
 * Uses [java.net.URLEncoder] rather than `android.net.Uri` so this stays a plain, Android-free
 * unit — `android.net.Uri.encode()` returns null under Robolectric-less unit tests
 * (`unitTests.isReturnDefaultValues = true` stubs every Android call to its default, not a
 * working implementation).
 */
object ClaimLink {
    private const val BASE_URL = "https://dashboard.frynetworks.com/claim"

    /** Returns null when there is no wallet configured, so the caller can route to Settings
     *  instead of opening a broken URL. */
    fun build(wallet: String): String? {
        if (wallet.isBlank()) return null
        val encoded = URLEncoder.encode(wallet, "UTF-8")
        return "$BASE_URL?wallet=$encoded"
    }
}
