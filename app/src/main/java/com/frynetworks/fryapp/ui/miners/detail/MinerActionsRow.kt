package com.frynetworks.fryapp.ui.miners.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.domain.StakeTier

/**
 * Blueprint 5.3 actions row; every button is gated by [MinerActionGating]. Laid out two per row so
 * no label is ever squeezed into a third of the width (IMG5: "Stake node", "Withdraw" clipped),
 * with labels allowed two lines and buttons at least [ACTION_MIN_HEIGHT] tall at any font scale.
 */
@Composable
fun MinerActionsRow(
    actions: MinerActionGating,
    verificationTier: StakeTier?,
    onClaim: () -> Unit,
    onStake: (StakeContext) -> Unit,
    onWithdraw: (StakeContext) -> Unit,
    onRename: () -> Unit,
    onRewardWallet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cells = buildList<@Composable (Modifier) -> Unit> {
        add { m ->
            Button(onClick = onClaim, enabled = actions.claim, contentPadding = ACTION_PADDING,
                modifier = m.testTag("miner_action_claim").semantics { contentDescription = "Claim rewards" }) { ActionLabel("Claim") }
        }
        add { m ->
            OutlinedButton(onClick = { onStake(StakeContext.Registration) }, enabled = actions.stakeRegistration, contentPadding = ACTION_PADDING,
                modifier = m.testTag("miner_action_stake_registration").semantics { contentDescription = "Stake registration" }) { ActionLabel("Register") }
        }
        if (actions.showStakeNode) {
            add { m ->
                OutlinedButton(onClick = { onStake(StakeContext.Node) }, enabled = actions.stakeNode, contentPadding = ACTION_PADDING,
                    modifier = m.testTag("miner_action_stake_node").semantics { contentDescription = "Stake node" }) { ActionLabel("Stake node") }
            }
        }
        add { m ->
            OutlinedButton(onClick = { onStake(StakeContext.Verification(StakeTier.ONE)) }, enabled = actions.stakeVerification, contentPadding = ACTION_PADDING,
                modifier = m.testTag("miner_action_stake_verification").semantics { contentDescription = "Verification stake" }) { ActionLabel("Verify") }
        }
        add { m -> WithdrawMenu(actions = actions, verificationTier = verificationTier, onWithdraw = onWithdraw, modifier = m) }
        add { m ->
            OutlinedButton(onClick = onRename, enabled = actions.rename, contentPadding = ACTION_PADDING,
                modifier = m.testTag("miner_action_rename").semantics { contentDescription = "Rename miner" }) { ActionLabel("Rename") }
        }
        add { m ->
            OutlinedButton(onClick = onRewardWallet, enabled = actions.rewardWallet, contentPadding = ACTION_PADDING,
                modifier = m.testTag("miner_action_reward_wallet").semantics { contentDescription = "Set reward wallet" }) { ActionLabel("Reward wallet") }
        }
    }
    Column(modifier = modifier.fillMaxWidth().testTag("miner_actions"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (pair in cells.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                for (cell in pair) cell(Modifier.weight(1f).fillMaxHeight().heightIn(min = ACTION_MIN_HEIGHT))
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

internal val ACTION_MIN_HEIGHT = 48.dp
private val ACTION_PADDING = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

@Composable
private fun ActionLabel(text: String) {
    Text(text, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Visible)
}

@Composable
private fun WithdrawMenu(
    actions: MinerActionGating,
    verificationTier: StakeTier?,
    onWithdraw: (StakeContext) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { open = true },
            enabled = actions.showWithdraw && actions.anyWithdraw,
            contentPadding = ACTION_PADDING,
            modifier = Modifier.fillMaxWidth().fillMaxHeight().heightIn(min = ACTION_MIN_HEIGHT).testTag("miner_action_withdraw").semantics { contentDescription = "Withdraw stake" },
        ) { ActionLabel("Withdraw") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Registration stake") },
                enabled = actions.withdrawRegistration,
                onClick = { open = false; onWithdraw(StakeContext.Registration) },
                modifier = Modifier.testTag("miner_withdraw_registration"),
            )
            DropdownMenuItem(
                text = { Text("Node stake") },
                enabled = actions.withdrawNode,
                onClick = { open = false; onWithdraw(StakeContext.Node) },
                modifier = Modifier.testTag("miner_withdraw_node"),
            )
            DropdownMenuItem(
                text = { Text("Verification stake") },
                enabled = actions.withdrawVerification,
                onClick = { open = false; onWithdraw(StakeContext.Verification(verificationTier ?: StakeTier.ONE)) },
                modifier = Modifier.testTag("miner_withdraw_verification"),
            )
        }
    }
}
