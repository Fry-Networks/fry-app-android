package com.frynetworks.fryapp.ui.miners

import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.data.dashboard.model.AssetTotalsResponse
import com.frynetworks.fryapp.data.dashboard.model.MinerSummary
import com.frynetworks.fryapp.data.dashboard.model.RewardSummary
import com.frynetworks.fryapp.data.dashboard.repo.FleetSummary
import com.frynetworks.fryapp.data.dashboard.repo.MinerListItem
import com.frynetworks.fryapp.data.dashboard.repo.MinerSource
import com.frynetworks.fryapp.data.dashboard.repo.MinerStatus
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.fakes.FakeMinerRepository
import com.frynetworks.fryapp.fakes.FakeRewardsRepository
import com.frynetworks.fryapp.fakes.TestSession
import com.frynetworks.fryapp.fakes.fixedClock
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.network.dashboard.ServerClock
import com.frynetworks.fryapp.network.dashboard.SessionEvent
import com.frynetworks.fryapp.network.dashboard.SessionEventBus
import com.frynetworks.fryapp.ui.common.ErrorCopy
import com.frynetworks.fryapp.ui.common.Phase
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class MinersViewModelTest {

    private val now = 1_757_548_800_000L
    private val miners = FakeMinerRepository()
    private val rewards = FakeRewardsRepository()
    private val bus = SessionEventBus()
    private val clock: ServerClock = fixedClock(now)

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun item(
        key: String,
        status: MinerStatus = MinerStatus.ACTIVE,
        nickname: String? = null,
        claimable: String = "0",
        productName: String? = null,
        source: MinerSource = MinerSource.REMOTE,
    ): MinerListItem {
        val family = MinerFamily.fromMinerKey(key)
        return MinerListItem(
            minerKey = key,
            family = family,
            displayName = nickname ?: MinerListItem.shortKey(key),
            status = status,
            source = source,
            localOnline = null,
            remote = MinerSummary(minerKey = key, nickname = nickname, productName = productName, status = status.wireValue),
            local = null,
            rewards = RewardSummary(claimable = BigDecimal(claimable)),
        )
    }

    private val fem = item("FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345", MinerStatus.ACTIVE, "Backyard FEM", "4.25", "Fry Edge Miner")
    private val rdn = item("RDN-BCDEFGHIJKLMNOPQRSTUVWXYZ0123456", MinerStatus.PENDING, "Rack node", "0")
    private val aqm = item("IHAQM-CDEFGHIJKLMNOPQRSTUVWXYZ01234567", MinerStatus.UNREGISTERED, null, "9.99")

    private fun vm(session: SessionRepository = TestSession.signedIn()) = MinersViewModel(miners, rewards, session, bus, clock)

    @Test
    fun `signed in - refresh runs once at start and the list is Content`() = runTest {
        miners.onRefresh = { miners.miners.value = listOf(fem, rdn, aqm) }
        rewards.totals.value = AssetTotalsResponse(nextUnlockAt = "2026-09-12T00:05:00.000Z")
        miners.fleet.value = FleetSummary(online = 2, total = 3)
        val vm = vm()
        val s = vm.uiState.value
        assertEquals(1, miners.refreshCalls)
        assertEquals(Phase.Content, s.phase)
        assertEquals(3, s.items.size)
        assertEquals(3, s.totalCount)
        assertEquals(FleetSummary(2, 3), s.fleet)
        assertEquals("2026-09-12T00:05:00.000Z", s.totals?.nextUnlockAt)
        assertFalse(s.isRefreshing)
        assertNull(s.error)
        assertTrue(s.signedIn)
    }

    @Test
    fun `Loading while the first refresh is in flight then Content when it completes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        miners.refreshGate = gate
        miners.onRefresh = { miners.miners.value = listOf(fem) }
        val vm = vm()
        assertEquals(Phase.Loading, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.isRefreshing)
        gate.complete(Unit)
        assertEquals(Phase.Content, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun `Empty when the refresh succeeds with no miners`() = runTest {
        val vm = vm()
        assertEquals(Phase.Empty, vm.uiState.value.phase)
        assertEquals(0, vm.uiState.value.totalCount)
    }

    @Test
    fun `Error with user copy when the refresh fails and nothing is cached`() = runTest {
        miners.refreshError = DashboardException("NETWORK_ERROR", "socket closed")
        val vm = vm()
        val s = vm.uiState.value
        assertEquals(Phase.Error, s.phase)
        assertEquals("NETWORK_ERROR", s.error?.code)
        assertEquals(ErrorCopy.forCode("NETWORK_ERROR"), s.error?.message)
        assertFalse(s.isRefreshing)
    }

    @Test
    fun `a failed refresh keeps cached rows as Content with an inline error`() = runTest {
        miners.miners.value = listOf(fem)
        miners.refreshError = DashboardException("RATE_LIMIT_EXCEEDED", "slow down")
        val vm = vm()
        assertEquals(Phase.Content, vm.uiState.value.phase)
        assertEquals("RATE_LIMIT_EXCEEDED", vm.uiState.value.error?.code)
        vm.dismissError()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `signed out - no refresh and the state reports signedIn false`() = runTest {
        val vm = vm(TestSession.signedOut())
        assertEquals(0, miners.refreshCalls)
        assertFalse(vm.uiState.value.signedIn)
    }

    @Test
    fun `status filter is single-select and toggles off on a second tap`() = runTest {
        miners.onRefresh = { miners.miners.value = listOf(fem, rdn, aqm) }
        val vm = vm()
        vm.toggleStatusFilter(MinerStatus.PENDING)
        assertEquals(listOf(rdn.minerKey), vm.uiState.value.items.map { it.minerKey })
        assertEquals(MinerStatus.PENDING, vm.uiState.value.statusFilter)
        vm.toggleStatusFilter(MinerStatus.PENDING)
        assertNull(vm.uiState.value.statusFilter)
        assertEquals(3, vm.uiState.value.items.size)
    }

    @Test
    fun `family filter lists the families present and narrows the rows`() = runTest {
        miners.onRefresh = { miners.miners.value = listOf(fem, rdn, aqm) }
        val vm = vm()
        assertEquals(listOf(MinerFamily.FEM, MinerFamily.RDN, MinerFamily.IHAQM), vm.uiState.value.availableFamilies)
        vm.toggleFamilyFilter(MinerFamily.IHAQM)
        assertEquals(listOf(aqm.minerKey), vm.uiState.value.items.map { it.minerKey })
    }

    @Test
    fun `search matches key nickname and product name case-insensitively`() = runTest {
        miners.onRefresh = { miners.miners.value = listOf(fem, rdn, aqm) }
        val vm = vm()
        vm.setQuery("backyard")
        assertEquals(listOf(fem.minerKey), vm.uiState.value.items.map { it.minerKey })
        vm.setQuery("edge miner")
        assertEquals(listOf(fem.minerKey), vm.uiState.value.items.map { it.minerKey })
        vm.setQuery("ihaqm-cdef")
        assertEquals(listOf(aqm.minerKey), vm.uiState.value.items.map { it.minerKey })
        vm.setQuery("nothing matches")
        assertTrue(vm.uiState.value.items.isEmpty())
        assertEquals(Phase.Content, vm.uiState.value.phase)
        assertEquals(3, vm.uiState.value.totalCount)
    }

    @Test
    fun `default sort puts miners that need attention first, claimable sort puts the biggest first`() = runTest {
        miners.onRefresh = { miners.miners.value = listOf(fem, rdn, aqm) }
        val vm = vm()
        assertEquals(listOf(aqm.minerKey, rdn.minerKey, fem.minerKey), vm.uiState.value.items.map { it.minerKey })
        vm.setSort(MinerSort.CLAIMABLE)
        assertEquals(listOf(aqm.minerKey, fem.minerKey, rdn.minerKey), vm.uiState.value.items.map { it.minerKey })
        vm.setSort(MinerSort.NAME)
        assertEquals(listOf(fem.minerKey, aqm.minerKey, rdn.minerKey), vm.uiState.value.items.map { it.minerKey })
    }

    @Test
    fun `session expired event signs out locally, drops the caches and queues a snackbar`() = runTest {
        miners.onRefresh = { miners.miners.value = listOf(fem) }
        val session = TestSession.signedIn()
        val vm = vm(session)
        assertEquals(Phase.Content, vm.uiState.value.phase)

        bus.emit(SessionEvent.Expired)

        assertTrue(session.state.value is SessionState.SignedOut)
        assertFalse(vm.uiState.value.signedIn)
        assertEquals(ErrorCopy.forCode("UNAUTHORIZED"), vm.uiState.value.message)
        assertEquals(1, miners.clearCacheCalls)
        assertEquals(1, rewards.clearCacheCalls)
        vm.consumeMessage()
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun `device mismatch event uses the dashboard's own copy`() = runTest {
        val vm = vm()
        bus.emit(SessionEvent.DeviceMismatch)
        assertEquals(ErrorCopy.forCode("DEVICE_MISMATCH"), vm.uiState.value.message)
    }

    @Test
    fun `onResume only refreshes once the last refresh is older than five minutes`() = runTest {
        val vm = vm()
        assertEquals(1, miners.refreshCalls)
        vm.onResume()
        assertEquals(1, miners.refreshCalls)
        clock.observeServerMillis(now + MinersViewModel.STALE_AFTER_MILLIS + 1_000)
        vm.onResume()
        assertEquals(2, miners.refreshCalls)
    }

    @Test
    fun `signing in after start triggers the first refresh`() = runTest {
        val session = TestSession.signedOut()
        val vm = vm(session)
        assertEquals(0, miners.refreshCalls)
        session.markSignedIn(com.frynetworks.fryapp.auth.SessionProfile(com.frynetworks.fryapp.fakes.TEST_ADDRESS), fingerprintBound = true)
        assertEquals(1, miners.refreshCalls)
        assertTrue(vm.uiState.value.signedIn)
    }
}
