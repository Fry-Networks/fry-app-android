package com.frynetworks.fryapp.ui.miners.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.frynetworks.fryapp.data.dashboard.model.DeviceStakeInfo
import com.frynetworks.fryapp.data.dashboard.model.HardwareStatusEntry
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.ui.common.CopyableText
import com.frynetworks.fryapp.ui.common.CountdownText
import com.frynetworks.fryapp.ui.common.FamilyChip
import com.frynetworks.fryapp.ui.common.MinerStatusChip
import com.frynetworks.fryapp.ui.common.TimeFormat
import com.frynetworks.fryapp.ui.common.shortAddress
import com.frynetworks.fryapp.ui.miners.formatAmount
import com.frynetworks.fryapp.ui.theme.FryCard
import java.math.BigDecimal

@Composable
private fun SectionCard(title: String, tag: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier.fillMaxWidth().testTag(tag), colors = CardDefaults.cardColors(containerColor = FryCard)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String, tag: String? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}

private fun flag(value: Boolean?): String = when (value) {
    true -> "Yes"
    false -> "No"
    null -> "—"
}

@Composable
fun IdentityCard(state: MinerDetailUiState, modifier: Modifier = Modifier) {
    val detail = state.detail
    SectionCard(title = "Identity", tag = "miner_identity", modifier = modifier) {
        Text(state.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("miner_nickname"))
        CopyableText(text = state.minerKey, label = "Miner key", textTag = "miner_key", copyTag = "miner_copy_key")
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FamilyChip(family = state.family, modifier = Modifier.testTag("miner_family"))
            state.listItem?.let { MinerStatusChip(status = it.status, modifier = Modifier.testTag("miner_status")) }
        }
        Spacer(Modifier.height(4.dp))
        LabelValue("Product", state.product?.displayName ?: state.product?.name ?: state.listItem?.remote?.productName ?: state.family.label, "miner_product")
        LabelValue("Registered", flag(detail?.isRegistered ?: state.listItem?.remote?.isRegistered), "miner_registered")
        LabelValue("Verified", flag(detail?.verified ?: state.listItem?.remote?.verified), "miner_verified")
        LabelValue("Active", flag(detail?.isActive), "miner_is_active")
        LabelValue("Reward eligible", flag(detail?.rewardEligible), "miner_reward_eligible")
        detail?.rewardBlockReason?.takeIf { it.isNotBlank() }?.let {
            Text(
                "Rewards blocked: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("miner_reward_block_reason"),
            )
        }
        if (state.isVirtual) LabelValue("Type", "Virtual miner", "miner_virtual")
        if (state.isByod) LabelValue("BYOD", "Yes", "miner_byod")
        detail?.rewardWallet?.takeIf { it.isNotBlank() }?.let { LabelValue("Reward wallet", shortAddress(it), "miner_reward_wallet") }
    }
}

@Composable
fun RewardsCard(state: MinerDetailUiState, now: () -> Long, onOpenHistory: () -> Unit, modifier: Modifier = Modifier) {
    val summary = state.summary
    val symbol = state.family.rewardAsset.symbol
    SectionCard(title = "Rewards ($symbol)", tag = "miner_rewards", modifier = modifier) {
        LabelValue("Claimable", formatAmount(summary?.claimable), "miner_reward_claimable")
        LabelValue("Pending", formatAmount(summary?.pending), "miner_reward_pending")
        LabelValue("Accruing", formatAmount(summary?.accruing), "miner_reward_accruing")
        LabelValue("Claimed", formatAmount(summary?.claimed), "miner_reward_claimed")
        CountdownText(
            targetMillis = TimeFormat.parseMillis(summary?.nextUnlockAt),
            now = now,
            prefix = "Next unlock in",
            tag = "miner_next_unlock",
        )
        summary?.firstRewardAt?.let { LabelValue("First reward", TimeFormat.dateOr(it)) }
        TextButton(
            onClick = onOpenHistory,
            modifier = Modifier.testTag("miner_open_rewards").semantics { contentDescription = "Reward history" },
        ) { Text("History") }
    }
}

@Composable
private fun StakeLine(kind: String, label: String, info: DeviceStakeInfo?, lockTarget: Long?, now: () -> Long) {
    if (info?.amount == null) {
        LabelValue(label, "Not staked", "miner_stake_${kind}_amount")
        return
    }
    val asset = info.assetId?.toLongOrNull()?.let { FryAsset.fromId(it) }
    LabelValue(label, "${formatAmount(info.amount)} ${asset?.symbol ?: ""}".trim(), "miner_stake_${kind}_amount")
    info.time?.let { LabelValue("  Staked", TimeFormat.dateOr(it), "miner_stake_${kind}_time") }
    info.txId?.let { LabelValue("  Transaction", "${it.take(10)}…", "miner_stake_${kind}_txid") }
    info.type?.let { LabelValue("  Tier", it, "miner_stake_${kind}_tier") }
    CountdownText(targetMillis = lockTarget, now = now, prefix = "  Unlocks in", tag = "miner_stake_${kind}_lock")
}

@Composable
fun StakeCard(state: MinerDetailUiState, now: () -> Long, modifier: Modifier = Modifier) {
    val detail = state.detail
    SectionCard(title = "Stake", tag = "miner_stake", modifier = modifier) {
        StakeLine("registration", "Registration", detail?.registration, state.lockTargets["registration"], now)
        Spacer(Modifier.height(4.dp))
        StakeLine("node", "Node", detail?.node, state.lockTargets["node"], now)
        Spacer(Modifier.height(4.dp))
        StakeLine("verification", "Verification", detail?.staked, state.lockTargets["verification"], now)
        state.product?.reward?.stake?.let { stake ->
            Spacer(Modifier.height(4.dp))
            Text("Product tiers (USD)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            stake.register?.let { LabelValue("Registration", usd(it)) }
            stake.node?.let { LabelValue("Node", usd(it)) }
            stake.stakeOne?.let { LabelValue("Verification one (1 day lock)", usd(it)) }
            stake.stakeTwo?.let { LabelValue("Verification two (180 day lock)", usd(it)) }
        }
    }
}

private fun usd(value: BigDecimal): String = "$" + value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

@Composable
fun HardwareCard(hardware: HardwareStatusEntry?, modifier: Modifier = Modifier) {
    SectionCard(title = "Hardware", tag = "miner_hardware", modifier = modifier) {
        if (hardware == null) {
            Text("Hardware status unavailable.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("miner_hw_status"))
            return@SectionCard
        }
        Column(modifier = Modifier.testTag("miner_hw_status")) {
            LabelValue("Linked", flag(hardware.linked), "miner_hw_linked")
            LabelValue("Valid", flag(hardware.valid), "miner_hw_valid")
            LabelValue("MAC match", flag(hardware.macMatch), "miner_hw_mac_match")
            hardware.reason?.takeIf { it.isNotBlank() }?.let { LabelValue("Reason", it, "miner_hw_reason") }
        }
    }
}
