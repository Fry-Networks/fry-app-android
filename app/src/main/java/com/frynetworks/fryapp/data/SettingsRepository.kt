package com.frynetworks.fryapp.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Stores the user's default wallet address so it can pre-fill new provisioning sessions. */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDefaultWallet(): String = prefs.getString(KEY_DEFAULT_WALLET, "") ?: ""

    fun setDefaultWallet(wallet: String) {
        prefs.edit { putString(KEY_DEFAULT_WALLET, wallet) }
    }

    /** Olostep web-relay opt-in. Off until the owner turns it on. */
    fun getOlostepOptIn(): Boolean = prefs.getBoolean(KEY_OLOSTEP_OPT_IN, false)

    fun setOlostepOptIn(optIn: Boolean) {
        prefs.edit {
            putBoolean(KEY_OLOSTEP_OPT_IN, optIn)
            putBoolean(KEY_OLOSTEP_CHOICE_MADE, true)
        }
    }

    /** True once the owner has used the toggle (either way); until then no opt-in byte is ever sent to a board. */
    fun hasOlostepChoice(): Boolean = prefs.getBoolean(KEY_OLOSTEP_CHOICE_MADE, false)

    companion object {
        private const val PREFS_NAME = "fry_settings"
        private const val KEY_DEFAULT_WALLET = "default_wallet"
        private const val KEY_OLOSTEP_OPT_IN = "olostep_opt_in"
        private const val KEY_OLOSTEP_CHOICE_MADE = "olostep_choice_made"
    }
}
