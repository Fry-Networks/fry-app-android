package com.frynetworks.fryapp.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.domain.MicroAmount
import com.frynetworks.fryapp.wallet.TxnSummary

private const val ADDRESS_EDGE_CHARS = 6

fun shortAddress(address: String?): String {
    if (address.isNullOrBlank()) return "—"
    if (address.length <= ADDRESS_EDGE_CHARS * 2) return address
    return "${address.take(ADDRESS_EDGE_CHARS)}…${address.takeLast(ADDRESS_EDGE_CHARS)}"
}

/** Human line for a decoded transaction: `Pay 0.001 ALGO to HXWYLL…VIW6WU`. */
fun describeTxn(summary: TxnSummary): String {
    val asset = summary.assetId?.let { FryAsset.fromId(it) }
    val amount = summary.amount
    return when (summary.type) {
        "pay" -> "Pay ${amount?.let { MicroAmount(it).format(maxFraction = 6) } ?: "0"} ALGO to ${shortAddress(summary.receiver)}"
        "axfer" -> if (amount == 0L && summary.sender == summary.receiver) {
            "Opt in to ${asset?.symbol ?: "asset ${summary.assetId}"}"
        } else {
            "Send ${amount?.let { MicroAmount(it).format(maxFraction = 6) } ?: "0"} ${asset?.symbol ?: "asset ${summary.assetId}"} to ${shortAddress(summary.receiver)}"
        }
        else -> "${summary.type} transaction from ${shortAddress(summary.sender)}"
    }
}

/**
 * "Continue in your wallet" progress card shown while a signature is pending: the native
 * "You are about to sign" summary, a spinner, and an "Open wallet" button that re-fires the
 * last deep link. [tagPrefix] is `claim` or `stake`.
 */
@Composable
fun WalletPrompt(
    summary: TxnSummary?,
    canOpenWallet: Boolean,
    onOpenWallet: () -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    title: String = "Continue in your wallet app",
) {
    Card(modifier = modifier.fillMaxWidth().testTag("${tagPrefix}_wallet_prompt")) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            if (summary != null) {
                Spacer(Modifier.height(8.dp))
                Text("You are about to sign:", style = MaterialTheme.typography.labelMedium)
                Text(
                    describeTxn(summary),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("${tagPrefix}_txn_summary"),
                )
                summary.note?.takeIf { it.isNotBlank() }?.let {
                    Text("Note: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenWallet,
                enabled = canOpenWallet,
                modifier = Modifier.testTag("${tagPrefix}_open_wallet").semantics { contentDescription = "Open wallet app" },
            ) { Text("Open wallet app") }
        }
    }
}
