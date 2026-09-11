package com.frynetworks.fryapp.ui.miners.claim

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frynetworks.fryapp.data.dashboard.repo.MinerListItem
import com.frynetworks.fryapp.domain.ClaimState
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.domain.MicroAmount
import com.frynetworks.fryapp.ui.common.CountdownText
import com.frynetworks.fryapp.ui.common.WalletPrompt
import com.frynetworks.fryapp.ui.common.openExternalUrl

/** Modal claim flow for one miner (or one reward row when [no] is set). */
@Composable
fun ClaimSheet(
    minerKey: String,
    no: Int?,
    onClose: () -> Unit,
    onCompleted: () -> Unit,
    viewModel: ClaimViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(minerKey, no) { viewModel.start(minerKey, no) }
    LaunchedEffect(state.state) { if (state.state is ClaimState.Done) onCompleted() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            if (state.canCancel) {
                viewModel.cancel()
                onClose()
            }
        },
        sheetState = sheetState,
        modifier = Modifier.testTag("claim_sheet"),
    ) {
        ClaimSheetContent(
            state = state,
            now = viewModel::now,
            onConfirm = viewModel::confirm,
            onCancel = {
                viewModel.cancel()
                onClose()
            },
            onOptIn = viewModel::optIn,
            onReconnect = viewModel::reconnectWallet,
            onOpenWallet = viewModel::openWallet,
            onRetry = viewModel::retry,
            onDone = {
                viewModel.cancel()
                onClose()
            },
        )
    }
}

/** Stateless sheet body (Compose UI tests render this directly). */
@Composable
fun ClaimSheetContent(
    state: ClaimUiState,
    now: () -> Long,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onOptIn: () -> Unit,
    onReconnect: () -> Unit,
    onOpenWallet: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()).testTag("claim_content")) {
        Text("Claim rewards", style = MaterialTheme.typography.titleLarge)
        Text(
            text = state.minerKey?.let { MinerListItem.shortKey(it) }.orEmpty() + (state.no?.let { "  ·  reward #$it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("claim_miner"),
        )
        Spacer(Modifier.height(12.dp))

        StageList(stages = state.stages(), tagPrefix = "claim")
        Spacer(Modifier.height(12.dp))

        when (val s = state.state) {
            is ClaimState.Preview -> PreviewTotals(s)
            is ClaimState.AwaitingSignature -> CountdownText(
                targetMillis = s.expiresAtMillis,
                now = now,
                prefix = "Sign within",
                tag = "claim_countdown",
            )
            is ClaimState.OptInRequired -> {
                Text(
                    "Your wallet is not opted in to ${FryAsset.fromId(s.assetId)?.symbol ?: "the reward asset"}. Opt in (a zero-value transaction) to receive rewards.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onOptIn,
                    modifier = Modifier.fillMaxWidth().testTag("claim_optin").semantics { contentDescription = "Opt in to reward asset" },
                ) { Text("Opt in") }
            }
            is ClaimState.Done -> DoneCard(
                txId = s.txId,
                claimedAt = s.claimedAt,
                onOpenExplorer = { openExternalUrl(context, ClaimViewModel.EXPLORER_TX_URL + s.txId) },
            )
            is ClaimState.Failed -> Card(modifier = Modifier.fillMaxWidth().testTag("claim_error")) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Claim failed (${s.code})", style = MaterialTheme.typography.titleSmall)
                    Text(state.errorCopy.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("claim_error_message"))
                    if (s.recoverable) {
                        TextButton(onClick = onRetry, modifier = Modifier.testTag("claim_retry")) { Text("Start again") }
                    }
                }
            }
            else -> {}
        }

        if (state.needsReconnect) {
            Spacer(Modifier.height(8.dp))
            Text("Your wallet app is not connected to this session.", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onReconnect,
                enabled = !state.connecting,
                modifier = Modifier.fillMaxWidth().testTag("claim_reconnect").semantics { contentDescription = "Reconnect wallet" },
            ) { Text("Reconnect wallet") }
        }

        if (state.awaitingWallet || state.connecting) {
            Spacer(Modifier.height(8.dp))
            WalletPrompt(
                summary = state.pendingSummary,
                canOpenWallet = state.lastWalletUri != null,
                onOpenWallet = onOpenWallet,
                tagPrefix = "claim",
                title = if (state.connecting) "Approve the connection in your wallet app" else "Continue in your wallet app",
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (state.state is ClaimState.Done) {
                Button(
                    onClick = onDone,
                    modifier = Modifier.weight(1f).testTag("claim_done").semantics { contentDescription = "Close" },
                ) { Text("Done") }
            } else {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = state.canCancel,
                    modifier = Modifier.weight(1f).testTag("claim_cancel").semantics { contentDescription = "Cancel claim" },
                ) { Text("Cancel") }
                Button(
                    onClick = onConfirm,
                    enabled = state.canConfirm,
                    modifier = Modifier.weight(1f).testTag("claim_confirm").semantics { contentDescription = "Confirm claim" },
                ) { Text("Claim") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PreviewTotals(preview: ClaimState.Preview) {
    Card(modifier = Modifier.fillMaxWidth().testTag("claim_totals")) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("You will receive", style = MaterialTheme.typography.labelMedium)
            if (preview.totals.isEmpty()) Text("Nothing claimable right now.", style = MaterialTheme.typography.bodySmall)
            preview.totals.forEach { total ->
                val asset = FryAsset.fromId(total.assetId)
                Text(
                    text = "${MicroAmount.fromDashboardAmount(total.amount).format(maxFraction = 6)} ${asset?.symbol ?: "asset ${total.assetId}"}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("claim_total_${total.assetId}"),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "You pay a 0.001 ALGO fee plus the network fee for the claim group.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DoneCard(txId: String, claimedAt: Long?, onOpenExplorer: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag("claim_success")) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Rewards claimed", style = MaterialTheme.typography.titleSmall)
            claimedAt?.let { Text(com.frynetworks.fryapp.ui.common.TimeFormat.dateTime(it), style = MaterialTheme.typography.bodySmall) }
            TextButton(
                onClick = onOpenExplorer,
                enabled = txId.isNotBlank(),
                modifier = Modifier.testTag("claim_txid").semantics { contentDescription = "View transaction" },
            ) { Text(if (txId.isBlank()) "No transaction id" else "View ${txId.take(10)}… on explorer") }
        }
    }
}

/** Shared stage list for the claim/stake sheets (`<prefix>_stage_<n>`, 1-based). */
@Composable
fun <T : Enum<T>> StageList(stages: List<Pair<T, StageStatus>>, tagPrefix: String, label: (T) -> String = { stageLabel(it) }) {
    Column(modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_stages")) {
        stages.forEachIndexed { index, (stage, status) ->
            val marker = when (status) {
                StageStatus.DONE -> "✓"
                StageStatus.CURRENT -> "…"
                StageStatus.PENDING -> "○"
                StageStatus.FAILED -> "✕"
            }
            val color = when (status) {
                StageStatus.DONE -> MaterialTheme.colorScheme.secondary
                StageStatus.CURRENT -> MaterialTheme.colorScheme.onSurface
                StageStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                StageStatus.FAILED -> MaterialTheme.colorScheme.primary
            }
            Text(
                text = "$marker  ${label(stage)}",
                style = MaterialTheme.typography.bodySmall,
                color = color,
                modifier = Modifier
                    .testTag("${tagPrefix}_stage_${index + 1}")
                    .semantics { contentDescription = "${label(stage)}: ${status.name.lowercase()}" },
            )
        }
    }
}

private fun <T : Enum<T>> stageLabel(stage: T): String = when (stage) {
    is ClaimStage -> stage.label
    else -> stage.name
}
