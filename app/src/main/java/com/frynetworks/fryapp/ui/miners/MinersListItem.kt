package com.frynetworks.fryapp.ui.miners

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.frynetworks.fryapp.data.dashboard.repo.MinerListItem
import com.frynetworks.fryapp.data.dashboard.repo.MinerSource
import com.frynetworks.fryapp.ui.common.FamilyChip
import com.frynetworks.fryapp.ui.common.MinerStatusChip
import com.frynetworks.fryapp.ui.theme.FryCard

/** One row of the Miners tab: name, short key, status chip, family chip, claimable amount. */
@Composable
fun MinersListRow(item: MinerListItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag("miners_item_${item.minerKey}")
            .semantics { contentDescription = "Miner ${item.displayName}" },
        colors = CardDefaults.cardColors(containerColor = FryCard),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = MinerListItem.shortKey(item.minerKey),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("miners_key_${item.minerKey}"),
                    )
                    item.remote?.productName?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(8.dp))
                MinerStatusChip(status = item.status, modifier = Modifier.testTag("miners_status_${item.minerKey}"))
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FamilyChip(family = item.family, modifier = Modifier.testTag("miners_family_${item.minerKey}"))
                    if (item.source != MinerSource.REMOTE) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when (item.localOnline) {
                                true -> "Paired · online"
                                false -> "Paired · offline"
                                null -> "Paired"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = "${formatAmount(item.rewards?.claimable)} ${item.family.rewardAsset.symbol} claimable",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (MinerListShaping.claimable(item).signum() > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("miners_claimable_${item.minerKey}"),
                )
            }
        }
    }
}
