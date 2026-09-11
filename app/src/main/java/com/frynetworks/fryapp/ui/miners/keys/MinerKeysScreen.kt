package com.frynetworks.fryapp.ui.miners.keys

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frynetworks.fryapp.data.dashboard.model.IotCredentials
import com.frynetworks.fryapp.data.dashboard.model.MyKeyDevice
import com.frynetworks.fryapp.ui.common.CopyableText
import com.frynetworks.fryapp.ui.common.EmptyState
import com.frynetworks.fryapp.ui.common.ErrorState
import com.frynetworks.fryapp.ui.common.LoadingState
import com.frynetworks.fryapp.ui.common.Phase
import com.frynetworks.fryapp.ui.common.TimeFormat
import com.frynetworks.fryapp.ui.theme.FryCard

private const val MASK = "••••••••"

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MinerKeysScreen(
    onBack: () -> Unit,
    viewModel: MinerKeysViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Miner keys") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("keys_back").semantics { contentDescription = "Back" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        MinerKeysContent(
            state = state,
            onRetry = viewModel::load,
            onToggleReveal = viewModel::toggleReveal,
            modifier = Modifier.padding(padding).fillMaxSize().semantics { testTagsAsResourceId = true },
        )
    }
}

/** Stateless body (Compose UI tests render this directly). */
@Composable
fun MinerKeysContent(
    state: MinerKeysUiState,
    onRetry: () -> Unit,
    onToggleReveal: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state.phase) {
        Phase.Loading -> LoadingState(tag = "keys_loading", modifier = modifier.testTag("keys_root"))
        Phase.Error -> ErrorState(
            error = state.error ?: return,
            tag = "keys_error",
            retryTag = "keys_error_retry",
            onRetry = onRetry,
            modifier = modifier.testTag("keys_root"),
        )
        Phase.Empty -> EmptyState(
            title = "No miner keys",
            body = "Keys registered to this wallet appear here.",
            tag = "keys_empty",
            modifier = modifier.testTag("keys_root"),
        )
        Phase.Content -> LazyColumn(modifier = modifier.testTag("keys_root")) {
            state.groups.forEach { group ->
                item(key = "group_${group.family.name}") {
                    Text(
                        text = group.family.label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("keys_group_${group.family.name}"),
                    )
                }
                items(group.devices, key = { "device_${it.minerKey}" }) { device ->
                    KeyCard(
                        device = device,
                        isRevealed = { portal -> state.isRevealed(device.minerKey.orEmpty(), portal) },
                        onToggleReveal = { portal -> onToggleReveal(device.minerKey.orEmpty(), portal) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (state.byodLicenses.isNotEmpty()) {
                item(key = "byod_header") {
                    Text("BYOD licenses", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("keys_byod_header"))
                }
                state.byodLicenses.forEachIndexed { index, license ->
                    item(key = "byod_$index") {
                        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("keys_byod_$index"), colors = CardDefaults.cardColors(containerColor = FryCard)) {
                            CopyableText(
                                text = license,
                                label = "BYOD license",
                                textTag = "keys_byod_text_$index",
                                copyTag = "keys_byod_copy_$index",
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun KeyCard(
    device: MyKeyDevice,
    isRevealed: (String) -> Boolean,
    onToggleReveal: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val key = device.minerKey.orEmpty()
    Card(modifier = modifier.fillMaxWidth().testTag("keys_item_$key"), colors = CardDefaults.cardColors(containerColor = FryCard)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device.nickname ?: key, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                AssistChip(
                    onClick = {},
                    label = { Text(if (device.isRegistered) "Registered" else "Unregistered") },
                    modifier = Modifier.testTag("keys_registered_$key"),
                )
            }
            CopyableText(text = key, label = "Miner key", textTag = "keys_key_$key", copyTag = "keys_copy_$key")
            device.credentials.forEach { cred ->
                val portal = cred.portal ?: "portal"
                CredentialBlock(
                    minerKey = key,
                    portal = portal,
                    credentials = cred,
                    revealed = isRevealed(portal),
                    onToggle = { onToggleReveal(portal) },
                )
            }
        }
    }
}

@Composable
private fun CredentialBlock(
    minerKey: String,
    portal: String,
    credentials: IotCredentials,
    revealed: Boolean,
    onToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("keys_cred_${minerKey}_$portal")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$portal credentials", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            IconButton(
                onClick = onToggle,
                modifier = Modifier
                    .testTag("keys_cred_reveal_${minerKey}_$portal")
                    .semantics { contentDescription = if (revealed) "Hide $portal credentials" else "Reveal $portal credentials" },
            ) {
                Icon(if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = null)
            }
        }
        credentials.credentials.forEach { (field, value) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("$field: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = if (revealed) value?.toString().orEmpty() else MASK,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("keys_cred_value_${minerKey}_${portal}_$field"),
                )
            }
        }
        credentials.credentialsSavedAt?.let {
            Text("Saved ${TimeFormat.dateOr(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (revealed) {
            Text("Hides automatically in 15 s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
