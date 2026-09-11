package com.frynetworks.fryapp.ui.miners.rewards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frynetworks.fryapp.data.dashboard.model.RewardItem
import com.frynetworks.fryapp.data.dashboard.model.RewardStatus
import com.frynetworks.fryapp.data.dashboard.repo.MinerListItem
import com.frynetworks.fryapp.ui.common.EmptyState
import com.frynetworks.fryapp.ui.common.ErrorState
import com.frynetworks.fryapp.ui.common.LoadingState
import com.frynetworks.fryapp.ui.common.Phase
import com.frynetworks.fryapp.ui.common.RewardStatusChip
import com.frynetworks.fryapp.ui.common.TimeFormat
import com.frynetworks.fryapp.ui.common.openExternalUrl
import com.frynetworks.fryapp.ui.miners.claim.ClaimSheet
import com.frynetworks.fryapp.ui.miners.claim.ClaimViewModel
import com.frynetworks.fryapp.ui.miners.formatAmount
import com.frynetworks.fryapp.ui.theme.FryCard
import java.math.RoundingMode

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RewardsHistoryScreen(
    onBack: () -> Unit,
    viewModel: RewardsHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reward history · ${MinerListItem.shortKey(state.minerKey)}") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("rewards_back").semantics { contentDescription = "Back" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        RewardsHistoryContent(
            state = state,
            onRetry = viewModel::retry,
            onPrev = viewModel::prev,
            onNext = viewModel::next,
            onClaim = viewModel::openClaim,
            modifier = Modifier.padding(padding).fillMaxSize().semantics { testTagsAsResourceId = true },
        )
    }
    state.claimNo?.let { no ->
        ClaimSheet(minerKey = state.minerKey, no = no, onClose = viewModel::closeClaim, onCompleted = viewModel::onClaimCompleted)
    }
}

/** Stateless body (Compose UI tests render this directly). */
@Composable
fun RewardsHistoryContent(
    state: RewardsHistoryUiState,
    onRetry: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClaim: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier.testTag("rewards_root")) {
        when (state.phase) {
            Phase.Loading -> LoadingState(tag = "rewards_loading", modifier = Modifier.fillMaxSize())
            Phase.Error -> ErrorState(
                error = state.error ?: return@Column,
                tag = "rewards_error",
                retryTag = "rewards_error_retry",
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
            Phase.Empty -> EmptyState(
                title = "No rewards yet",
                body = "Rewards appear here once this miner starts earning.",
                tag = "rewards_empty",
                modifier = Modifier.fillMaxSize(),
            )
            Phase.Content -> {
                CountsHeader(state)
                state.error?.let { Text(it.message, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp).testTag("rewards_inline_error")) }
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().testTag("rewards_list")) {
                    items(state.items, key = { it.id ?: "${it.no}" }) { item ->
                        RewardRow(
                            item = item,
                            symbol = state.asset.symbol,
                            canClaim = state.signedIn,
                            onClaim = onClaim,
                            onOpenTx = { txId -> openExternalUrl(context, ClaimViewModel.EXPLORER_TX_URL + txId) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
                Pager(state, onPrev, onNext)
            }
        }
    }
}

@Composable
private fun CountsHeader(state: RewardsHistoryUiState) {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("rewards_counts"),
    ) {
        CountCell("Weekly", state.weeklyCount, "rewards_count_weekly")
        CountCell("Daily", state.dailyCount, "rewards_count_daily")
        CountCell("Total", state.totalCount, "rewards_count_total")
    }
}

@Composable
private fun CountCell(label: String, value: Int?, tag: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value?.toString() ?: "—", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(tag))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Pager(state: RewardsHistoryUiState, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedButton(
            onClick = onPrev,
            enabled = state.hasPrev,
            modifier = Modifier.testTag("rewards_page_prev").semantics { contentDescription = "Previous page" },
        ) { Text("Previous") }
        Text("Page ${state.page} of ${state.totalPages}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("rewards_page_label"))
        OutlinedButton(
            onClick = onNext,
            enabled = state.hasNext,
            modifier = Modifier.testTag("rewards_page_next").semantics { contentDescription = "Next page" },
        ) { Text("Next") }
    }
}

@Composable
private fun RewardRow(
    item: RewardItem,
    symbol: String,
    canClaim: Boolean,
    onClaim: (Int) -> Unit,
    onOpenTx: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val no = item.no ?: 0
    Card(modifier = modifier.fillMaxWidth().testTag("rewards_item_$no"), colors = CardDefaults.cardColors(containerColor = FryCard)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "#$no · " + (item.weekLabel ?: TimeFormat.dateOr(item.createdAt)),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = if (item.isWeekly) "Weekly reward" else "Daily reward",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RewardStatusChip(status = item.status, modifier = Modifier.testTag("rewards_status_$no"))
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${formatAmount(item.amount)} $symbol",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("rewards_amount_$no"),
                )
                if (item.onHold && item.originalAmount != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${formatAmount(item.originalAmount)} $symbol",
                        style = MaterialTheme.typography.bodySmall,
                        textDecoration = TextDecoration.LineThrough,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("rewards_original_$no"),
                    )
                }
                item.fiatValue?.let {
                    Spacer(Modifier.width(8.dp))
                    Text("≈ $" + it.setScale(2, RoundingMode.HALF_UP).toPlainString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item.etaDate?.let {
                Text(
                    text = "Unlocks ${TimeFormat.dateOr(it)}" + (item.progressDays?.let { d -> " · day $d" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.claimedAt?.let { Text("Claimed ${TimeFormat.dateOr(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.txId?.takeIf { it.isNotBlank() }?.let { txId ->
                    TextButton(
                        onClick = { onOpenTx(txId) },
                        modifier = Modifier.testTag("rewards_tx_$no").semantics { contentDescription = "View transaction" },
                    ) { Text("View tx ${txId.take(8)}…") }
                }
                if (item.status == RewardStatus.CLAIMABLE && item.no != null) {
                    TextButton(
                        onClick = { onClaim(item.no) },
                        enabled = canClaim,
                        modifier = Modifier.testTag("rewards_claim_$no").semantics { contentDescription = "Claim reward $no" },
                    ) { Text("Claim") }
                }
            }
        }
    }
}
