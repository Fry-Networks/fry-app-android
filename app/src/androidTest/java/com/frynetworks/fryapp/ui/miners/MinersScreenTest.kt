package com.frynetworks.fryapp.ui.miners

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.frynetworks.fryapp.data.dashboard.model.AssetTotalsBuckets
import com.frynetworks.fryapp.data.dashboard.model.AssetTotalsResponse
import com.frynetworks.fryapp.data.dashboard.model.MinerSummary
import com.frynetworks.fryapp.data.dashboard.model.RewardBucket
import com.frynetworks.fryapp.data.dashboard.model.RewardSummary
import com.frynetworks.fryapp.data.dashboard.repo.FleetSummary
import com.frynetworks.fryapp.data.dashboard.repo.MinerListItem
import com.frynetworks.fryapp.data.dashboard.repo.MinerSource
import com.frynetworks.fryapp.data.dashboard.repo.MinerStatus
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.ui.common.Phase
import com.frynetworks.fryapp.ui.common.UiError
import com.frynetworks.fryapp.ui.theme.FryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/** Renders the stateless [MinersContent] in each phase and checks the contractual test tags. */
@RunWith(AndroidJUnit4::class)
class MinersScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val femKey = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val rdnKey = "RDN-BCDEFGHIJKLMNOPQRSTUVWXYZ0123456"

    private fun item(key: String, status: MinerStatus, nickname: String, claimable: String) = MinerListItem(
        minerKey = key,
        family = MinerFamily.fromMinerKey(key),
        displayName = nickname,
        status = status,
        source = MinerSource.REMOTE,
        localOnline = null,
        remote = MinerSummary(minerKey = key, nickname = nickname, status = status.wireValue),
        local = null,
        rewards = RewardSummary(claimable = BigDecimal(claimable)),
    )

    private fun content(state: MinersUiState, onRetry: () -> Unit = {}, onQuery: (String) -> Unit = {}, onToggleStatus: (MinerStatus) -> Unit = {}, onOpenMiner: (String) -> Unit = {}) {
        compose.setContent {
            FryTheme {
                MinersContent(
                    state = state,
                    now = { 0L },
                    onRefresh = {},
                    onRetry = onRetry,
                    onQuery = onQuery,
                    onToggleStatus = onToggleStatus,
                    onToggleFamily = {},
                    onSort = {},
                    onOpenMiner = onOpenMiner,
                    onAddDevice = {},
                )
            }
        }
    }

    @Test
    fun loadingPhaseShowsTheSpinner() {
        content(MinersUiState(signedIn = true, phase = Phase.Loading))
        compose.onNodeWithTag("miners_loading").assertIsDisplayed()
    }

    @Test
    fun emptyPhaseShowsTheEmptyState() {
        content(MinersUiState(signedIn = true, phase = Phase.Empty))
        compose.onNodeWithTag("miners_empty").assertIsDisplayed()
        compose.onNodeWithTag("miners_empty_add_device").assertIsDisplayed()
    }

    @Test
    fun errorPhaseShowsCopyAndRetryInvokesTheCallback() {
        var retries = 0
        content(MinersUiState(signedIn = true, phase = Phase.Error, error = UiError("NETWORK_ERROR", "Could not reach the Fry Dashboard.")), onRetry = { retries++ })
        compose.onNodeWithTag("miners_error").assertIsDisplayed()
        compose.onNodeWithTag("miners_error_retry").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun contentPhaseRendersTotalsFleetRowsAndStatusChips() {
        val items = listOf(item(femKey, MinerStatus.ACTIVE, "Backyard FEM", "4.25"), item(rdnKey, MinerStatus.PENDING, "Rack node", "0"))
        var opened: String? = null
        content(
            MinersUiState(
                signedIn = true,
                phase = Phase.Content,
                items = items,
                totalCount = 2,
                totals = AssetTotalsResponse(totals = AssetTotalsBuckets(fnode = RewardBucket(claimable = BigDecimal("4.25")), tfry = RewardBucket(claimable = BigDecimal("9.99")))),
                fleet = FleetSummary(online = 1, total = 2),
                availableFamilies = listOf(MinerFamily.FEM, MinerFamily.RDN),
            ),
            onOpenMiner = { opened = it },
        )
        compose.onNodeWithTag("miners_totals_fnode").assertIsDisplayed()
        compose.onNodeWithTag("miners_totals_tfry").assertIsDisplayed()
        compose.onNodeWithTag("miners_online").assertIsDisplayed()
        compose.onNodeWithTag("miners_item_$femKey").assertIsDisplayed()
        compose.onNodeWithTag("miners_status_$femKey", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("miners_item_$femKey").performClick()
        assertEquals(femKey, opened)
    }

    @Test
    fun filterChipAndSearchFieldInvokeTheirCallbacks() {
        var toggled: MinerStatus? = null
        var query = ""
        content(
            MinersUiState(signedIn = true, phase = Phase.Content, items = listOf(item(femKey, MinerStatus.ACTIVE, "Backyard FEM", "0")), totalCount = 1),
            onQuery = { query = it },
            onToggleStatus = { toggled = it },
        )
        compose.onNodeWithTag("miners_filter_pending").performClick()
        assertEquals(MinerStatus.PENDING, toggled)
        compose.onNodeWithTag("miners_search").performTextInput("back")
        assertEquals("back", query)
        compose.onNodeWithTag("miners_sort").assertIsDisplayed()
    }

    @Test
    fun contentWithNoMatchesShowsTheNoMatchLine() {
        content(MinersUiState(signedIn = true, phase = Phase.Content, items = emptyList(), totalCount = 3, query = "zzz"))
        compose.onNodeWithTag("miners_no_match").assertIsDisplayed()
    }
}
