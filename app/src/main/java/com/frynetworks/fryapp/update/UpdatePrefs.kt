package com.frynetworks.fryapp.update

import android.content.Context

/** Last check time and last installer result. Plain prefs: nothing here is secret. */
class UpdatePrefs(context: Context) : UpdateStore {
    private val prefs = context.applicationContext.getSharedPreferences("fry_app_update", Context.MODE_PRIVATE)

    override var lastCheckMillis: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

    override val lastInstallMessage: String? get() = prefs.getString(KEY_LAST_STATUS, null)

    fun recordInstallStatus(status: Int, message: String) {
        prefs.edit().putInt(KEY_LAST_STATUS_CODE, status).putString(KEY_LAST_STATUS, message).apply()
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_check_millis"
        const val KEY_LAST_STATUS = "last_install_message"
        const val KEY_LAST_STATUS_CODE = "last_install_status"
    }
}
