package com.frynetworks.fryapp.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/** Reads [SignerFacts] for this app and for a downloaded APK through PackageManager. */
class ApkInspector(private val context: Context) {

    fun installed(): SignerFacts? = runCatching {
        facts(packageInfo { pm, flags -> pm.getPackageInfo(context.packageName, flags) })
    }.getOrNull()

    fun candidate(apk: File): SignerFacts? = runCatching {
        facts(packageInfo { pm, flags -> pm.getPackageArchiveInfo(apk.absolutePath, flags) })
    }.getOrNull()

    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun packageInfo(get: (PackageManager, Int) -> PackageInfo?): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return get(context.packageManager, flags)
    }

    @Suppress("DEPRECATION")
    private fun facts(info: PackageInfo?): SignerFacts? {
        info ?: return null
        val (current, lineage) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return null
            if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners.map { it.sha256() }.toSet() to emptySet()
            } else {
                val history = signing.signingCertificateHistory.orEmpty().map { it.sha256() }
                setOfNotNull(history.lastOrNull()) to history.toSet()
            }
        } else {
            info.signatures.orEmpty().map { it.sha256() }.toSet() to emptySet()
        }
        return SignerFacts(info.packageName, PackageInfoCompat.getLongVersionCode(info), current, lineage)
    }

    private fun Signature.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }
}
