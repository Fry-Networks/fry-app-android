package com.frynetworks.fryapp.update

import com.frynetworks.fryapp.update.UpdateFixtures.PIN
import com.frynetworks.fryapp.update.UpdateFixtures.PKG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The last word before `session.commit()`: writing the APK into the install session takes a
 * moment, so the gateway asks the coordinator once more whether it may commit. A board setup, a
 * signed transaction or an activity that appeared in that window abandons the session instead.
 */
class InstallCommitVetoTest {

    @get:Rule val tmp = TemporaryFolder()

    /** A gateway that, like the real one, finishes the session write and then consults the veto. */
    private inner class FakeGatewaySources : UpdateSources {
        var duringSessionWrite: () -> Unit = {}
        var vetoAnswers = mutableListOf<Boolean>()
        val commits = mutableListOf<File>()
        val abandoned = mutableListOf<File>()
        var lastApk: File? = null
        override fun channel() = UpdateChannel.STABLE
        override fun manifestText(channel: UpdateChannel): String? = UpdateFixtures.json(versionCode = 7)
        override fun download(manifest: UpdateManifest) = DownloadResult.Ok(File.createTempFile("fryapp-7-", ".apk", tmp.root).also { lastApk = it })
        override fun installedSigner() = SignerFacts(PKG, 6, setOf(PIN))
        override fun candidateSigner(apk: File) = SignerFacts(PKG, 7, setOf(PIN), setOf(PIN))
        override fun install(apk: File, packageName: String): Boolean = error("the coordinator must install through the veto")
        override fun install(apk: File, packageName: String, mayCommit: () -> Boolean): Boolean {
            duringSessionWrite()
            val allowed = mayCommit()
            vetoAnswers += allowed
            if (allowed) commits += apk else abandoned += apk
            return allowed
        }
    }

    private class MemoryStore : UpdateStore {
        override var lastCheckMillis = 0L
        override val lastInstallMessage: String? = null
    }

    private var inhibited = false
    private var onScreen = false
    private val sources = FakeGatewaySources()
    private val coordinator = UpdateCoordinator(PKG, 6, sources, MemoryStore(), { inhibited }, { 10_000_000L }, { onScreen })

    @Test
    fun `nothing changed during the session write - the commit goes ahead`() {
        assertEquals(UpdateState.Installing("0.4.1-rc.1"), coordinator.check(UpdateTrigger.DAILY))
        assertEquals(listOf(true), sources.vetoAnswers)
        assertEquals(1, sources.commits.size)
    }

    @Test
    fun `an activity resumed during the session write vetoes an unattended commit`() {
        sources.duringSessionWrite = { onScreen = true }
        assertEquals(UpdateState.Deferred("0.4.1-rc.1", UpdateCoordinator.DEFERRED_IN_USE), coordinator.check(UpdateTrigger.DAILY))
        assertEquals(listOf(false), sources.vetoAnswers)
        assertTrue(sources.commits.isEmpty())
        assertEquals(1, sources.abandoned.size)
        assertFalse("the abandoned APK is not kept", sources.lastApk!!.exists())
    }

    @Test
    fun `a board setup or signed transaction started during the session write vetoes even a manual commit`() {
        sources.duringSessionWrite = { inhibited = true }
        assertEquals(UpdateState.Deferred("0.4.1-rc.1", UpdateCoordinator.DEFERRED_PROVISIONING), coordinator.check(UpdateTrigger.MANUAL))
        assertEquals(listOf(false), sources.vetoAnswers)
        assertTrue(sources.commits.isEmpty())
    }

    @Test
    fun `the user's own check is not vetoed by the app being on screen`() {
        sources.duringSessionWrite = { onScreen = true }
        assertEquals(UpdateState.Installing("0.4.1-rc.1"), coordinator.check(UpdateTrigger.MANUAL))
        assertEquals(listOf(true), sources.vetoAnswers)
    }
}
