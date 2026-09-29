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
        facts(packageInfo(archive = false) { pm, flags -> pm.getPackageInfo(context.packageName, flags) })
    }.getOrNull()

    fun candidate(apk: File): SignerFacts? = runCatching {
        facts(packageInfo(archive = true) { pm, flags -> pm.getPackageArchiveInfo(apk.absolutePath, flags) })
    }.getOrNull()

    @SuppressLint("PackageManagerGetSignatures")
    private fun packageInfo(archive: Boolean, get: (PackageManager, Int) -> PackageInfo?): PackageInfo? =
        get(context.packageManager, signatureFlags(Build.VERSION.SDK_INT, archive))

    companion object {
        /**
         * `getPackageArchiveInfo` honours GET_SIGNING_CERTIFICATES only from API 29: on API 28 the
         * archive call must also ask for the legacy GET_SIGNATURES or it reports no signer at all,
         * and the update is refused for good (fail-closed, but never updating on Android 9).
         */
        @Suppress("DEPRECATION")
        @SuppressLint("InlinedApi")
        internal fun signatureFlags(sdkInt: Int, archive: Boolean): Int = when {
            sdkInt >= Build.VERSION_CODES.Q -> PackageManager.GET_SIGNING_CERTIFICATES
            sdkInt == Build.VERSION_CODES.P ->
                if (archive) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNING_CERTIFICATES
            else -> PackageManager.GET_SIGNATURES
        }

        /** `signingInfo` when the platform filled it in, else the legacy `signatures` (below P, and an API 28 archive). */
        internal fun facts(info: PackageInfo?): SignerFacts? {
            info ?: return null
            val (current, lineage) = modernSigners(info) ?: legacySigners(info)
            return SignerFacts(info.packageName, PackageInfoCompat.getLongVersionCode(info), current, lineage)
        }

        /** The v3 signing facts on P+, or null when the platform left `signingInfo` empty. */
        private fun modernSigners(info: PackageInfo): Pair<Set<String>, Set<String>>? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            val signing = info.signingInfo ?: return null
            return if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners.map { it.sha256() }.toSet() to emptySet()
            } else {
                val history = signing.signingCertificateHistory.orEmpty().map { it.sha256() }
                setOfNotNull(history.lastOrNull()) to history.toSet()
            }
        }

        @Suppress("DEPRECATION")
        private fun legacySigners(info: PackageInfo): Pair<Set<String>, Set<String>> =
            info.signatures.orEmpty().map { it.sha256() }.toSet() to emptySet()

        private fun Signature.sha256(): String =
            MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
