package com.frynetworks.fryapp.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.frynetworks.fryapp.wallet.WalletVendor
import com.google.gson.Gson

/** What we remember about the signed-in dashboard account (never the session token itself). */
data class SessionProfile(
    val address: String,
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val vendor: WalletVendor? = null,
    val fingerprint: String? = null,
)

interface SessionStore {
    fun load(): SessionProfile?
    fun save(profile: SessionProfile?)
}

class EncryptedPrefsSessionStore(context: Context) : SessionStore {
    private val gson = Gson()
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "fry_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): SessionProfile? =
        prefs.getString(KEY_PROFILE, null)?.let { runCatching { gson.fromJson(it, SessionProfile::class.java) }.getOrNull() }

    override fun save(profile: SessionProfile?) {
        prefs.edit().apply {
            if (profile == null) remove(KEY_PROFILE) else putString(KEY_PROFILE, gson.toJson(profile))
        }.apply()
    }

    private companion object {
        const val KEY_PROFILE = "profile"
    }
}
