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

    companion object {
        private const val PREFS_NAME = "fry_settings"
        private const val KEY_DEFAULT_WALLET = "default_wallet"
    }
}
