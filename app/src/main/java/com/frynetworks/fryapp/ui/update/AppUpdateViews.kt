package com.frynetworks.fryapp.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.frynetworks.fryapp.BuildConfig
import com.frynetworks.fryapp.update.UpdateChannel
import com.frynetworks.fryapp.update.UpdateCoordinator
import com.frynetworks.fryapp.update.UpdatePrefs
import com.frynetworks.fryapp.update.UpdateState
import com.frynetworks.fryapp.update.UpdateTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val coordinator: UpdateCoordinator,
    private val prefs: UpdatePrefs,
) : ViewModel() {
    val state: StateFlow<UpdateState> = coordinator.state

    val channel: UpdateChannel get() = coordinator.channel()

    val lastInstallMessage: String? get() = prefs.lastInstallMessage

    fun checkNow() {
        viewModelScope.launch(Dispatchers.IO) { coordinator.check(UpdateTrigger.MANUAL) }
    }
}

/** What the Home banner says; null when there is nothing to tell. */
fun bannerText(state: UpdateState): String? = when (state) {
    is UpdateState.Downloading -> "Downloading app update ${state.versionName}…"
    is UpdateState.Installing -> "Installing app update ${state.versionName}. Confirm it if Android asks."
    is UpdateState.Deferred -> "App update ${state.versionName} is ready; installing ${state.reason}."
    is UpdateState.Failed -> "App update failed: ${state.reason}"
    UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate, is UpdateState.NotApplicable -> null
}

fun statusText(state: UpdateState): String = when (state) {
    UpdateState.Idle -> "Not checked yet."
    UpdateState.Checking -> "Checking for updates…"
    UpdateState.UpToDate -> "This is the latest version."
    is UpdateState.NotApplicable -> "This build does not update itself (${state.reason})."
    else -> bannerText(state).orEmpty()
}

@Composable
fun AppUpdateBanner(viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val text = bannerText(state) ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (state is UpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("home_update_banner")
            .semantics { contentDescription = "App update: $text" },
    )
}

@Composable
fun AppUpdateSection(viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxWidth().testTag("settings_updates")) {
        Text("App updates", style = MaterialTheme.typography.titleSmall)
        Text(
            "Channel: ${viewModel.channel.wire} · installed ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp).testTag("settings_update_channel"),
        )
        Text(statusText(state), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp).testTag("settings_update_status"))
        viewModel.lastInstallMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp).testTag("settings_update_last_install"))
        }
        OutlinedButton(
            onClick = viewModel::checkNow,
            enabled = state != UpdateState.Checking && state !is UpdateState.Downloading,
            modifier = Modifier.padding(top = 8.dp).testTag("settings_update_check").semantics { contentDescription = "Check for app updates" },
        ) { Text("Check for updates") }
    }
}
