package com.frynetworks.fryapp.network.dashboard.cookies

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * OkHttp cookie jar for the dashboard host only: write-through to a [CookieStore], expired
 * cookies dropped, `__Host-`/`__Secure-` prefixed names preserved via OkHttp's own
 * `Cookie.toString()` / `Cookie.parse` round-trip. Cookies are only ever sent back to the
 * host they came from ([Cookie.matches]), so nothing leaks to hardwareapi or algod.
 */
class PersistentCookieJar(
    private val store: CookieStore,
    private val host: String = DASHBOARD_HOST,
) : CookieJar {

    private val cookies = LinkedHashMap<String, Cookie>()
    private var loaded = false

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        ensureLoaded()
        val now = System.currentTimeMillis()
        var changed = false
        for (c in cookies) {
            if (!c.domain.equals(host, ignoreCase = true)) continue
            val key = key(c)
            if (c.expiresAt <= now) {
                changed = this.cookies.remove(key) != null || changed
            } else {
                this.cookies[key] = c
                changed = true
            }
        }
        if (changed) persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val expired = cookies.filterValues { it.expiresAt <= now }.keys
        if (expired.isNotEmpty()) {
            expired.forEach { cookies.remove(it) }
            persist()
        }
        return cookies.values.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() {
        ensureLoaded()
        cookies.clear()
        persist()
    }

    /** True when an unexpired NextAuth session token is held. */
    @Synchronized
    fun hasSessionCookie(): Boolean {
        ensureLoaded()
        val now = System.currentTimeMillis()
        return cookies.values.any { it.name in SESSION_COOKIE_NAMES && it.expiresAt > now }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val base = "https://$host/".toHttpUrl()
        val now = System.currentTimeMillis()
        for (line in store.load()) {
            val c = Cookie.parse(base, line) ?: continue
            if (c.expiresAt > now) cookies[key(c)] = c
        }
    }

    private fun persist() {
        store.save(cookies.values.map { it.toString() })
    }

    private fun key(c: Cookie) = "${c.name}|${c.domain}|${c.path}"

    companion object {
        const val DASHBOARD_HOST = "dashboard.frynetworks.com"
        val SESSION_COOKIE_NAMES = setOf("__Secure-next-auth.session-token", "next-auth.session-token")
    }
}
