package com.frynetworks.fryapp.update

import com.frynetworks.fryapp.update.UpdateFixtures.OTHER_CERT
import com.frynetworks.fryapp.update.UpdateFixtures.PIN
import com.frynetworks.fryapp.update.UpdateFixtures.PKG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateCoordinatorTest {

    @get:Rule val tmp = TemporaryFolder()

    private inner class FakeSources : UpdateSources {
        var channel = UpdateChannel.STABLE
        var manifest: String? = UpdateFixtures.json(versionCode = 7)
        var downloadResult: (UpdateManifest) -> DownloadResult = { DownloadResult.Ok(File.createTempFile("fryapp-${it.versionCode}-", ".apk", tmp.root)) }
        var candidate: (File) -> SignerFacts? = { SignerFacts(PKG, 7, setOf(PIN), setOf(PIN)) }
        var onManifest: () -> Unit = {}
        val manifestFetches = mutableListOf<UpdateChannel>()
        val downloads = mutableListOf<UpdateManifest>()
        val installs = mutableListOf<File>()
        var lastApk: File? = null

        override fun channel() = channel
        override fun manifestText(channel: UpdateChannel): String? { manifestFetches += channel; onManifest(); return manifest }
        override fun download(manifest: UpdateManifest): DownloadResult {
            downloads += manifest
            return downloadResult(manifest).also { lastApk = (it as? DownloadResult.Ok)?.file }
        }
        override fun installedSigner() = SignerFacts(PKG, 6, setOf(PIN))
        override fun candidateSigner(apk: File) = candidate(apk)
        override fun install(apk: File, packageName: String): Boolean { installs += apk; return true }
    }

    private class MemoryStore : UpdateStore {
        override var lastCheckMillis = 0L
        override val lastInstallMessage: String? = null
    }

    private var now = 10_000_000L
    private var inhibited = false
    private val sources = FakeSources()
    private val coordinator = UpdateCoordinator(PKG, 6, sources, MemoryStore(), { inhibited }, { now })

    @Test
    fun `a verified newer build is installed`() {
        assertEquals(UpdateState.Installing("0.4.1-rc.1"), coordinator.check(UpdateTrigger.MANUAL))
        assertEquals(1, sources.installs.size)
    }

    @Test
    fun `a tampered download never opens an install session`() {
        sources.downloadResult = { DownloadResult.Failed("download SHA-256 does not match the manifest") }
        val state = coordinator.check(UpdateTrigger.MANUAL)
        assertTrue("$state", state is UpdateState.Failed)
        assertTrue(sources.installs.isEmpty())
    }

    @Test
    fun `a download signed with the wrong certificate is deleted and never installed`() {
        sources.candidate = { SignerFacts(PKG, 7, setOf(OTHER_CERT)) }
        val state = coordinator.check(UpdateTrigger.MANUAL)
        assertTrue("$state", state is UpdateState.Failed && state.reason.contains("refused"))
        assertTrue(sources.installs.isEmpty())
        assertFalse(sources.lastApk!!.exists())
    }

    @Test
    fun `an older or equal manifest is up to date and nothing is downloaded`() {
        sources.manifest = UpdateFixtures.json(versionCode = 6)
        assertEquals(UpdateState.UpToDate, coordinator.check(UpdateTrigger.MANUAL))
        sources.manifest = UpdateFixtures.json(versionCode = 5)
        assertEquals(UpdateState.UpToDate, coordinator.check(UpdateTrigger.MANUAL))
        assertTrue(sources.downloads.isEmpty())
    }

    @Test
    fun `an invalid manifest downloads nothing`() {
        sources.manifest = UpdateFixtures.json(url = "https://evil.example/fryapp.apk")
        assertTrue(coordinator.check(UpdateTrigger.MANUAL) is UpdateState.Failed)
        sources.manifest = null
        assertTrue(coordinator.check(UpdateTrigger.MANUAL) is UpdateState.Failed)
        assertTrue(sources.downloads.isEmpty())
    }

    @Test
    fun `the test channel reads the test pointer and requires a test manifest`() {
        sources.channel = UpdateChannel.TEST
        assertTrue(coordinator.check(UpdateTrigger.MANUAL) is UpdateState.Failed) // a stable manifest on the test pointer
        sources.manifest = UpdateFixtures.json(channel = "test")
        assertEquals(UpdateState.Installing("0.4.1-rc.1"), coordinator.check(UpdateTrigger.MANUAL))
        assertEquals(listOf(UpdateChannel.TEST, UpdateChannel.TEST), sources.manifestFetches)
    }

    @Test
    fun `launch checks are throttled to one per 6 hours, manual and daily checks are not`() {
        coordinator.check(UpdateTrigger.LAUNCH)
        now += UpdateCoordinator.LAUNCH_THROTTLE_MS - 1
        coordinator.check(UpdateTrigger.LAUNCH)
        assertEquals(1, sources.manifestFetches.size)
        coordinator.check(UpdateTrigger.MANUAL)
        coordinator.check(UpdateTrigger.DAILY)
        assertEquals(3, sources.manifestFetches.size)
        now += UpdateCoordinator.LAUNCH_THROTTLE_MS
        coordinator.check(UpdateTrigger.LAUNCH)
        assertEquals(4, sources.manifestFetches.size)
    }

    @Test
    fun `a check while one is running does not start a second pass`() {
        var nested: UpdateState? = null
        sources.onManifest = { if (nested == null) nested = coordinator.check(UpdateTrigger.MANUAL) }
        coordinator.check(UpdateTrigger.MANUAL)
        assertEquals(UpdateState.Checking, nested)
        assertEquals(1, sources.manifestFetches.size)
        assertEquals(1, sources.installs.size)
    }

    @Test
    fun `nothing installs while a board is being provisioned`() {
        inhibited = true
        val state = coordinator.check(UpdateTrigger.MANUAL)
        assertEquals(UpdateState.Deferred("0.4.1-rc.1", UpdateCoordinator.DEFERRED_PROVISIONING), state)
        assertTrue(sources.downloads.isEmpty())
        assertTrue(sources.installs.isEmpty())
    }

    @Test
    fun `the debug package never updates from a release manifest`() {
        val debug = UpdateCoordinator("$PKG.debug", 6, sources, MemoryStore(), { false }, { now })
        assertTrue(debug.check(UpdateTrigger.MANUAL) is UpdateState.NotApplicable)
        assertTrue(sources.downloads.isEmpty())
    }
}
