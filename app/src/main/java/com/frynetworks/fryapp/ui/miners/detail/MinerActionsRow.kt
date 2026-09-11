package com.frynetworks.fryapp.ui.miners.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.domain.StakeTier

/** Blueprint 5.3 actions row; every button is gated by [MinerActionGating]. */
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
    Column(modifier = modifier.fillMaxWidth().testTag("miner_actions"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onClaim,
                enabled = actions.claim,
                modifier = Modifier.weight(1f).testTag("miner_action_claim").semantics { contentDescription = "Claim rewards" },
            ) { Text("Claim") }
            OutlinedButton(
                onClick = { onStake(StakeContext.Registration) },
                enabled = actions.stakeRegistration,
                modifier = Modifier.weight(1f).testTag("miner_action_stake_registration").semantics { contentDescription = "Stake registration" },
            ) { Text("Register") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (actions.showStakeNode) {
                OutlinedButton(
                    onClick = { onStake(StakeContext.Node) },
                    enabled = actions.stakeNode,
                    modifier = Modifier.weight(1f).testTag("miner_action_stake_node").semantics { contentDescription = "Stake node" },
                ) { Text("Stake node") }
            }
            OutlinedButton(
                onClick = { onStake(StakeContext.Verification(StakeTier.ONE)) },
                enabled = actions.stakeVerification,
                modifier = Modifier.weight(1f).testTag("miner_action_stake_verification").semantics { contentDescription = "Verification stake" },
            ) { Text("Verify") }
            WithdrawMenu(actions = actions, verificationTier = verificationTier, onWithdraw = onWithdraw, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onRename,
                enabled = actions.rename,
                modifier = Modifier.weight(1f).testTag("miner_action_rename").semantics { contentDescription = "Rename miner" },
            ) { Text("Rename") }
            OutlinedButton(
                onClick = onRewardWallet,
                enabled = actions.rewardWallet,
                modifier = Modifier.weight(1f).testTag("miner_action_reward_wallet").semantics { contentDescription = "Set reward wallet" },
            ) { Text("Reward wallet") }
        }
    }
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
            modifier = Modifier.fillMaxWidth().testTag("miner_action_withdraw").semantics { contentDescription = "Withdraw stake" },
        ) { Text("Withdraw") }
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
