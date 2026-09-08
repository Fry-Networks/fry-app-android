package com.frynetworks.fryapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.compose.rememberNavController
import com.frynetworks.fryapp.ui.nav.FryNavHost
import com.frynetworks.fryapp.ui.theme.FryTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FryAppRoot()
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FryAppRoot() {
    FryTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // Surfaces every testTag as a bare resource-id (no package prefix) so an
                // instrumentation/UI-automator script can find nodes by the exact names in
                // PROTOCOL.md section 10.
                .semantics { testTagsAsResourceId = true },
        ) {
            val navController = rememberNavController()
            FryNavHost(navController = navController)
        }
    }
}
