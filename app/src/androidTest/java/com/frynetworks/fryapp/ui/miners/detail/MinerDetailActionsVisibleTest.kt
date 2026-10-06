package com.frynetworks.fryapp.ui.miners.detail

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import android.view.View
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import com.frynetworks.fryapp.data.dashboard.model.RewardSummary
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.ui.common.Phase
import com.frynetworks.fryapp.ui.theme.FryTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal

/**
 * IV6 A2 (S1): on the device detail screen every action button (Claim, Register, Verify,
 * Withdraw, Rename, Reward wallet) must lie fully inside the visible window once scrolled to,
 * at font scale 1.0 and 1.3, for an ESP8266 that is registered, verified, active and blocked.
 * Run on a 1080x2340, 450 dpi device with gesture navigation (AVD iv6-s22).
 */
@RunWith(AndroidJUnit4::class)
class MinerDetailActionsVisibleTest {

    @get:Rule
    val compose = createComposeRule()

    private val key = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val tags = listOf(
        "miner_action_claim", "miner_action_stake_registration", "miner_action_stake_verification",
        "miner_action_withdraw", "miner_action_rename", "miner_action_reward_wallet",
    )

    private val state = MinerDetailUiState(
        minerKey = key,
        family = MinerFamily.fromMinerKey(key),
        phase = Phase.Content,
        signedIn = true,
        detail = DeviceDetail(
            minerKey = key, nickname = "Esp8266", isRegistered = true, verified = true, isActive = true, rewardEligible = false,
            rewardBlockReason = "lease_held_elsewhere",
        ),
        summary = RewardSummary(claimable = BigDecimal("4.25")),
        actions = MinerActionGating(
            claim = true, stakeRegistration = true, stakeVerification = true, showWithdraw = true, withdrawRegistration = true,
            rename = true, rewardWallet = true,
        ),
    )

    private fun check(fontScale: Float) {
        var host: View? = null
        compose.setContent {
            host = LocalView.current
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                FryTheme {
                    MinerDetailContent(
                        state = state, now = { 0L }, onRefresh = {}, onRetry = {}, onOpenRewards = {}, onClaim = {},
                        onStake = {}, onWithdraw = {}, onRename = {}, onRewardWallet = {},
                    )
                }
            }
        }
        val root = compose.onNodeWithTag("miner_root").fetchSemanticsNode().boundsInRoot
        // The real visible window area: the decor view minus the system bars, the gesture-nav
        // strip and any cutout (a button under the gesture bar is clipped even inside miner_root).
        val decor = host!!.rootView
        val rootInsets = ViewCompat.getRootWindowInsets(decor)!!
        val bars = rootInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        // Only the bottom home-gesture strip counts from systemGestures: the left and right
        // back-gesture zones overlap ordinary content margins and clip nothing.
        val gestureBottom = rootInsets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
        val insets = androidx.core.graphics.Insets.of(bars.left, bars.top, bars.right, maxOf(bars.bottom, gestureBottom))
        val visLeft = insets.left.toFloat()
        val visTop = insets.top.toFloat()
        val visRight = (decor.width - insets.right).toFloat()
        val visBottom = (decor.height - insets.bottom).toFloat()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        for (tag in tags) {
            compose.onNodeWithTag("miner_content").performScrollToNode(hasTestTag(tag))
            compose.waitForIdle()
            val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag at $fontScale left ${b.left} < ${root.left}", b.left >= root.left)
            assertTrue("$tag at $fontScale right ${b.right} > ${root.right}", b.right <= root.right + 0.5f)
            assertTrue("$tag at $fontScale top ${b.top} < ${root.top}", b.top >= root.top)
            assertTrue("$tag at $fontScale bottom ${b.bottom} > ${root.bottom}", b.bottom <= root.bottom + 0.5f)
            val w = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
            assertTrue("$tag at $fontScale window left ${w.left} < $visLeft", w.left >= visLeft)
            assertTrue("$tag at $fontScale window right ${w.right} > $visRight", w.right <= visRight + 0.5f)
            assertTrue("$tag at $fontScale window top ${w.top} < $visTop (status bar)", w.top >= visTop)
            assertTrue("$tag at $fontScale window bottom ${w.bottom} > $visBottom (nav/gesture bar)", w.bottom <= visBottom + 0.5f)
            assertTrue("$tag at $fontScale is not 48 dp tall: ${b.height}", b.height >= 48f * ctx.resources.displayMetrics.density - 1f)
        }
        // Evidence screenshot of the scrolled-to-bottom state, kept in the app's cache dir.
        val shot = compose.onNodeWithTag("miner_root").captureToImage().asAndroidBitmap()
        File(ctx.cacheDir, "detail-actions-${fontScale}.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun everyActionButtonIsInsideTheWindowAtFontScale1_0() = check(1.0f)

    @Test fun everyActionButtonIsInsideTheWindowAtFontScale1_3() = check(1.3f)
}
