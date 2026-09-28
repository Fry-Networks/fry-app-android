package com.frynetworks.fryapp.update

sealed interface UpdateDecision {
    data object UpToDate : UpdateDecision

    /** A newer build; [required] when the installed build is below the manifest's minimum. */
    data class Offer(val manifest: UpdateManifest, val required: Boolean) : UpdateDecision

    data class Rejected(val reason: String) : UpdateDecision
}

/** Only a strictly higher versionCode of this very package is an update; never a downgrade. */
object VersionPolicy {
    fun decide(installedPackage: String, installedVersionCode: Long, manifest: UpdateManifest): UpdateDecision = when {
        manifest.packageName != installedPackage -> UpdateDecision.Rejected("manifest is for ${manifest.packageName}, this app is $installedPackage")
        manifest.versionCode <= installedVersionCode -> UpdateDecision.UpToDate
        else -> UpdateDecision.Offer(manifest, required = installedVersionCode < manifest.minSupportedVersionCode)
    }
}
