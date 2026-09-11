package com.frynetworks.fryapp.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle

/** Puts [text] on the system clipboard. Miner keys and addresses only — never credentials. */
fun copyToClipboard(context: Context, label: String, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Opens [url] in whatever handles it (browser); swallows the no-activity case. */
fun openExternalUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Selectable text with a trailing copy button; [textTag]/[copyTag] are the two test tags. */
@Composable
fun CopyableText(
    text: String,
    label: String,
    textTag: String,
    copyTag: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            text = text,
            style = style,
            modifier = Modifier.weight(1f).testTag(textTag).semantics { contentDescription = label },
        )
        IconButton(
            onClick = { copyToClipboard(context, label, text) },
            modifier = Modifier.testTag(copyTag).semantics { contentDescription = "Copy $label" },
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null)
        }
    }
}
