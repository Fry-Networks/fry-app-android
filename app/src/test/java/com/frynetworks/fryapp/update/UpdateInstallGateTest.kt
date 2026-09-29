package com.frynetworks.fryapp.update

import com.frynetworks.fryapp.data.dashboard.model.ClaimEnvelopeResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimPreviewResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimTotalDto
import com.frynetworks.fryapp.data.dashboard.model.ConfirmResultResponse
import com.frynetworks.fryapp.domain.ClaimState
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.fakes.FakeAlgodRepository
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.FakeRewardsRepository
import com.frynetworks.fryapp.fakes.FakeWalletBridge
import com.frynetworks.fryapp.fakes.TEST_ADDRESS
import com.frynetworks.fryapp.fakes.TestSession
import com.frynetworks.fryapp.fakes.fixedClock
import com.frynetworks.fryapp.ui.miners.claim.ClaimViewModel
import com.frynetworks.fryapp.update.UpdateFixtures.PIN
import com.frynetworks.fryapp.update.UpdateFixtures.PKG
import com.frynetworks.fryapp.wallet.WalletBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigDecimal

/**
 * Money safety (r3 behaviour #2): once the app is its own installer, a background pass installs
 * silently and Android kills the process. So a claim (or stake) holds the install inhibitor from
 * the first signature to the dashboard's confirm, and a pass that nobody asked for (launch, daily)
 * never installs while the app is on screen; only the user's own check may.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateInstallGateTest {

    @get:Rule val tmp = TemporaryFolder()

    private inner class FakeSources : UpdateSources {
        var onDownload: () -> Unit = {}
        val downloads = mutableListOf<UpdateManifest>()
        val installs = mutableListOf<File>()
        var lastApk: File? = null
        override fun channel() = UpdateChannel.STABLE
        override fun manifestText(channel: UpdateChannel): String? = UpdateFixtures.json(versionCode = 7)
        override fun download(manifest: UpdateManifest): DownloadResult {
            downloads += manifest
            onDownload()
            return DownloadResult.Ok(File.createTempFile("fryapp-7-", ".apk", tmp.root).also { lastApk = it })
        }
        override fun installedSigner() = SignerFacts(PKG, 6, setOf(PIN))
        override fun candidateSigner(apk: File) = SignerFacts(PKG, 7, setOf(PIN), setOf(PIN))
        override fun install(apk: File, packageName: String): Boolean { installs += apk; return true }
    }

    private class MemoryStore : UpdateStore {
        override var lastCheckMillis = 0L
        override val lastInstallMessage: String? = null
    }

    /** The scripted bridge, with `submit` held until [gate] completes: a signed transaction in flight. */
    private class GatedBridge(private val inner: FakeWalletBridge, private val gate: CompletableDeferred<Unit>) : WalletBridge by inner {
        override suspend fun submit(signedB64: List<String>, waitRounds: Int): List<String> {
            gate.await()
            return inner.submit(signedB64, waitRounds)
        }
    }

    private val key = "FEM-TESTKEY0000000000000000000000001"
    private val asset = FryAsset.FNODE
    private val inhibitor = InstallInhibitor()
    private val sources = FakeSources()
    private var onScreen = false
    private val coordinator = UpdateCoordinator(PKG, 6, sources, MemoryStore(), { inhibitor.inhibited }, { 10_000_000L }, { onScreen })

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMain() = Dispatchers.resetMain()

    private fun claimViewModel(bridge: WalletBridge): ClaimViewModel {
        val rewards = FakeRewardsRepository().apply {
            preview = ClaimPreviewResponse(success = true, preview = true, totals = listOf(ClaimTotalDto(asset.id.toString(), BigDecimal("4.25"))))
            envelope = ClaimEnvelopeResponse(success = true, mode = "user_pays", groupId = "G1", unsignedUserLeg = "USERLEG", unsignedServerLegs = listOf("SERVER1"))
            confirmResult = ConfirmResultResponse(ok = true, txId = "CONFIRMTX", claimedAt = "2026-09-11T12:00:00.000Z")
        }
        val algod = FakeAlgodRepository().apply { markOptedIn(asset.id) }
        return ClaimViewModel(rewards, algod, FakeMinerRepository(), bridge, TestSession.signedIn(), fixedClock(1_757_592_000_000L), openUri = {}, pollDelayMillis = 0, optInRetryDelayMillis = 0, inhibitor = inhibitor)
    }

    @Test
    fun `a claim in flight holds the install inhibitor until the dashboard confirmed it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeWalletBridge().apply { reconnectAddress = TEST_ADDRESS }
        val vm = claimViewModel(GatedBridge(fake, gate))
        vm.start(key)
        assertFalse(inhibitor.inhibited)

        vm.confirm() // signs the fee payment and suspends inside submit
        assertTrue("inhibited while the signed transaction is in flight", inhibitor.inhibited)
        assertEquals(UpdateState.Deferred("0.4.1-rc.1", UpdateCoordinator.DEFERRED_PROVISIONING), coordinator.check(UpdateTrigger.DAILY))
        assertTrue(sources.downloads.isEmpty() && sources.installs.isEmpty())

        gate.complete(Unit)
        assertTrue("${vm.uiState.value.state}", vm.uiState.value.state is ClaimState.Done)
        assertFalse("released once the claim is confirmed", inhibitor.inhibited)
        assertEquals(UpdateState.Installing("0.4.1-rc.1"), coordinator.check(UpdateTrigger.MANUAL))
    }

    @Test
    fun `a rejected signature releases the inhibitor`() = runTest {
        val fake = FakeWalletBridge().apply { reconnectAddress = TEST_ADDRESS; signBehaviour = listOf(FakeWalletBridge.SignBehaviour.Reject) }
        val vm = claimViewModel(fake)
        vm.start(key)
        vm.confirm()
        assertTrue("${vm.uiState.value.state}", vm.uiState.value.state is ClaimState.Failed)
        assertFalse(inhibitor.inhibited)
    }

    @Test
    fun `cancelling the claim job while the transaction is in flight releases the inhibitor`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeWalletBridge().apply { reconnectAddress = TEST_ADDRESS }
        val vm = claimViewModel(GatedBridge(fake, gate))
        vm.start(key)
        vm.confirm() // suspended inside submit, holding the inhibitor
        assertTrue(inhibitor.inhibited)

        vm.start(key) // cancels the in-flight job before starting over
        assertFalse("released by the cancelled job's finally", inhibitor.inhibited)
        assertEquals(UpdateState.Installing("0.4.1-rc.1"), coordinator.check(UpdateTrigger.MANUAL))
    }

    @Test
    fun `an unattended pass never installs while the app is on screen, a manual one does`() = runTest {
        onScreen = true
        for (trigger in listOf(UpdateTrigger.DAILY, UpdateTrigger.LAUNCH)) {
            assertEquals("$trigger", UpdateState.Deferred("0.4.1-rc.1", UpdateCoordinator.DEFERRED_IN_USE), coordinator.check(trigger))
        }
        assertTrue(sources.downloads.isEmpty() && sources.installs.isEmpty())
        assertEquals(UpdateState.Installing("0.4.1-rc.1"), coordinator.check(UpdateTrigger.MANUAL))
        assertEquals(1, sources.installs.size)

        onScreen = false
        assertEquals(UpdateState.Installing("0.4.1-rc.1"), coordinator.check(UpdateTrigger.DAILY))
    }

    @Test
    fun `the app coming to the foreground during the download still defers the install, and the APK is not kept`() = runTest {
        sources.onDownload = { onScreen = true }
        assertEquals(UpdateState.Deferred("0.4.1-rc.1", UpdateCoordinator.DEFERRED_IN_USE), coordinator.check(UpdateTrigger.DAILY))
        assertEquals(1, sources.downloads.size)
        assertTrue(sources.installs.isEmpty())
        assertFalse("a deferred download is deleted", sources.lastApk!!.exists())
    }
}
