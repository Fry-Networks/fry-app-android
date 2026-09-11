package com.frynetworks.fryapp.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay

private const val TICK_MILLIS = 1_000L

/**
 * `"<prefix> 2h 5m"` ticking once a second until [targetMillis] (server-clock epoch millis)
 * passes, then `"<prefix> now"`. Renders nothing when [targetMillis] is null.
 */
@Composable
fun CountdownText(
    targetMillis: Long?,
    now: () -> Long,
    prefix: String,
    tag: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
) {
    if (targetMillis == null) return
    var remaining by remember(targetMillis) { mutableLongStateOf(targetMillis - now()) }
    LaunchedEffect(targetMillis) {
        while (true) {
            remaining = targetMillis - now()
            if (remaining <= 0) break
            delay(TICK_MILLIS)
        }
    }
    Text(
        text = "$prefix ${TimeFormat.duration(remaining)}",
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.testTag(tag),
    )
}
