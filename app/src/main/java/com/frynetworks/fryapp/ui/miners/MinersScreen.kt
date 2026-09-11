package com.frynetworks.fryapp.ui.miners

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.data.dashboard.repo.MinerStatus
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.ui.account.SignInScreen
import com.frynetworks.fryapp.ui.account.SignInViewModel
import com.frynetworks.fryapp.ui.common.EmptyState
import com.frynetworks.fryapp.ui.common.ErrorState
import com.frynetworks.fryapp.ui.common.LoadingState
import com.frynetworks.fryapp.ui.common.Phase

/**
 * "Miners" tab: every miner registered to the signed-in wallet, of every type. Renders the
 * wallet sign-in inline until a dashboard session exists.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MinersScreen(
    onOpenMiner: (String) -> Unit = {},
    onOpenKeys: () -> Unit = {},
    onOpenActivity: () -> Unit = {},
    onAddDevice: () -> Unit = {},
    signInViewModel: SignInViewModel = hiltViewModel(),
    viewModel: MinersViewModel = hiltViewModel(),
) {
    val signIn by signInViewModel.uiState.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Miners") },
                actions = {
                    if (state.signedIn) {
                        IconButton(
                            onClick = onOpenKeys,
                            modifier = Modifier.testTag("miners_open_keys").semantics { contentDescription = "Miner keys" },
                        ) { Icon(Icons.Filled.Key, contentDescription = null) }
                        IconButton(
                            onClick = onOpenActivity,
                            modifier = Modifier.testTag("miners_open_activity").semantics { contentDescription = "Activity" },
                        ) { Icon(Icons.Filled.History, contentDescription = null) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.testTag("miners_snackbar")) },
    ) { padding ->
        val rootModifier = Modifier.padding(padding).fillMaxSize().semantics { testTagsAsResourceId = true }
        when (signIn.session) {
            is SessionState.SignedIn -> MinersContent(
                state = state,
                now = { System.currentTimeMillis() },
                onRefresh = viewModel::refresh,
                onRetry = viewModel::refresh,
                onQuery = viewModel::setQuery,
                onToggleStatus = viewModel::toggleStatusFilter,
                onToggleFamily = viewModel::toggleFamilyFilter,
                onSort = viewModel::setSort,
                onOpenMiner = onOpenMiner,
                onAddDevice = onAddDevice,
                modifier = rootModifier,
            )
            else -> Column(modifier = rootModifier.verticalScroll(rememberScrollState()).testTag("miners_root")) {
                SignInScreen(state = signIn, viewModel = signInViewModel)
            }
        }
    }
}

/** Stateless signed-in body (Compose UI tests render this directly). */
@Composable
fun MinersContent(
    state: MinersUiState,
    now: () -> Long,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onQuery: (String) -> Unit,
    onToggleStatus: (MinerStatus) -> Unit,
    onToggleFamily: (MinerFamily) -> Unit,
    onSort: (MinerSort) -> Unit,
    onOpenMiner: (String) -> Unit,
    onAddDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.testTag("miners_root"),
    ) {
        when (state.phase) {
            Phase.Loading -> LoadingState(tag = "miners_loading", modifier = Modifier.fillMaxSize())
            Phase.Error -> ErrorState(
                error = state.error ?: return@PullToRefreshBox,
                tag = "miners_error",
                retryTag = "miners_error_retry",
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
            Phase.Empty -> EmptyState(
                title = "No miners on this wallet yet",
                body = "Provision a Fry miner or register one on the dashboard to see it here.",
                tag = "miners_empty",
                actionLabel = "Add a device",
                actionTag = "miners_empty_add_device",
                onAction = onAddDevice,
                modifier = Modifier.fillMaxSize(),
            )
            Phase.Content -> LazyColumn(modifier = Modifier.fillMaxSize().testTag("miners_list")) {
                item(key = "totals") {
                    MinersTotalsHeader(
                        totals = state.totals,
                        fleet = state.fleet,
                        now = now,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item(key = "filters") {
                    MinersFilterBar(
                        query = state.query,
                        statusFilter = state.statusFilter,
                        familyFilter = state.familyFilter,
                        families = state.availableFamilies,
                        sort = state.sort,
                        onQuery = onQuery,
                        onToggleStatus = onToggleStatus,
                        onToggleFamily = onToggleFamily,
                        onSort = onSort,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                state.error?.let { error ->
                    item(key = "inline_error") {
                        Text(
                            text = error.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("miners_inline_error"),
                        )
                    }
                }
                item(key = "count") {
                    Text(
                        text = "${state.items.size} of ${state.totalCount} miners",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("miners_count"),
                    )
                }
                if (state.items.isEmpty()) {
                    item(key = "no_match") {
                        Text(
                            text = "No miners match your search or filters.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("miners_no_match"),
                        )
                    }
                }
                items(state.items, key = { it.minerKey }) { item ->
                    MinersListRow(
                        item = item,
                        onClick = { onOpenMiner(item.minerKey) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
