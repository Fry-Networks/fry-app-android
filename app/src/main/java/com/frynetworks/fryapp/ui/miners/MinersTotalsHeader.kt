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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.frynetworks.fryapp.data.dashboard.model.AssetTotalsResponse
import com.frynetworks.fryapp.data.dashboard.model.RewardBucket
import com.frynetworks.fryapp.data.dashboard.repo.FleetSummary
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.domain.MicroAmount
import com.frynetworks.fryapp.ui.common.CountdownText
import com.frynetworks.fryapp.ui.common.TimeFormat
import com.frynetworks.fryapp.ui.theme.FryCard
import java.math.BigDecimal

fun formatAmount(value: BigDecimal?): String = MicroAmount.fromDashboardAmount(value).format(maxFraction = 2)

/** Totals from `get-asset-totals` (fNODE + tFRY buckets) plus fleet online/total. */
@Composable
fun MinersTotalsHeader(
    totals: AssetTotalsResponse?,
    fleet: FleetSummary?,
    now: () -> Long,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().testTag("miners_totals"), colors = CardDefaults.cardColors(containerColor = FryCard)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Rewards", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = fleet?.let { "${it.online} / ${it.total} online" } ?: "Fleet status unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("miners_online").semantics { contentDescription = "Miners online" },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                BucketColumn(FryAsset.FNODE, totals?.totals?.fnode, "miners_totals_fnode", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                BucketColumn(FryAsset.TFRY, totals?.totals?.tfry, "miners_totals_tfry", Modifier.weight(1f))
            }
            val unlockAt = TimeFormat.parseMillis(totals?.nextUnlockAt)
            val claimableAt = TimeFormat.parseMillis(totals?.nextClaimableAt)
            if (unlockAt != null || claimableAt != null) Spacer(Modifier.height(8.dp))
            CountdownText(targetMillis = unlockAt, now = now, prefix = "Next unlock in", tag = "miners_next_unlock")
            CountdownText(targetMillis = claimableAt, now = now, prefix = "Next claimable in", tag = "miners_next_claimable")
            totals?.pendingWindowLabel?.let {
                Text("Pending window: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BucketColumn(asset: FryAsset, bucket: RewardBucket?, tag: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.testTag(tag).semantics { contentDescription = "${asset.symbol} totals" }) {
        Text(asset.symbol, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        BucketLine("Claimable", bucket?.claimable, "${tag}_claimable")
        BucketLine("Pending", bucket?.pending, "${tag}_pending")
        BucketLine("Accruing", bucket?.accruing, "${tag}_accruing")
        BucketLine("Claimed", bucket?.claimed, "${tag}_claimed")
    }
}

@Composable
private fun BucketLine(label: String, value: BigDecimal?, tag: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatAmount(value), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(tag))
    }
}
