package com.frynetworks.fryapp.ui.miners

import com.frynetworks.fryapp.data.dashboard.model.ActivityEvent
import com.frynetworks.fryapp.data.dashboard.model.IotCredentials
import com.frynetworks.fryapp.data.dashboard.model.MyKeyDevice
import com.frynetworks.fryapp.data.dashboard.model.MyKeysResponse
import com.frynetworks.fryapp.data.dashboard.model.RewardItem
import com.frynetworks.fryapp.data.dashboard.model.RewardsPageResponse
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.FakeRewardsRepository
import com.frynetworks.fryapp.fakes.TestSession
import com.frynetworks.fryapp.fakes.fixedClock
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.ui.common.ErrorCopy
import com.frynetworks.fryapp.ui.common.Phase
import com.frynetworks.fryapp.ui.miners.activity.ActivityViewModel
import com.frynetworks.fryapp.ui.miners.keys.MinerKeysViewModel
import com.frynetworks.fryapp.ui.miners.rewards.RewardsHistoryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Rewards history, Miner keys and Activity ViewModels against the hand-written fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListScreensViewModelTest {

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val miners = FakeMinerRepository()
    private val rewards = FakeRewardsRepository()

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun page(vararg nos: Int, totalPages: Int) =
        RewardsPageResponse(success = true, items = nos.map { RewardItem(id = "id$it", no = it) }, totalPages = totalPages, weeklyCount = 2, dailyCount = 1, totalCount = nos.size)

    // --- Rewards history ---------------------------------------------------------------------

    @Test
    fun `rewards history loads page one and pages forward and back within bounds`() = runTest {
        rewards.pages = mapOf(1 to page(12, 11, totalPages = 2), 2 to page(5, totalPages = 2))
        val vm = RewardsHistoryViewModel(key, rewards, TestSession.signedIn())
        assertEquals(Phase.Content, vm.uiState.value.phase)
        assertEquals(listOf(12, 11), vm.uiState.value.items.map { it.no })
        assertEquals(2, vm.uiState.value.weeklyCount)
        assertFalse(vm.uiState.value.hasPrev)
        assertTrue(vm.uiState.value.hasNext)

        vm.next()
        assertEquals(2, vm.uiState.value.page)
        assertEquals(listOf(5), vm.uiState.value.items.map { it.no })
        assertFalse(vm.uiState.value.hasNext)
        vm.next()
        assertEquals(2, vm.uiState.value.page)

        vm.prev()
        assertEquals(1, vm.uiState.value.page)
        assertEquals(listOf(key to 1, key to 2, key to 1), rewards.pageCalls)
    }

    @Test
    fun `rewards history is Empty with no items and Error with copy on failure`() = runTest {
        val empty = RewardsHistoryViewModel(key, rewards, TestSession.signedIn())
        assertEquals(Phase.Empty, empty.uiState.value.phase)

        rewards.pageError = DashboardException("NOT_WIRED", "not wired")
        val failed = RewardsHistoryViewModel(key, rewards, TestSession.signedIn())
        assertEquals(Phase.Error, failed.uiState.value.phase)
        assertEquals(ErrorCopy.forCode("NOT_WIRED"), failed.uiState.value.error?.message)
    }

    @Test
    fun `per-row claim opens only when signed in and reloads the page when it completes`() = runTest {
        rewards.pages = mapOf(1 to page(12, totalPages = 1))
        val vm = RewardsHistoryViewModel(key, rewards, TestSession.signedIn())
        vm.openClaim(12)
        assertEquals(12, vm.uiState.value.claimNo)
        vm.closeClaim()
        assertNull(vm.uiState.value.claimNo)
        vm.onClaimCompleted()
        assertEquals(2, rewards.pageCalls.size)

        val signedOut = RewardsHistoryViewModel(key, rewards, TestSession.signedOut())
        signedOut.openClaim(12)
        assertNull(signedOut.uiState.value.claimNo)
        assertEquals("UNAUTHORIZED", signedOut.uiState.value.error?.code)
    }

    // --- Miner keys --------------------------------------------------------------------------

    private val keysResponse = MyKeysResponse(
        success = true,
        devices = listOf(
            MyKeyDevice(minerKey = key, nickname = "Backyard FEM", isRegistered = true),
            MyKeyDevice(
                minerKey = "IHAQM-CDEFGHIJKLMNOPQRSTUVWXYZ01234567",
                nickname = "Air",
                credentials = listOf(IotCredentials(portal = "purpleair", credentials = mapOf("api_key" to "SECRET"))),
            ),
        ),
        byodLicenses = listOf("BYOD-LICENSE-0042"),
    )

    @Test
    fun `keys are grouped by family in enum order with BYOD licenses`() = runTest {
        miners.keys = keysResponse
        val vm = MinerKeysViewModel(miners, TestSession.signedIn(), revealMillis = 15_000)
        val s = vm.uiState.value
        assertEquals(Phase.Content, s.phase)
        assertEquals(listOf(MinerFamily.FEM, MinerFamily.IHAQM), s.groups.map { it.family })
        assertEquals(listOf("BYOD-LICENSE-0042"), s.byodLicenses)
    }

    @Test
    fun `revealed credentials auto-mask after the reveal window and toggle off early on a second tap`() = runTest {
        miners.keys = keysResponse
        val aqm = "IHAQM-CDEFGHIJKLMNOPQRSTUVWXYZ01234567"
        val vm = MinerKeysViewModel(miners, TestSession.signedIn(), revealMillis = 15_000)
        assertFalse(vm.uiState.value.isRevealed(aqm, "purpleair"))

        vm.toggleReveal(aqm, "purpleair")
        assertTrue(vm.uiState.value.isRevealed(aqm, "purpleair"))
        advanceTimeBy(14_000)
        assertTrue(vm.uiState.value.isRevealed(aqm, "purpleair"))
        advanceTimeBy(1_500)
        assertFalse(vm.uiState.value.isRevealed(aqm, "purpleair"))

        vm.toggleReveal(aqm, "purpleair")
        vm.toggleReveal(aqm, "purpleair")
        assertFalse(vm.uiState.value.isRevealed(aqm, "purpleair"))
    }

    @Test
    fun `keys screen is Empty without devices and Error on failure`() = runTest {
        val empty = MinerKeysViewModel(miners, TestSession.signedIn(), revealMillis = 1)
        assertEquals(Phase.Empty, empty.uiState.value.phase)
        miners.myKeysError = DashboardException("UNAUTHORIZED", "401", httpStatus = 401)
        val failed = MinerKeysViewModel(miners, TestSession.signedIn(), revealMillis = 1)
        assertEquals(Phase.Error, failed.uiState.value.phase)
        assertEquals("UNAUTHORIZED", failed.uiState.value.error?.code)
    }

    // --- Activity ----------------------------------------------------------------------------

    @Test
    fun `activity keeps at most twelve events and reports Empty when there are none`() = runTest {
        rewards.activityEvents = (1..20).map { ActivityEvent(type = "reward_unlocked", minerKey = key, at = "2026-09-11T00:00:00.000Z") }
        val vm = ActivityViewModel(rewards, TestSession.signedIn(), fixedClock(0))
        assertEquals(ActivityViewModel.MAX_EVENTS, vm.uiState.value.events.size)
        assertEquals(Phase.Content, vm.uiState.value.phase)

        rewards.activityEvents = emptyList()
        val empty = ActivityViewModel(rewards, TestSession.signedIn(), fixedClock(0))
        assertEquals(Phase.Empty, empty.uiState.value.phase)
    }

    @Test
    fun `activity 503 maps to ACTIVITY_UNAVAILABLE copy`() = runTest {
        rewards.activityError = DashboardException("ACTIVITY_UNAVAILABLE", "503", httpStatus = 503)
        val vm = ActivityViewModel(rewards, TestSession.signedIn(), fixedClock(0))
        assertEquals(Phase.Error, vm.uiState.value.phase)
        assertEquals(ErrorCopy.forCode("ACTIVITY_UNAVAILABLE"), vm.uiState.value.error?.message)
    }
}
