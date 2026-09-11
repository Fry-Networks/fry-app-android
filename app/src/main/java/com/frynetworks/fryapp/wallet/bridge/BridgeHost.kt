package com.frynetworks.fryapp.wallet.bridge

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Keeps the bridge WebView attached to the view hierarchy for the Activity's lifetime. It is
 * 1 dp and nearly transparent rather than GONE so Chromium keeps its timers and WebSocket alive
 * while the user is over in Pera/Defly approving a request.
 */
@Composable
fun BridgeHost(bridge: WalletBridgeWebView) {
    AndroidView(
        factory = { bridge.attach() },
        modifier = Modifier.size(1.dp).alpha(0.01f).testTag("wallet_bridge_host"),
    )
}
