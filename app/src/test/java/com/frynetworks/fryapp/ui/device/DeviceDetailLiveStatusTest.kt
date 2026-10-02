package com.frynetworks.fryapp.ui.device

import com.frynetworks.fryapp.auth.SessionProfile
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.data.DeviceRepository
import com.frynetworks.fryapp.data.dashboard.api.DashboardErrorCodes
import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.OTHER_ADDRESS
import com.frynetworks.fryapp.fakes.TEST_ADDRESS
import com.frynetworks.fryapp.fakes.TestSession
import com.frynetworks.fryapp.network.dashboard.DashboardException
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AP5 lifecycle: the device screen polls `POST /api/devices/{key}` (MinerRepository.refreshDetail,
 * session wallet only) only while something collects [DeviceDetailViewModel.liveStatus], i.e. while
 * the screen is visible (collectAsStateWithLifecycle = STARTED). Virtual time; fake repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDetailLiveStatusTest {

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val base = 1_759_400_000_000L
    private val minute = 60_000L
    private val miners = FakeMinerRepository()
    private val devices = mockk<DeviceRepository>(relaxed = true) { every { observeDevices() } returns flowOf(emptyList()) }

    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    /** (virtual ms, session address) of every `refreshDetail` the screen makes. */
    private val requests = mutableListOf<Pair<Long, String?>>()

    /** Simulated network time per request; 0 answers without suspending. */
    private var latencyMs = 0L

    private fun TestScope.vm(session: SessionRepository = TestSession.signedIn()): DeviceDetailViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val recording = object : MinerRepository by miners {
            override suspend fun refreshDetail(minerKey: String): DeviceDetail {
                requests += testScheduler.currentTime to session.signedInAddress
                if (latencyMs > 0) delay(latencyMs)
                return miners.refreshDetail(minerKey)
            }
        }
        return DeviceDetailViewModel(key, devices, recording, session) { base + testScheduler.currentTime }
    }

    private fun requestSeconds() = requests.map { it.first / 1000 }

    private fun answer(isActive: Boolean?) {
        miners.refreshDetailError = null
        miners.details.value = mapOf(key to DeviceDetail(minerKey = key, isActive = isActive))
    }

    /** Stands in for the visible screen: collects until the returned job is cancelled. */
    private fun TestScope.show(vm: DeviceDetailViewModel, seen: MutableList<LiveStatus> = mutableListOf()): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.liveStatus.collect { seen += it } }

    @Test
    fun `nothing is fetched until the screen is visible`() = runTest {
        answer(true)
        val vm = vm()
        advanceTimeBy(30 * minute)
        assertEquals(0, miners.refreshDetailCalls)
        assertEquals(LiveStatus.Checking, vm.liveStatus.value)
    }

    @Test
    fun `visible - first fetch at once then every 60 s`() = runTest {
        answer(true)
        val vm = vm()
        show(vm)
        runCurrent()
        assertEquals(1, miners.refreshDetailCalls)
        assertEquals(LiveStatus.Known("Active", base), vm.liveStatus.value)
        advanceTimeBy(minute - 1); runCurrent()
        assertEquals(1, miners.refreshDetailCalls)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, miners.refreshDetailCalls)
        advanceTimeBy(minute); runCurrent()
        assertEquals(3, miners.refreshDetailCalls)
    }

    @Test
    fun `hidden - polling stops and resumes with an immediate fetch`() = runTest {
        answer(false)
        val vm = vm()
        val screen = show(vm)
        runCurrent()
        assertEquals(1, miners.refreshDetailCalls)
        screen.cancel()
        advanceTimeBy(30 * minute); runCurrent()
        assertEquals("no fetch while hidden", 1, miners.refreshDetailCalls)
        show(vm)
        runCurrent()
        assertEquals(2, miners.refreshDetailCalls)
        assertEquals(LiveStatus.Known("Inactive: no recent heartbeat on the dashboard", base + 30 * minute), vm.liveStatus.value)
    }

    @Test
    fun `resume after 20 min shows unavailable, never the old answer, until the dashboard answers`() = runTest {
        answer(true)
        val vm = vm()
        val screen = show(vm)
        runCurrent()
        screen.cancel()
        advanceTimeBy(20 * minute); runCurrent()
        val seen = mutableListOf<LiveStatus>()
        miners.refreshDetailError = DashboardException("INTERNAL_ERROR", "down", httpStatus = 500)
        show(vm, seen)
        runCurrent()
        assertTrue("stale Known shown on resume: $seen", seen.none { it is LiveStatus.Known })
        assertEquals(LiveStatus.Unavailable(base), vm.liveStatus.value)
    }

    @Test
    fun `resume after more than 15 min never re-emits the old answer as current`() = runTest {
        answer(true)
        val vm = vm()
        val screen = show(vm)
        runCurrent()
        screen.cancel()
        advanceTimeBy(16 * minute); runCurrent()
        latencyMs = 1_000
        val seen = mutableListOf<LiveStatus>()
        show(vm, seen)
        runCurrent()
        assertEquals("while the request is in flight", LiveStatus.Unavailable(base), vm.liveStatus.value)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(LiveStatus.Known("Active", base + 16 * minute + 1_000), vm.liveStatus.value)
        assertTrue("old answer re-emitted: $seen", LiveStatus.Known("Active", base) !in seen)
    }

    @Test
    fun `every reachable state - checking, known x3, unavailable`() = runTest {
        val seen = mutableListOf<LiveStatus>()
        answer(null)
        val vm = vm()
        show(vm, seen)
        runCurrent()
        assertEquals(LiveStatus.Checking, seen.first())
        assertEquals(LiveStatus.Known("Not reported by the dashboard", base), vm.liveStatus.value)
        answer(true); advanceTimeBy(minute); runCurrent()
        assertEquals(LiveStatus.Known("Active", base + minute), vm.liveStatus.value)
        answer(false); advanceTimeBy(minute); runCurrent()
        assertEquals(LiveStatus.Known("Inactive: no recent heartbeat on the dashboard", base + 2 * minute), vm.liveStatus.value)
        miners.refreshDetailError = DashboardException("INTERNAL_ERROR", "down", httpStatus = 500)
        advanceTimeBy(minute); runCurrent()
        assertEquals(LiveStatus.Unavailable(base + 2 * minute), vm.liveStatus.value)
    }

    @Test
    fun `a missing dashboard record is not reported, at the normal cadence`() = runTest {
        miners.refreshDetailError = DashboardException(DashboardErrorCodes.DEVICE_NOT_FOUND, "Device not found", httpStatus = 404)
        val vm = vm()
        show(vm)
        runCurrent()
        assertEquals(LiveStatus.Known("Not reported by the dashboard", base), vm.liveStatus.value)
        advanceTimeBy(minute); runCurrent()
        assertEquals(2, miners.refreshDetailCalls)
    }

    @Test
    fun `errors back off at exactly 60 s, 2, 4, then 5 min, for every failure kind`() = runTest {
        val failures = listOf(
            DashboardException(DashboardErrorCodes.WALLET_MISMATCH, "mismatch", httpStatus = 401),
            DashboardException("INTERNAL_ERROR", "down", httpStatus = 500),
            DashboardException(DashboardErrorCodes.NETWORK_ERROR, "offline"),
            java.io.IOException("timeout"),
        )
        for (failure in failures) {
            requests.clear()
            miners.refreshDetailError = failure
            val vm = vm()
            val screen = show(vm)
            val start = testScheduler.currentTime / 1000
            advanceTimeBy(20 * minute); runCurrent()
            assertEquals("$failure", listOf(0L, 60L, 180L, 420L, 720L, 1020L), requestSeconds().map { it - start })
            assertEquals(LiveStatus.Unavailable(null), vm.liveStatus.value)
            screen.cancel()
        }
    }

    @Test
    fun `an answer after errors resets the cadence to 60 s and lastChecked is the last success`() = runTest {
        answer(true)
        val vm = vm()
        show(vm)
        runCurrent()
        miners.refreshDetailError = DashboardException("INTERNAL_ERROR", "down", httpStatus = 500)
        advanceTimeBy(4 * minute); runCurrent()                     // fails at 60, 120 and 240 s
        assertEquals(listOf(0L, 60L, 120L, 240L), requestSeconds())
        assertEquals(LiveStatus.Unavailable(base), vm.liveStatus.value)
        answer(false)
        advanceTimeBy(5 * minute); runCurrent()                     // 480 s answers, then 60 s again
        assertEquals(listOf(0L, 60L, 120L, 240L, 480L, 540L), requestSeconds())
        assertEquals(LiveStatus.Known("Inactive: no recent heartbeat on the dashboard", base + 540_000), vm.liveStatus.value)
    }

    @Test
    fun `steady state makes at most one request per minute while visible`() = runTest {
        answer(true)
        val vm = vm()
        show(vm)
        advanceTimeBy(60 * minute); runCurrent()
        val perMinute = requests.groupingBy { it.first / minute }.eachCount()
        assertTrue("per-minute counts $perMinute", perMinute.values.all { it <= 1 })
        assertEquals(61, requests.size)
    }

    @Test
    fun `signed out - no fetch and the sign-in prompt`() = runTest {
        answer(true)
        val vm = vm(TestSession.signedOut())
        show(vm)
        advanceTimeBy(10 * minute); runCurrent()
        assertEquals(0, miners.refreshDetailCalls)
        assertEquals(LiveStatus.SignedOut, vm.liveStatus.value)
    }

    @Test
    fun `signing out while visible stops polling and drops the answer`() = runTest {
        answer(true)
        val session = TestSession.signedIn()
        val vm = vm(session)
        show(vm)
        runCurrent()
        assertEquals(1, miners.refreshDetailCalls)
        session.markSignedOut()
        assertEquals(LiveStatus.SignedOut, vm.liveStatus.value)
        assertEquals(1, requests.size)
        advanceTimeBy(10 * minute); runCurrent()
        assertEquals("0 further requests after sign-out", 1, requests.size)
    }

    @Test
    fun `signing in while visible starts polling with the new address`() = runTest {
        answer(true)
        val session = TestSession.signedOut()
        val vm = vm(session)
        show(vm)
        advanceTimeBy(5 * minute); runCurrent()
        assertEquals(0, requests.size)
        session.markSignedIn(SessionProfile(OTHER_ADDRESS), fingerprintBound = true)
        runCurrent()
        assertEquals(listOf(300_000L to OTHER_ADDRESS), requests)
        assertEquals(LiveStatus.Known("Active", base + 300_000), vm.liveStatus.value)
        advanceTimeBy(minute); runCurrent()
        assertEquals(listOf(OTHER_ADDRESS, OTHER_ADDRESS), requests.map { it.second })
    }

    @Test
    fun `switching wallet restarts with a fresh fetch and no carried-over answer`() = runTest {
        answer(true)
        val session = TestSession.signedIn()
        val vm = vm(session)
        val seen = mutableListOf<LiveStatus>()
        show(vm, seen)
        runCurrent()
        advanceTimeBy(30_000)
        latencyMs = 1_000
        val before = seen.size
        session.markSignedIn(SessionProfile(OTHER_ADDRESS), fingerprintBound = true)
        runCurrent()
        assertEquals(listOf(TEST_ADDRESS, OTHER_ADDRESS), requests.map { it.second })
        assertEquals("first wallet's answer not carried over", LiveStatus.Checking, vm.liveStatus.value)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(LiveStatus.Known("Active", base + 31_000), vm.liveStatus.value)
        assertTrue("after switch: ${seen.drop(before)}", LiveStatus.Known("Active", base) !in seen.drop(before))
    }
}
