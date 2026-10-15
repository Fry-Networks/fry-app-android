package com.frynetworks.fryapp.network.dashboard.cookies

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** A first load that fails must not leave an empty jar that the next save writes over the stored session. */
class PersistentCookieJarLoadRetryTest {

    private val dashboard = "https://dashboard.frynetworks.com/".toHttpUrl()
    private val session = "__Secure-next-auth.session-token"

    /** Fails the first [failures] loads, like an unreadable keystore right after boot. */
    private class FlakyStore(var saved: List<String>, var failures: Int) : CookieStore {
        var loads = 0
        override fun load(): List<String> {
            loads++
            if (failures > 0) {
                failures--
                throw IOException("store unavailable")
            }
            return saved
        }
        override fun save(serialized: List<String>) { saved = serialized }
    }

    private fun cookie(name: String, value: String): Cookie =
        Cookie.Builder().name(name).value(value).domain("dashboard.frynetworks.com").path("/")
            .expiresAt(System.currentTimeMillis() + 86_400_000L).secure().httpOnly().build()

    private fun sessionValues(store: CookieStore) =
        PersistentCookieJar(store).loadForRequest(dashboard).filter { it.name == session }.map { it.value }

    private fun storeHoldingSession(failures: Int) =
        FlakyStore(listOf(cookie(session, "original.jwt.token").toString()), failures)

    @Test
    fun `a save after a failed first load keeps the stored session`() {
        val store = storeHoldingSession(failures = 1)
        val jar = PersistentCookieJar(store)
        assertTrue(runCatching { jar.loadForRequest(dashboard) }.isFailure)

        jar.saveFromResponse(dashboard, listOf(cookie("__Host-next-auth.csrf-token", "csrf")))

        assertEquals(listOf("original.jwt.token"), sessionValues(FlakyStore(store.saved, 0)))
    }

    @Test
    fun `the load is retried until it succeeds and then done once`() {
        val store = storeHoldingSession(failures = 2)
        val jar = PersistentCookieJar(store)
        assertTrue(runCatching { jar.loadForRequest(dashboard) }.isFailure)
        assertTrue(runCatching { jar.hasSessionCookie() }.isFailure)

        assertTrue(jar.hasSessionCookie())
        assertEquals(listOf("original.jwt.token"), jar.loadForRequest(dashboard).map { it.value })
        assertEquals(3, store.loads)
    }

    @Test
    fun `a clear after a failed first load still empties the stored session`() {
        val store = storeHoldingSession(failures = 1)
        val jar = PersistentCookieJar(store)
        assertTrue(runCatching { jar.loadForRequest(dashboard) }.isFailure)

        jar.clear()

        assertEquals(emptyList<String>(), store.saved)
    }
}
