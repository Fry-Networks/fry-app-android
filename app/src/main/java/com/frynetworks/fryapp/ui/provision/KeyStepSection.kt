package com.frynetworks.fryapp.ui.provision

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.frynetworks.fryapp.data.keys.KeyCheckResult
import com.frynetworks.fryapp.domain.MinerKeyFormat
import com.frynetworks.fryapp.domain.MinerKeyInput

/**
 * The owner-key step (O-1 USER_SUPPLIED): paste the FEM- key, see its format and, when signed in,
 * what the dashboard says about it; an ESP8266 without a key also needs its setup code.
 */
@Composable
fun KeyStepSection(
    minerKey: String,
    onMinerKeyChange: (String) -> Unit,
    setupCode: String,
    onSetupCodeChange: (String) -> Unit,
    showSetupCode: Boolean,
    keyCheck: KeyCheckUi,
    acknowledged: Boolean,
    onAcknowledgedChange: (Boolean) -> Unit,
    onCheckKey: (String) -> Unit,
    onClearCheck: () -> Unit,
) {
    val parsed = MinerKeyFormat.parse(minerKey)
    val validKey = (parsed as? MinerKeyInput.Valid)?.key
    LaunchedEffect(validKey) {
        if (validKey != null) onCheckKey(validKey) else onClearCheck()
    }

    OutlinedTextField(
        value = minerKey,
        onValueChange = onMinerKeyChange,
        label = { Text("Miner key (FEM-…)") },
        singleLine = true,
        isError = parsed is MinerKeyInput.Invalid || parsed is MinerKeyInput.LegacyIot,
        supportingText = {
            Text(
                when (parsed) {
                    MinerKeyInput.Empty -> "Paste the FEM- key from the dashboard (Generate Free FEM Key). Boards on older firmware keep their own key; leave it empty for those."
                    is MinerKeyInput.Valid -> "Key format OK."
                    is MinerKeyInput.LegacyIot -> MinerKeyFormat.LEGACY_GUIDANCE + "."
                    is MinerKeyInput.Invalid -> parsed.reason
                },
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("prov_key")
            .semantics { contentDescription = "Miner key" },
    )
    if (parsed is MinerKeyInput.LegacyIot) {
        TextButton(onClick = { onMinerKeyChange(parsed.suggestion) }, modifier = Modifier.testTag("prov_key_use_fem")) {
            Text("Use ${MinerKeyFormat.mask(parsed.suggestion)} instead")
        }
    }

    when (keyCheck) {
        KeyCheckUi.Idle -> Unit
        is KeyCheckUi.Checking -> Text("Checking this key with the dashboard…", modifier = Modifier.testTag("prov_key_checking"))
        is KeyCheckUi.Done -> if (keyCheck.minerKey == validKey) {
            val result = keyCheck.result
            val blocking = result is KeyCheckResult.Checked && result.blocksSetup
            Text(
                text = KeyCheckCopy.summary(result),
                color = if (blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().testTag("prov_key_check").semantics { contentDescription = "Key check result" },
            )
            KeyCheckCopy.acknowledgement(result)?.let { text ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Checkbox(checked = acknowledged, onCheckedChange = onAcknowledgedChange, modifier = Modifier.testTag("prov_key_ack"))
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (showSetupCode) {
        OutlinedTextField(
            value = setupCode,
            onValueChange = { onSetupCodeChange(it.trim().uppercase()) },
            label = { Text("Setup code (ESP8266 without a key)") },
            singleLine = true,
            supportingText = { Text("8 characters. Leave empty for a board that already has a key.") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("prov_setup_code")
                .semantics { contentDescription = "Setup code" },
        )
    }
}
