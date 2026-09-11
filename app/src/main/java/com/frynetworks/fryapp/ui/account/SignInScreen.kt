package com.frynetworks.fryapp.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.auth.SignInStep
import com.frynetworks.fryapp.wallet.WalletVendor

/**
 * Wallet sign-in: the only place the dashboard session is created. The user picks Pera or
 * Defly, approves the connection and the nonce transaction in the wallet app, and lands back
 * here signed in. Never shows a web page.
 */
@Composable
fun SignInScreen(state: SignInUiState, viewModel: SignInViewModel, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Text("Sign in with your wallet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Manage every miner registered to your wallet: keys, rewards, stakes and activity. " +
                "You approve one connection and one zero-value signature in your wallet app.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))

        for (vendor in WalletVendor.entries) {
            val installed = vendor in state.installed
            Button(
                onClick = { viewModel.connect(vendor) },
                enabled = installed && !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("signin_connect_${vendor.id}")
                    .semantics { contentDescription = "Connect ${vendor.displayName}" },
            ) {
                Text(if (installed) "Connect ${vendor.displayName}" else "${vendor.displayName} not installed")
            }
            Spacer(Modifier.height(8.dp))
        }

        val step = (state.session as? SessionState.SigningIn)?.step
        if (state.busy || step != null) {
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator(modifier = Modifier.testTag("signin_progress"))
            Spacer(Modifier.height(8.dp))
            Text(
                text = stepLabel(step),
                modifier = Modifier.testTag("signin_status").semantics { contentDescription = "Sign-in status" },
            )
            if (step == SignInStep.ConnectingWallet || step == SignInStep.AwaitingSignature) {
                OutlinedButton(
                    onClick = { viewModel.openWallet() },
                    enabled = state.lastWalletUri != null,
                    modifier = Modifier.testTag("signin_open_wallet"),
                ) { Text("Open wallet app") }
            }
        }

        state.needsProfileFor?.let { address ->
            Spacer(Modifier.height(16.dp))
            ProfileForm(address = address, busy = state.busy, viewModel = viewModel)
        }

        state.error?.let { error ->
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth().testTag("signin_error")) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Sign-in failed (${error.code})", style = MaterialTheme.typography.titleSmall)
                    Text(error.message, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { viewModel.dismissError() }, modifier = Modifier.testTag("signin_error_dismiss")) { Text("Dismiss") }
                }
            }
        }
    }
}

@Composable
private fun ProfileForm(address: String, busy: Boolean, viewModel: SignInViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var firstName by rememberSaveable { mutableStateOf("") }
    var lastName by rememberSaveable { mutableStateOf("") }
    val valid = email.contains("@") && firstName.isNotBlank() && lastName.isNotBlank()
    Card(modifier = Modifier.fillMaxWidth().testTag("signin_profile_form")) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("New wallet on the dashboard", style = MaterialTheme.typography.titleSmall)
            Text("Wallet ${address.take(6)}…${address.takeLast(6)} has no dashboard profile yet. Add your details to create one.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("signin_email"))
            OutlinedTextField(value = firstName, onValueChange = { firstName = it }, label = { Text("First name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("signin_first_name"))
            OutlinedTextField(value = lastName, onValueChange = { lastName = it }, label = { Text("Last name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("signin_last_name"))
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.submitProfile(email, firstName, lastName) },
                enabled = valid && !busy,
                modifier = Modifier.testTag("signin_submit"),
            ) { Text("Create profile and sign in") }
            TextButton(onClick = { viewModel.cancelProfile() }, modifier = Modifier.testTag("signin_profile_cancel")) { Text("Cancel") }
        }
    }
}

private fun stepLabel(step: SignInStep?): String = when (step) {
    SignInStep.ConnectingWallet -> "Approve the connection in your wallet app…"
    SignInStep.CheckingUser -> "Checking your dashboard account…"
    SignInStep.BuildingProof -> "Preparing the sign-in transaction…"
    SignInStep.AwaitingSignature -> "Approve the zero-value sign-in transaction in your wallet…"
    SignInStep.Authenticating -> "Signing in to the Fry Dashboard…"
    SignInStep.BindingDevice -> "Binding this device to your session…"
    SignInStep.Verifying -> "Verifying the session…"
    null -> "Working…"
}
