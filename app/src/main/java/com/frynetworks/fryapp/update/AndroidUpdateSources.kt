package com.frynetworks.fryapp.update

import android.content.Context
import java.io.File

/** Production [UpdateSources]: the channel file, GitHub releases, PackageManager, PackageInstaller. */
class AndroidUpdateSources(
    private val context: Context,
    private val downloader: ApkDownloader = ApkDownloader(),
    private val inspector: ApkInspector = ApkInspector(context),
    private val installer: PackageInstallerGateway = PackageInstallerGateway(context),
) : UpdateSources {

    override fun channel(): UpdateChannel = ChannelSelector.read(context)

    override fun manifestText(channel: UpdateChannel): String? = downloader.fetchText(channel.manifestUrl)

    override fun download(manifest: UpdateManifest): DownloadResult {
        val dir = File(context.cacheDir, "updates")
        // Only the newest download is kept.
        dir.listFiles()?.forEach { it.delete() }
        return downloader.download(manifest, File(dir, "fryapp-${manifest.versionCode}.apk"))
    }

    override fun installedSigner(): SignerFacts? = inspector.installed()

    override fun candidateSigner(apk: File): SignerFacts? = inspector.candidate(apk)

    override fun install(apk: File, packageName: String): Boolean = installer.install(apk, packageName)

    override fun install(apk: File, packageName: String, mayCommit: () -> Boolean): Boolean = installer.install(apk, packageName, mayCommit)
}
