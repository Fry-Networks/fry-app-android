package com.frynetworks.fryapp.network.dashboard.cookies

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Persists serialized cookies (one Set-Cookie string each). */
interface CookieStore {
    fun load(): List<String>
    fun save(serialized: List<String>)
}

/**
 * Dashboard session cookies at rest, encrypted with the Android Keystore master key. The
 * NextAuth session token is a bearer credential for the wallet's dashboard account, so it never
 * lands in plain SharedPreferences.
 */
class EncryptedPrefsCookieStore(context: Context) : CookieStore {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): List<String> {
        val count = prefs.getInt(KEY_COUNT, 0)
        return (0 until count).mapNotNull { prefs.getString("$KEY_PREFIX$it", null) }
    }

    override fun save(serialized: List<String>) {
        prefs.edit().apply {
            clear()
            putInt(KEY_COUNT, serialized.size)
            serialized.forEachIndexed { i, s -> putString("$KEY_PREFIX$i", s) }
        }.apply()
    }

    private companion object {
        const val PREFS_FILE = "fry_dashboard_cookies"
        const val KEY_COUNT = "count"
        const val KEY_PREFIX = "cookie_"
    }
}
