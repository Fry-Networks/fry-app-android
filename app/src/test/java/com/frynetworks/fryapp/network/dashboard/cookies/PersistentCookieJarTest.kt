package com.frynetworks.fryapp.network.dashboard.cookies

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [CookieStore] fake so the jar can be tested without Android crypto. */
private class FakeStore : CookieStore {
    var saved: List<String> = emptyList()
    var loads = 0
    override fun load(): List<String> { loads++; return saved }
    override fun save(serialized: List<String>) { saved = serialized }
}

class PersistentCookieJarTest {

    private val dashboard = "https://dashboard.frynetworks.com/".toHttpUrl()
    private val authPath = "https://dashboard.frynetworks.com/api/auth/callback/wallet".toHttpUrl()

    private fun cookie(name: String, value: String, maxAgeSeconds: Long = 86_400, secure: Boolean = true): Cookie {
        val b = Cookie.Builder().name(name).value(value).domain("dashboard.frynetworks.com").path("/")
            .expiresAt(System.currentTimeMillis() + maxAgeSeconds * 1000)
        if (secure) b.secure()
        if (name.startsWith("__Host-") || name.startsWith("__Secure-")) b.httpOnly()
        return b.build()
    }

    @Test
    fun `NextAuth cookies round-trip through the store and come back for the same host`() {
        val store = FakeStore()
        val jar = PersistentCookieJar(store)
        jar.saveFromResponse(authPath, listOf(
            cookie("__Host-next-auth.csrf-token", "csrf%7Chash"),
            cookie("__Secure-next-auth.session-token", "jwt.token.value"),
            cookie("__Secure-next-auth.callback-url", "https%3A%2F%2Fdashboard.frynetworks.com"),
        ))
        assertEquals(3, store.saved.size)

        val fresh = PersistentCookieJar(store)
        val sent = fresh.loadForRequest(dashboard).map { it.name }.sorted()
        assertEquals(listOf("__Host-next-auth.csrf-token", "__Secure-next-auth.callback-url", "__Secure-next-auth.session-token"), sent)
        assertEquals("jwt.token.value", fresh.loadForRequest(dashboard).first { it.name == "__Secure-next-auth.session-token" }.value)
    }

    @Test
    fun `a rotated session cookie overwrites the previous value`() {
        val store = FakeStore()
        val jar = PersistentCookieJar(store)
        jar.saveFromResponse(dashboard, listOf(cookie("__Secure-next-auth.session-token", "old")))
        jar.saveFromResponse(dashboard, listOf(cookie("__Secure-next-auth.session-token", "new")))
        val values = jar.loadForRequest(dashboard).filter { it.name == "__Secure-next-auth.session-token" }.map { it.value }
        assertEquals(listOf("new"), values)
        assertEquals(1, store.saved.size)
    }

    @Test
    fun `expired cookies are dropped and never sent`() {
        val store = FakeStore()
        val jar = PersistentCookieJar(store)
        jar.saveFromResponse(dashboard, listOf(cookie("__Secure-next-auth.session-token", "stale", maxAgeSeconds = -10)))
        assertTrue(jar.loadForRequest(dashboard).isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `cookies are never sent to another host`() {
        val store = FakeStore()
        val jar = PersistentCookieJar(store)
        jar.saveFromResponse(dashboard, listOf(cookie("__Secure-next-auth.session-token", "jwt")))
        assertTrue(jar.loadForRequest("https://hardwareapi.frynetworks.com/versions/IOTVPN".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://mainnet-api.algonode.cloud/v2/status".toHttpUrl()).isEmpty())
    }

    @Test
    fun `clear wipes memory and the store`() {
        val store = FakeStore()
        val jar = PersistentCookieJar(store)
        jar.saveFromResponse(dashboard, listOf(cookie("__Secure-next-auth.session-token", "jwt")))
        jar.clear()
        assertTrue(jar.loadForRequest(dashboard).isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `hasSessionCookie reports an unexpired NextAuth session token`() {
        val store = FakeStore()
        val jar = PersistentCookieJar(store)
        assertTrue(!jar.hasSessionCookie())
        jar.saveFromResponse(dashboard, listOf(cookie("__Secure-next-auth.session-token", "jwt")))
        assertTrue(jar.hasSessionCookie())
    }
}
