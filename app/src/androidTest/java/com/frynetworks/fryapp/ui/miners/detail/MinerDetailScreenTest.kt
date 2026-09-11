package com.frynetworks.fryapp.ui.miners.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import com.frynetworks.fryapp.data.dashboard.model.DeviceStakeInfo
import com.frynetworks.fryapp.data.dashboard.model.RewardSummary
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.ui.common.Phase
import com.frynetworks.fryapp.ui.theme.FryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/** Renders [MinerDetailContent] and checks the 5.3 action gating through the contractual tags. */
@RunWith(AndroidJUnit4::class)
class MinerDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val femKey = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val rdnKey = "RDN-BCDEFGHIJKLMNOPQRSTUVWXYZ0123456"

    private fun state(minerKey: String, actions: MinerActionGating, detail: DeviceDetail = DeviceDetail(minerKey = minerKey, nickname = "Backyard")) =
        MinerDetailUiState(
            minerKey = minerKey,
            family = MinerFamily.fromMinerKey(minerKey),
            phase = Phase.Content,
            signedIn = true,
            detail = detail,
            summary = RewardSummary(claimable = BigDecimal("4.25")),
            actions = actions,
        )

    private fun content(state: MinerDetailUiState, onClaim: () -> Unit = {}, onStake: (StakeContext) -> Unit = {}, onOpenRewards: () -> Unit = {}) {
        compose.setContent {
            FryTheme {
                MinerDetailContent(
                    state = state,
                    now = { 0L },
                    onRefresh = {},
                    onRetry = {},
                    onOpenRewards = onOpenRewards,
                    onClaim = onClaim,
                    onStake = onStake,
                    onWithdraw = {},
                    onRename = {},
                    onRewardWallet = {},
                )
            }
        }
    }

    @Test
    fun claimIsDisabledWhenGatingSaysSoAndEnabledOtherwise() {
        content(state(femKey, MinerActionGating(rename = true, rewardWallet = true)))
        compose.onNodeWithTag("miner_action_claim").assertIsNotEnabled()
        compose.onNodeWithTag("miner_action_stake_registration").assertIsNotEnabled()
        compose.onNodeWithTag("miner_action_withdraw").assertIsNotEnabled()
        compose.onNodeWithTag("miner_action_rename").assertIsEnabled()
        compose.onNodeWithTag("miner_action_reward_wallet").assertIsEnabled()
    }

    @Test
    fun enabledClaimAndRegistrationInvokeTheirCallbacks() {
        var claims = 0
        var staked: StakeContext? = null
        content(
            state(femKey, MinerActionGating(claim = true, stakeRegistration = true, rename = true, rewardWallet = true)),
            onClaim = { claims++ },
            onStake = { staked = it },
        )
        compose.onNodeWithTag("miner_action_claim").assertIsEnabled().performClick()
        assertEquals(1, claims)
        compose.onNodeWithTag("miner_action_stake_registration").performClick()
        assertEquals(StakeContext.Registration, staked)
    }

    @Test
    fun stakeNodeIsHiddenForNonNodeFamiliesAndShownForNodeFamilies() {
        content(state(femKey, MinerActionGating(rename = true, rewardWallet = true)))
        compose.onNodeWithTag("miner_action_stake_node").assertDoesNotExist()
    }

    @Test
    fun nodeFamilyShowsStakeNodeAndWithdrawMenuGatedByWithdrawable() {
        content(
            state(
                rdnKey,
                MinerActionGating(showStakeNode = true, stakeNode = true, showWithdraw = true, withdrawRegistration = true, rename = true, rewardWallet = true),
                detail = DeviceDetail(minerKey = rdnKey, registration = DeviceStakeInfo(amount = BigDecimal("40"))),
            ),
        )
        compose.onNodeWithTag("miner_action_stake_node").assertIsEnabled()
        compose.onNodeWithTag("miner_action_withdraw").assertIsEnabled().performClick()
        compose.onNodeWithTag("miner_withdraw_registration").assertIsDisplayed()
    }

    @Test
    fun identityAndRewardsCardsExposeKeyStatusAndHistoryLink() {
        var history = 0
        content(state(femKey, MinerActionGating(rename = true, rewardWallet = true)), onOpenRewards = { history++ })
        compose.onNodeWithTag("miner_key", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("miner_copy_key").assertIsDisplayed()
        compose.onNodeWithTag("miner_reward_claimable", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("miner_open_rewards").performClick()
        assertEquals(1, history)
        compose.onNodeWithTag("miner_hardware").assertExists()
    }
}
