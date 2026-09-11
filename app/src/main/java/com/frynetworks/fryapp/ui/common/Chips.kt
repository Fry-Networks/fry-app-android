package com.frynetworks.fryapp.ui.common

import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.frynetworks.fryapp.data.dashboard.model.RewardStatus
import com.frynetworks.fryapp.data.dashboard.repo.MinerStatus
import com.frynetworks.fryapp.domain.MinerFamily

fun MinerStatus.label(): String = when (this) {
    MinerStatus.ACTIVE -> "Active"
    MinerStatus.PENDING -> "Pending"
    MinerStatus.UNREGISTERED -> "Unregistered"
    MinerStatus.MIGRATED -> "Migrated"
    MinerStatus.NOT_ON_DASHBOARD -> "Not on dashboard"
    MinerStatus.UNKNOWN -> "Unknown"
}

/** Stable lowercase token used in `miners_filter_<status>` test tags. */
fun MinerStatus.tagToken(): String = name.lowercase()

fun RewardStatus.label(): String = when (this) {
    RewardStatus.CLAIMABLE -> "Claimable"
    RewardStatus.AGGREGATED -> "Aggregated"
    RewardStatus.PENDING -> "Pending"
    RewardStatus.ON_HOLD -> "On hold"
    RewardStatus.CLAIMED -> "Claimed"
    RewardStatus.UNKNOWN -> "Unknown"
}

@Composable
private fun statusColor(status: MinerStatus): Color = when (status) {
    MinerStatus.ACTIVE -> MaterialTheme.colorScheme.secondary
    MinerStatus.PENDING, MinerStatus.UNREGISTERED, MinerStatus.NOT_ON_DASHBOARD -> MaterialTheme.colorScheme.primary
    MinerStatus.MIGRATED, MinerStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun rewardColor(status: RewardStatus): Color = when (status) {
    RewardStatus.CLAIMABLE -> MaterialTheme.colorScheme.secondary
    RewardStatus.CLAIMED -> MaterialTheme.colorScheme.onSurfaceVariant
    RewardStatus.ON_HOLD -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
fun MinerStatusChip(status: MinerStatus, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = {},
        label = { Text(status.label()) },
        colors = AssistChipDefaults.assistChipColors(labelColor = statusColor(status)),
        modifier = modifier,
    )
}

@Composable
fun RewardStatusChip(status: RewardStatus, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = {},
        label = { Text(status.label()) },
        colors = AssistChipDefaults.assistChipColors(labelColor = rewardColor(status)),
        modifier = modifier,
    )
}

@Composable
fun FamilyChip(family: MinerFamily, modifier: Modifier = Modifier) {
    AssistChip(onClick = {}, label = { Text(family.label) }, modifier = modifier)
}
