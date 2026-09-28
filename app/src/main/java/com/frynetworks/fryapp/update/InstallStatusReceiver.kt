package com.frynetworks.fryapp.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/**
 * Receives PackageInstaller session results (explicit broadcast, not exported). A pending user
 * action starts the system confirmation screen; every other result is recorded for the UI.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, InstallStatusCopy.STATUS_FAILURE)
        if (status == InstallStatusCopy.STATUS_PENDING_USER_ACTION) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let { confirm ->
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        UpdatePrefs(context).recordInstallStatus(status, InstallStatusCopy.forStatus(status))
    }

    companion object {
        const val ACTION = "com.frynetworks.fryapp.update.INSTALL_STATUS"
    }
}
