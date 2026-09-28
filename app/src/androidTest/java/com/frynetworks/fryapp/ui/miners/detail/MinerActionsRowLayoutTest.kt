package com.frynetworks.fryapp.ui.miners.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * IMG5: action labels were clipped. Every label must fit in at most two lines without visual
 * overflow and every button must be at least 48 dp tall, at font scale 1.0, 1.3 and 2.0, on a
 * 360 dp wide screen (the narrowest common phone width).
 */
class MinerActionsRowLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val labels = mapOf(
        "miner_action_claim" to "Claim",
        "miner_action_stake_registration" to "Register",
        "miner_action_stake_node" to "Stake node",
        "miner_action_stake_verification" to "Verify",
        "miner_action_withdraw" to "Withdraw",
        "miner_action_rename" to "Rename",
        "miner_action_reward_wallet" to "Reward wallet",
    )

    private val everything = MinerActionGating(
        claim = true, stakeRegistration = true, showStakeNode = true, stakeNode = true, stakeVerification = true,
        showWithdraw = true, withdrawRegistration = true, rename = true, rewardWallet = true,
    )

    private fun assertLabelsFit(fontScale: Float) {
        var minHeightPx = 0
        compose.setContent {
            val base = LocalDensity.current
            val density = Density(base.density, fontScale)
            minHeightPx = with(density) { 48.dp.roundToPx() }
            CompositionLocalProvider(LocalDensity provides density) {
                Box(Modifier.width(360.dp)) {
                    MinerActionsRow(everything, verificationTier = null, onClaim = {}, onStake = {}, onWithdraw = {}, onRename = {}, onRewardWallet = {})
                }
            }
        }
        for ((tag, label) in labels) {
            val button = compose.onNodeWithTag(tag).fetchSemanticsNode()
            assertTrue("$tag at $fontScale: ${button.size.height}px < 48dp", button.size.height >= minHeightPx)
            val text = compose.onNode(hasAnyAncestor(hasTestTag(tag)) and hasText(label), useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            assertFalse("$tag at $fontScale overflows", layout.hasVisualOverflow)
            assertTrue("$tag at $fontScale needs ${layout.lineCount} lines", layout.lineCount <= 2)
            assertTrue("$tag text taller than its button", layout.size.height <= button.size.height)
        }
    }

    @Test fun labelsFitAtFontScale1_0() = assertLabelsFit(1.0f)

    @Test fun labelsFitAtFontScale1_3() = assertLabelsFit(1.3f)

    @Test fun labelsFitAtFontScale2_0() = assertLabelsFit(2.0f)
}
