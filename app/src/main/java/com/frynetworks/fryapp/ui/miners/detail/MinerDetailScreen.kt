package com.frynetworks.fryapp.ui.miners.detail

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.domain.StakeTier
import com.frynetworks.fryapp.ui.common.EmptyState
import com.frynetworks.fryapp.ui.common.ErrorState
import com.frynetworks.fryapp.ui.common.LoadingState
import com.frynetworks.fryapp.ui.common.Phase
import com.frynetworks.fryapp.ui.miners.claim.ClaimSheet
import com.frynetworks.fryapp.ui.miners.stake.StakeSheet

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MinerDetailScreen(
    onBack: () -> Unit,
    onOpenRewards: (String) -> Unit,
    viewModel: MinerDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.actionMessage) {
        val message = state.actionMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.consumeActionMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.displayName, modifier = Modifier.testTag("miner_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("miner_back").semantics { contentDescription = "Back" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, modifier = Modifier.testTag("miner_refresh").semantics { contentDescription = "Refresh" }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.testTag("miner_snackbar")) },
    ) { padding ->
        MinerDetailContent(
            state = state,
            now = viewModel::now,
            onRefresh = viewModel::refresh,
            onRetry = viewModel::refresh,
            onOpenRewards = { onOpenRewards(state.minerKey) },
            onClaim = { viewModel.openClaim() },
            onStake = viewModel::openStake,
            onWithdraw = { viewModel.openDialog(DetailDialog.Withdraw(it)) },
            onRename = { viewModel.openDialog(DetailDialog.Rename) },
            onRewardWallet = { viewModel.openDialog(DetailDialog.RewardWallet) },
            modifier = Modifier.padding(padding).fillMaxSize().semantics { testTagsAsResourceId = true },
        )
    }

    when (val dialog = state.dialog) {
        DetailDialog.Rename -> RenameDialog(
            current = state.detail?.nickname ?: state.displayName,
            busy = state.busy,
            error = state.actionError,
            onSave = viewModel::rename,
            onDismiss = viewModel::closeDialog,
        )
        DetailDialog.RewardWallet -> RewardWalletDialog(
            current = state.detail?.rewardWallet,
            busy = state.busy,
            error = state.actionError,
            onSave = viewModel::setRewardWallet,
            onDismiss = viewModel::closeDialog,
        )
        is DetailDialog.Withdraw -> WithdrawDialog(
            context = dialog.context,
            busy = state.busy,
            error = state.actionError,
            onConfirm = { viewModel.withdraw(dialog.context) },
            onDismiss = viewModel::closeDialog,
        )
        null -> {}
    }

    when (val sheet = state.sheet) {
        is DetailSheet.Claim -> ClaimSheet(
            minerKey = state.minerKey,
            no = sheet.no,
            onClose = viewModel::closeSheet,
            onCompleted = viewModel::onSheetCompleted,
        )
        is DetailSheet.Stake -> StakeSheet(
            minerKey = state.minerKey,
            context = sheet.context,
            byod = sheet.byod,
            onClose = viewModel::closeSheet,
            onCompleted = viewModel::onSheetCompleted,
        )
        null -> {}
    }
}

/** Stateless body: identity / rewards / stake / hardware cards and the actions row. */
@Composable
fun MinerDetailContent(
    state: MinerDetailUiState,
    now: () -> Long,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onOpenRewards: () -> Unit,
    onClaim: () -> Unit,
    onStake: (StakeContext) -> Unit,
    onWithdraw: (StakeContext) -> Unit,
    onRename: () -> Unit,
    onRewardWallet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh, modifier = modifier.testTag("miner_root")) {
        when (state.phase) {
            Phase.Loading -> LoadingState(tag = "miner_loading", modifier = Modifier.fillMaxSize())
            Phase.Error -> ErrorState(
                error = state.error ?: return@PullToRefreshBox,
                tag = "miner_error",
                retryTag = "miner_error_retry",
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
            Phase.Empty -> EmptyState(
                title = "Miner not found",
                body = "This miner is not registered to the signed-in wallet.",
                tag = "miner_empty",
                modifier = Modifier.fillMaxSize(),
            )
            Phase.Content -> LazyColumn(modifier = Modifier.fillMaxSize().testTag("miner_content")) {
                item(key = "identity") { IdentityCard(state, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                item(key = "actions") {
                    MinerActionsRow(
                        actions = state.actions,
                        verificationTier = state.detail?.staked?.type?.let { StakeTier.fromCode(it) },
                        onClaim = onClaim,
                        onStake = onStake,
                        onWithdraw = onWithdraw,
                        onRename = onRename,
                        onRewardWallet = onRewardWallet,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                item(key = "rewards") { RewardsCard(state, now = now, onOpenHistory = onOpenRewards, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                item(key = "stake") { StakeCard(state, now = now, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                if (!state.isVirtual && state.family.prefix in MinerFamily.hardwareMacPrefixes) {
                    item(key = "hardware") { HardwareCard(state.hardware, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                }
                state.error?.let { error ->
                    item(key = "inline_error") {
                        Text(error.message, modifier = Modifier.padding(16.dp).testTag("miner_inline_error"))
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
