package com.frynetworks.fryapp.update

/** Signing facts of an installed app or an APK file; digests are lowercase SHA-256 hex of the certificate. */
data class SignerFacts(
    val packageName: String,
    val versionCode: Long,
    /** The current signer(s). */
    val signers: Set<String>,
    /** Every certificate in the APK's v3 rotation lineage, current one included; empty without one. */
    val lineage: Set<String> = emptySet(),
)

sealed interface SignerVerdict {
    data object Allow : SignerVerdict
    data class Deny(val reason: String) : SignerVerdict
}

/**
 * Fails closed. The candidate APK is installed only when it is the package and versionCode the
 * manifest promised, is signed by a certificate the manifest pins, and is signed by the installed
 * app's own certificate or by a successor that lists it in its rotation lineage. Android would
 * refuse a mismatched signer anyway; checking first keeps a bad APK from ever opening a session.
 */
object SignerPolicy {
    fun check(installed: SignerFacts?, candidate: SignerFacts?, manifest: UpdateManifest): SignerVerdict {
        if (installed == null || installed.signers.isEmpty()) return SignerVerdict.Deny("could not read this app's own signature")
        if (candidate == null || candidate.signers.isEmpty()) return SignerVerdict.Deny("could not read the download's signature")
        if (candidate.packageName != manifest.packageName || candidate.packageName != installed.packageName) {
            return SignerVerdict.Deny("download is package ${candidate.packageName}")
        }
        if (candidate.versionCode != manifest.versionCode) return SignerVerdict.Deny("download is version ${candidate.versionCode}, manifest says ${manifest.versionCode}")
        if (!manifest.certSha256.containsAll(candidate.signers)) return SignerVerdict.Deny("download is signed by a certificate the manifest does not pin")
        val sameSigner = candidate.signers == installed.signers
        val rotatedFrom = candidate.lineage.isNotEmpty() && candidate.lineage.containsAll(installed.signers)
        if (!sameSigner && !rotatedFrom) return SignerVerdict.Deny("download is not signed by this app's certificate or its successor")
        return SignerVerdict.Allow
    }
}
