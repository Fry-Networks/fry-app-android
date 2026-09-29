package com.frynetworks.fryapp.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/**
 * Hands a verified APK to [PackageInstaller]. On API 31+ it asks not to need the user
 * (USER_ACTION_NOT_REQUIRED; honoured once this app installed the current version itself and
 * holds UPDATE_PACKAGES_WITHOUT_USER_ACTION); on API 34+ it asks to become the update owner. The
 * result arrives at [InstallStatusReceiver] through an explicit broadcast.
 */
class PackageInstallerGateway(private val context: Context) {

    fun install(apk: File, packageName: String): Boolean = install(apk, packageName) { true }

    /**
     * [mayCommit] is asked right before `session.commit()`, after the APK has been written into
     * the session (which takes a moment): a false answer abandons the session and returns false,
     * so a board setup, a signed transaction or an activity that appeared meanwhile is never
     * killed by a silent install.
     */
    fun install(apk: File, packageName: String, mayCommit: () -> Boolean): Boolean = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setRequestUpdateOwnership(true)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            if (!mayCommit()) {
                session.abandon()
                return@runCatching false
            }
            val intent = Intent(context, InstallStatusReceiver::class.java)
                .setAction(InstallStatusReceiver.ACTION)
                .setPackage(context.packageName)
            // Mutable: PackageInstaller fills in the status extras.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
        true
    }.getOrDefault(false)
}
