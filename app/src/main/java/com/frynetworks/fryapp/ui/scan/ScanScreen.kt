package com.frynetworks.fryapp.ui.scan

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ScanScreen(
    onDeviceChosen: (address: String, transport: String) -> Unit,
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val results by viewModel.results.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val hasScanned by viewModel.hasScanned.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val requiredPermissions = remember { scanPermissions() }
    var hasPermissions by remember { mutableStateOf(hasAllPermissions(context, requiredPermissions)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasPermissions = grants.values.all { it }
        if (hasPermissions) {
            viewModel.startScan()
        } else {
            // A partial grant used to leave the button silently inert. On Android 12+ denying
            // Location alone is enough to make every scan return nothing at all.
            viewModel.onPermissionsDenied(grants.filterValues { !it }.keys.toList())
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Scan for devices") }) }) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Button(
                onClick = {
                    if (hasPermissions) viewModel.startScan() else permissionLauncher.launch(requiredPermissions)
                },
                modifier = Modifier
                    .padding(16.dp)
                    .testTag("scan_start")
                    .semantics { contentDescription = "Start scan" },
            ) {
                Text(if (scanning) "Scanning..." else "Start scan")
            }
            error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp)
                        .testTag("scan_error")
                        .semantics { contentDescription = "Scan error: $message" },
                )
            }

            if (!scanning && hasScanned && results.isEmpty() && error == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("scan_empty"),
                ) {
                    Text("No Fry devices found.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.padding(4.dp))
                    Text(
                        "Check the board is powered and within a few metres of this phone.\n\n" +
                            "If it was set up before, it is already on a Wi-Fi network and will not " +
                            "advertise. Hold the BOOT button for 10 seconds to factory reset it — " +
                            "the board keeps its miner key — then scan again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(results) { index, device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceChosen(device.address, device.transport) }
                            .testTag("scan_result_$index")
                            .semantics { contentDescription = "Scan result ${device.label}" }
                            .padding(16.dp),
                    ) {
                        Text(
                            text = "${device.label} (${device.transport})",
                            modifier = Modifier.testTag("scan_result_name_$index"),
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
