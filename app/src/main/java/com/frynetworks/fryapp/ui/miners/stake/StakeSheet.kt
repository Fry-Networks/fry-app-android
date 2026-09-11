package com.frynetworks.fryapp.ui.miners.stake

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.domain.StakeState
import com.frynetworks.fryapp.domain.StakeTier
import com.frynetworks.fryapp.ui.common.WalletPrompt
import com.frynetworks.fryapp.ui.common.openExternalUrl
import com.frynetworks.fryapp.ui.miners.claim.ClaimViewModel
import com.frynetworks.fryapp.ui.miners.claim.StageList
import com.frynetworks.fryapp.ui.miners.detail.label
import java.math.RoundingMode

@Composable
fun StakeSheet(
    minerKey: String,
    context: StakeContext,
    byod: Boolean,
    onClose: () -> Unit,
    onCompleted: () -> Unit,
    viewModel: StakeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(minerKey, context, byod) { viewModel.start(minerKey, context, byod) }
    LaunchedEffect(state.state) { if (state.state is StakeState.Done) onCompleted() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            if (state.canCancel) {
                viewModel.cancel()
                onClose()
            }
        },
        sheetState = sheetState,
        modifier = Modifier.testTag("stake_sheet"),
    ) {
        StakeSheetContent(
            state = state,
            onConfirm = viewModel::confirm,
            onCancel = {
                viewModel.cancel()
                onClose()
            },
            onOptIn = viewModel::optIn,
            onReconnect = viewModel::reconnectWallet,
            onOpenWallet = viewModel::openWallet,
            onRetry = viewModel::retry,
            onSelectTier = viewModel::selectTier,
            onDone = {
                viewModel.cancel()
                onClose()
            },
        )
    }
}

/** Stateless sheet body (Compose UI tests render this directly). */
@Composable
fun StakeSheetContent(
    state: StakeUiState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onOptIn: () -> Unit,
    onReconnect: () -> Unit,
    onOpenWallet: () -> Unit,
    onRetry: () -> Unit,
    onSelectTier: (StakeTier) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val androidContext = LocalContext.current
    Column(modifier = modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()).testTag("stake_content")) {
        Text(state.context?.label() ?: "Stake", style = MaterialTheme.typography.titleLarge)
        Text(
            text = state.minerKey?.let { MinerListItem.shortKey(it) }.orEmpty() + if (state.byod) "  ·  BYOD (halved)" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("stake_miner"),
        )
        Spacer(Modifier.height(12.dp))

        val context = state.context
        if (context is StakeContext.Verification) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StakeTier.entries.forEach { tier ->
                    FilterChip(
                        selected = context.tier == tier,
                        onClick = { onSelectTier(tier) },
                        enabled = state.canCancel,
                        label = { Text(if (tier == StakeTier.ONE) "Tier one · 1 day lock" else "Tier two · 180 day lock") },
                        modifier = Modifier.testTag("stake_tier_${tier.code}"),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        StageList(stages = state.stages(), tagPrefix = "stake", label = { it.label })
        Spacer(Modifier.height(12.dp))

        if (state.loading) {
            CircularProgressIndicator(modifier = Modifier.testTag("stake_loading"))
        }

        state.plan?.let { plan -> AmountCard(plan) }

        when (val s = state.state) {
            is StakeState.OptInRequired -> {
                Text("Your wallet is not opted in to the stake asset. Opt in to continue.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onOptIn,
                    enabled = state.pendingSummary == null,
                    modifier = Modifier.fillMaxWidth().testTag("stake_optin").semantics { contentDescription = "Opt in to stake asset" },
                ) { Text("Opt in") }
            }
            is StakeState.Done -> Card(modifier = Modifier.fillMaxWidth().testTag("stake_success")) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(if (s.waived) "Stake already satisfied (waived)" else "Stake recorded", style = MaterialTheme.typography.titleSmall)
                    TextButton(
                        onClick = { openExternalUrl(androidContext, ClaimViewModel.EXPLORER_TX_URL + s.txId) },
                        enabled = s.txId.isNotBlank(),
                        modifier = Modifier.testTag("stake_txid").semantics { contentDescription = "View transaction" },
                    ) { Text(if (s.txId.isBlank()) "No transaction id" else "View ${s.txId.take(10)}… on explorer") }
                }
            }
            is StakeState.Failed -> Card(modifier = Modifier.fillMaxWidth().testTag("stake_error")) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Stake failed (${s.code})", style = MaterialTheme.typography.titleSmall)
                    Text(state.errorCopy.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("stake_error_message"))
                    s.retryAfterSeconds?.let {
                        Text("Retry after $it s", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("stake_retry_after"))
                    }
                    if (s.recoverable) {
                        TextButton(onClick = onRetry, modifier = Modifier.testTag("stake_retry")) { Text("Start again") }
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
                modifier = Modifier.fillMaxWidth().testTag("stake_reconnect").semantics { contentDescription = "Reconnect wallet" },
            ) { Text("Reconnect wallet") }
        }

        if (state.awaitingWallet || state.connecting) {
            Spacer(Modifier.height(8.dp))
            WalletPrompt(
                summary = state.pendingSummary,
                canOpenWallet = state.lastWalletUri != null,
                onOpenWallet = onOpenWallet,
                tagPrefix = "stake",
                title = if (state.connecting) "Approve the connection in your wallet app" else "Continue in your wallet app",
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (state.state is StakeState.Done) {
                Button(
                    onClick = onDone,
                    modifier = Modifier.weight(1f).testTag("stake_done").semantics { contentDescription = "Close" },
                ) { Text("Done") }
            } else {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = state.canCancel,
                    modifier = Modifier.weight(1f).testTag("stake_cancel").semantics { contentDescription = "Cancel stake" },
                ) { Text("Cancel") }
                Button(
                    onClick = onConfirm,
                    enabled = state.canConfirm,
                    modifier = Modifier.weight(1f).testTag("stake_confirm").semantics { contentDescription = "Confirm stake" },
                ) { Text("Stake") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AmountCard(plan: StakeState.Ready) {
    Card(modifier = Modifier.fillMaxWidth().testTag("stake_plan")) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("You will stake", style = MaterialTheme.typography.labelMedium)
            Text("${plan.amount.toPlainString()} ${plan.asset.symbol}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("stake_amount"))
            Text(
                text = "≈ $" + plan.usd.setScale(2, RoundingMode.HALF_UP).toPlainString() + " USD",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("stake_usd"),
            )
            Text(
                text = "Asset ${plan.asset.symbol} (${plan.asset.id})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("stake_asset"),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Tokens are transferred to the Fry stake wallet and recorded against this miner. Keep at least 0.002 ALGO for fees.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
