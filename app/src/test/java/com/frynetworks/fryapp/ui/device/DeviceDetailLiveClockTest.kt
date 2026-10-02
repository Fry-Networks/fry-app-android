package com.frynetworks.fryapp.ui.device

import com.frynetworks.fryapp.data.DeviceRepository
import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.TestSession
import com.frynetworks.fryapp.network.dashboard.DashboardException
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * AP5 review r1 (F2): the screen's "checked" ages move with a ticking clock while the status value
 * stays the same, and the clock never makes a request.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDetailLiveClockTest {

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val base = 1_759_400_000_000L
    private val minute = 60_000L
    private val miners = FakeMinerRepository()
    private val devices = mockk<DeviceRepository>(relaxed = true) { every { observeDevices() } returns flowOf(emptyList()) }
    private val requests = mutableListOf<Long>()
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

    /** What the visible screen collects: the status and the clock; returns the latest clock value. */
    private fun TestScope.showWithClock(vm: DeviceDetailViewModel): () -> Long {
        var now = 0L
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.liveStatus.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.liveClock.collect { now = it } }
        return { now }
    }

    @Test
    fun `the checked text advances with the clock while the answer is unchanged`() = runTest {
        answeredBeforeHang = 1
        val vm = vm()
        val clock = showWithClock(vm)
        runCurrent()
        val answer = LiveStatus.Known("Active", base)
        assertEquals("Checked just now", LiveStatusText.checked(vm.liveStatus.value, clock()))
        advanceTimeBy(2 * minute); runCurrent()
        assertEquals(answer, vm.liveStatus.value)
        assertEquals("Checked 2m ago", LiveStatusText.checked(vm.liveStatus.value, clock()))
        advanceTimeBy(3 * minute); runCurrent()
        assertEquals(answer, vm.liveStatus.value)
        assertEquals("Checked 5m ago", LiveStatusText.checked(vm.liveStatus.value, clock()))
        assertEquals(listOf(0L, minute), requests)
    }

    @Test
    fun `during an outage the last-checked age keeps moving`() = runTest {
        val vm = vm()
        val clock = showWithClock(vm)
        runCurrent()
        miners.refreshDetailError = DashboardException("INTERNAL_ERROR", "down", httpStatus = 500)
        advanceTimeBy(2 * minute); runCurrent()
        assertEquals(LiveStatus.Unavailable(base), vm.liveStatus.value)
        assertEquals("Status unavailable · last checked 2m ago", LiveStatusText.status(vm.liveStatus.value, clock()))
        advanceTimeBy(2 * minute); runCurrent()
        assertEquals(LiveStatus.Unavailable(base), vm.liveStatus.value)
        assertEquals("Status unavailable · last checked 4m ago", LiveStatusText.status(vm.liveStatus.value, clock()))
    }

    @Test
    fun `the clock adds no request`() = runTest {
        val withoutClock = run {
            val vm = vm()
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.liveStatus.collect {} }
            advanceTimeBy(30 * minute); runCurrent()
            job.cancel()
            requests.size
        }
        requests.clear()
        val vm = vm()
        showWithClock(vm)
        advanceTimeBy(30 * minute); runCurrent()
        assertEquals(31, withoutClock)
        assertEquals(withoutClock, requests.size)
    }

    @Test
    fun `the clock ticks about every 30 s and only while collected`() = runTest {
        val vm = vm()
        val ticks = mutableListOf<Long>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.liveClock.collect { ticks += it - base } }
        advanceTimeBy(90_000); runCurrent()
        assertEquals(listOf(0L, 30_000L, 60_000L, 90_000L), ticks)
        job.cancel()
        advanceTimeBy(10 * minute); runCurrent()
        assertEquals(4, ticks.size)
        assertEquals("the clock alone never polls", 0, requests.size)
    }
}
