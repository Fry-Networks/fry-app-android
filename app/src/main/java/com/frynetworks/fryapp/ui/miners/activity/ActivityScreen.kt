package com.frynetworks.fryapp.ui.miners.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frynetworks.fryapp.data.dashboard.model.ActivityEvent
import com.frynetworks.fryapp.data.dashboard.repo.MinerListItem
import com.frynetworks.fryapp.ui.common.EmptyState
import com.frynetworks.fryapp.ui.common.ErrorState
import com.frynetworks.fryapp.ui.common.LoadingState
import com.frynetworks.fryapp.ui.common.Phase
import com.frynetworks.fryapp.ui.common.TimeFormat
import com.frynetworks.fryapp.ui.miners.formatAmount
import com.frynetworks.fryapp.ui.theme.FryCard

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ActivityScreen(
    onBack: () -> Unit,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recent activity") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("activity_back").semantics { contentDescription = "Back" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        ActivityContent(
            state = state,
            now = viewModel::now,
            onRetry = viewModel::load,
            modifier = Modifier.padding(padding).fillMaxSize().semantics { testTagsAsResourceId = true },
        )
    }
}

/** Stateless body (Compose UI tests render this directly). */
@Composable
fun ActivityContent(state: ActivityUiState, now: () -> Long, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (state.phase) {
        Phase.Loading -> LoadingState(tag = "activity_loading", modifier = modifier.testTag("activity_root"))
        Phase.Error -> ErrorState(
            error = state.error ?: return,
            tag = "activity_error",
            retryTag = "activity_error_retry",
            onRetry = onRetry,
            modifier = modifier.testTag("activity_root"),
        )
        Phase.Empty -> EmptyState(
            title = "No activity yet",
            body = "Reward unlocks, claims and registrations show up here.",
            tag = "activity_empty",
            modifier = modifier.testTag("activity_root"),
        )
        Phase.Content -> LazyColumn(modifier = modifier.testTag("activity_root")) {
            itemsIndexed(state.events) { index, event ->
                ActivityRow(index = index, event = event, now = now, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun iconFor(type: String?): ImageVector = when (type) {
    "reward_unlocked" -> Icons.Filled.LockOpen
    "reward_claimed" -> Icons.Filled.Paid
    "registered" -> Icons.Filled.CheckCircle
    else -> Icons.Filled.Info
}

fun activityTitle(type: String?): String = when (type) {
    "reward_unlocked" -> "Reward unlocked"
    "reward_claimed" -> "Reward claimed"
    "registered" -> "Miner registered"
    else -> type?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "Activity"
}

@Composable
private fun ActivityRow(index: Int, event: ActivityEvent, now: () -> Long, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().testTag("activity_item_$index"), colors = CardDefaults.cardColors(containerColor = FryCard)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Icon(iconFor(event.type), contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(activityTitle(event.type), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("activity_type_$index"))
                Text(
                    text = event.nickname?.takeIf { it.isNotBlank() } ?: event.minerKey?.let { MinerListItem.shortKey(it) } ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                event.amount?.let {
                    Text("${formatAmount(it)} ${event.asset.orEmpty()}".trim(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("activity_amount_$index"))
                }
            }
            Text(
                text = TimeFormat.parseMillis(event.at)?.let { TimeFormat.relative(it, now()) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("activity_time_$index"),
            )
        }
    }
}
