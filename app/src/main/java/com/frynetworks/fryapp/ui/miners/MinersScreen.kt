package com.frynetworks.fryapp.ui.miners

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.ui.account.SignInScreen
import com.frynetworks.fryapp.ui.account.SignInViewModel

/**
 * "Miners" tab: every miner registered to the signed-in wallet, of every type. Renders the
 * wallet sign-in inline until a dashboard session exists.
 */
@Composable
fun MinersScreen(
    signInViewModel: SignInViewModel = hiltViewModel(),
) {
    val signIn by signInViewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("My Miners") }) }) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).testTag("miners_root")) {
            when (val session = signIn.session) {
                is SessionState.SignedIn -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Signed in", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = session.profile.address,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("account_address"),
                            )
                            session.profile.vendor?.let { Text("via ${it.displayName}", style = MaterialTheme.typography.labelSmall) }
                        }
                        TextButton(onClick = { signInViewModel.signOut() }, modifier = Modifier.testTag("account_signout")) { Text("Sign out") }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Miner list, totals, keys, rewards and activity attach here (feature work in progress).
                    Text(
                        text = "Loading your miners…",
                        modifier = Modifier.padding(16.dp).testTag("miners_placeholder"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> SignInScreen(state = signIn, viewModel = signInViewModel)
            }
        }
    }
}
