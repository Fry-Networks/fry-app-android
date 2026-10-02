package com.frynetworks.fryapp.ui.device

import com.frynetworks.fryapp.data.DeviceRepository
import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.TestSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
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
import org.junit.Test

/**
 * AP5 review r1 (F2, F4): an answer turns unavailable after 15 minutes on its own, while visible,
 * even when no new answer arrives; hiding the screen stops requests at once (no sharing grace).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDetailLiveStatusStalenessTest {

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val base = 1_759_400_000_000L
    private val minute = 60_000L
    private val miners = FakeMinerRepository()
    private val devices = mockk<DeviceRepository>(relaxed = true) { every { observeDevices() } returns flowOf(emptyList()) }
    private val requests = mutableListOf<Long>()

    /** After this many answered requests, every further request hangs forever (no emission). */
    private var answeredBeforeHang = Int.MAX_VALUE

    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.vm(): DeviceDetailViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        miners.details.value = mapOf(key to DeviceDetail(minerKey = key, isActive = true))
        val recording = object : MinerRepository by miners {
            override suspend fun refreshDetail(minerKey: String): DeviceDetail {
                requests += testScheduler.currentTime
                if (requests.size > answeredBeforeHang) awaitCancellation()
                return miners.refreshDetail(minerKey)
            }
        }
        return DeviceDetailViewModel(key, devices, recording, TestSession.signedIn()) { base + testScheduler.currentTime }
    }

    private fun TestScope.show(vm: DeviceDetailViewModel): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.liveStatus.collect {} }

    @Test
    fun `a visible answer turns unavailable after 15 min with no new emission`() = runTest {
        answeredBeforeHang = 1
        val vm = vm()
        show(vm)
        runCurrent()
        assertEquals(LiveStatus.Known("Active", base), vm.liveStatus.value)
        advanceTimeBy(15 * minute); runCurrent()
        assertEquals("15 min old is still current", LiveStatus.Known("Active", base), vm.liveStatus.value)
        advanceTimeBy(30_000); runCurrent()                     // one ~30 s tick
        assertEquals(LiveStatus.Unavailable(base), vm.liveStatus.value)
        assertEquals("the hung request is the only one after the answer", listOf(0L, minute), requests)
    }

    @Test
    fun `hiding just before a poll is due makes no further request`() = runTest {
        val vm = vm()
        val screen = show(vm)
        runCurrent()
        advanceTimeBy(59_900); runCurrent()
        assertEquals(listOf(0L), requests)
        screen.cancel()
        advanceTimeBy(5_100); runCurrent()
        assertEquals("a request after hiding means a sharing timeout > 0", listOf(0L), requests)
    }

    @Test
    fun `steady state request count is one per minute with the ticker running`() = runTest {
        val vm = vm()
        show(vm)
        advanceTimeBy(30 * minute); runCurrent()
        assertEquals((0..30).map { it * minute }, requests)
    }
}
