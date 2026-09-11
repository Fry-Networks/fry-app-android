package com.frynetworks.fryapp.ui.miners.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.ui.common.UiError
import com.frynetworks.fryapp.util.AlgorandAddress

@Composable
fun RenameDialog(
    current: String,
    busy: Boolean,
    error: UiError?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Rename miner") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nickname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rename_input").semantics { contentDescription = "Nickname" },
                )
                error?.let { Text(it.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("rename_error")) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name) },
                enabled = !busy && name.isNotBlank(),
                modifier = Modifier.testTag("rename_save").semantics { contentDescription = "Save nickname" },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.testTag("rename_cancel")) { Text("Cancel") } },
        modifier = Modifier.testTag("rename_dialog"),
    )
}

@Composable
fun RewardWalletDialog(
    current: String?,
    busy: Boolean,
    error: UiError?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var address by rememberSaveable { mutableStateOf(current.orEmpty()) }
    val valid = AlgorandAddress.isValid(address.trim())
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Reward wallet") },
        text = {
            Column {
                Text("Rewards for this miner are paid to this Algorand address.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Algorand address") },
                    singleLine = true,
                    isError = address.isNotBlank() && !valid,
                    modifier = Modifier.fillMaxWidth().testTag("rewardwallet_input").semantics { contentDescription = "Reward wallet address" },
                )
                if (address.isNotBlank() && !valid) {
                    Text("Not a valid Algorand address.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("rewardwallet_invalid"))
                }
                error?.let { Text(it.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("rewardwallet_error")) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(address.trim()) },
                enabled = !busy && valid,
                modifier = Modifier.testTag("rewardwallet_save").semantics { contentDescription = "Save reward wallet" },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.testTag("rewardwallet_cancel")) { Text("Cancel") } },
        modifier = Modifier.testTag("rewardwallet_dialog"),
    )
}

@Composable
fun WithdrawDialog(
    context: StakeContext,
    busy: Boolean,
    error: UiError?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Withdraw ${context.label().lowercase()}?") },
        text = {
            Column {
                Text(
                    "The dashboard will return the staked tokens to your wallet. This may change the miner's registration or verification status.",
                    style = MaterialTheme.typography.bodySmall,
                )
                error?.let { Text(it.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("withdraw_error")) }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !busy,
                modifier = Modifier.testTag("withdraw_confirm").semantics { contentDescription = "Confirm withdrawal" },
            ) { Text("Withdraw") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.testTag("withdraw_cancel")) { Text("Cancel") } },
        modifier = Modifier.testTag("withdraw_dialog"),
    )
}
