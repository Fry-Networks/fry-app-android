package com.frynetworks.fryapp.ui.provision

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        val current = state
        if (current is ProvisionUiState.Success) onDone(current.minerKey)
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
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.submit(address, transport, ssid, pass, wallet) },
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
            }
            Text(
                text = statusText,
                modifier = Modifier
                    .testTag("prov_status")
                    .semantics { contentDescription = "Provisioning status" },
            )

            val minerKeyText = (state as? ProvisionUiState.Success)?.minerKey.orEmpty()
            if (minerKeyText.isNotEmpty()) {
                Text(
                    text = minerKeyText,
                    modifier = Modifier
                        .testTag("prov_minerkey")
                        .semantics { contentDescription = "Miner key" },
                )
            }
        }
    }
}
