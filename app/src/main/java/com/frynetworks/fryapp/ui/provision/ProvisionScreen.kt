package com.frynetworks.fryapp.ui.provision

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.frynetworks.fryapp.data.Transport
import com.frynetworks.fryapp.domain.MinerKeyFormat
import com.frynetworks.fryapp.ui.common.CopyableText
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ProvisionScreen(
    address: String,
    transport: String,
    onDone: (minerKey: String) -> Unit,
    viewModel: ProvisionViewModel = hiltViewModel(),
) {
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var wallet by remember { mutableStateOf(viewModel.defaultWallet()) }
    var minerKey by remember { mutableStateOf("") }
    var setupCode by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyCheck by viewModel.keyCheck.collectAsStateWithLifecycle()
    val keyNotice by viewModel.keyNotice.collectAsStateWithLifecycle()

    LaunchedEffect(state, keyNotice) {
        val current = state
        // A board that kept its own key: stay here so the user can copy it first.
        if (current is ProvisionUiState.Success && keyNotice == null) onDone(current.minerKey)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Provision device") }) }) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it },
                label = { Text("Wi-Fi network name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("prov_ssid")
                    .semantics { contentDescription = "Wi-Fi network name" },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("Wi-Fi password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("prov_pass")
                    .semantics { contentDescription = "Wi-Fi password" },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = wallet,
                onValueChange = { wallet = it },
                label = { Text("Algorand wallet address") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("prov_wallet")
                    .semantics { contentDescription = "Algorand wallet address" },
            )
            Spacer(Modifier.height(8.dp))
            KeyStepSection(
                minerKey = minerKey,
                onMinerKeyChange = { minerKey = it; acknowledged = false },
                setupCode = setupCode,
                onSetupCodeChange = { setupCode = it },
                showSetupCode = transport == Transport.SOFTAP,
                keyCheck = keyCheck,
                acknowledged = acknowledged,
                onAcknowledgedChange = { acknowledged = it },
                onCheckKey = { viewModel.checkKey(it) },
                onClearCheck = { viewModel.clearKeyCheck() },
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.submit(address, transport, ssid, pass, wallet, KeyStep(minerKey, setupCode, acknowledged)) },
                modifier = Modifier
                    .testTag("prov_submit")
                    .semantics { contentDescription = "Submit provisioning" },
            ) {
                Text("Provision")
            }
            Spacer(Modifier.height(16.dp))

            val statusText = when (val current = state) {
                is ProvisionUiState.Idle -> ""
                is ProvisionUiState.InProgress -> current.message
                is ProvisionUiState.Success -> "Connected"
                is ProvisionUiState.Error -> "Error: ${current.reason}"
                is ProvisionUiState.Handoff -> current.message
            }
            Text(
                text = statusText,
                modifier = Modifier
                    .testTag("prov_status")
                    .semantics { contentDescription = "Provisioning status" },
            )

            val minerKeyText = when (val current = state) {
                is ProvisionUiState.Success -> current.minerKey
                is ProvisionUiState.Handoff -> current.minerKey
                else -> ""
            }
            if (minerKeyText.isNotEmpty()) {
                Text(
                    text = minerKeyText,
                    modifier = Modifier
                        .testTag("prov_minerkey")
                        .semantics { contentDescription = "Miner key" },
                )
            }
            keyNotice?.let { notice ->
                Spacer(Modifier.height(8.dp))
                Text(notice.message, modifier = Modifier.testTag("prov_key_notice"))
                CopyableText(text = notice.boardKey, label = "Board miner key", textTag = "prov_board_key", copyTag = "prov_board_key_copy")
                (state as? ProvisionUiState.Success)?.let { success ->
                    Button(onClick = { onDone(success.minerKey) }, modifier = Modifier.testTag("prov_continue")) { Text("Continue") }
                }
            }
            // A masked key (FEM-AB…) names no device on this phone, so there is nothing to open.
            (state as? ProvisionUiState.Handoff)?.takeUnless { MinerKeyFormat.isMasked(it.minerKey) }?.let { handoff ->
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onDone(handoff.minerKey) },
                    modifier = Modifier.testTag("prov_open_device"),
                ) { Text("Open device") }
            }
        }
    }
}
