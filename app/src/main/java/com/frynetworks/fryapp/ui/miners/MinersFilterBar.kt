package com.frynetworks.fryapp.ui.miners

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.frynetworks.fryapp.data.dashboard.repo.MinerStatus
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.ui.common.label
import com.frynetworks.fryapp.ui.common.tagToken

private val STATUS_CHIPS = listOf(
    MinerStatus.ACTIVE, MinerStatus.PENDING, MinerStatus.UNREGISTERED, MinerStatus.MIGRATED, MinerStatus.NOT_ON_DASHBOARD,
)

/** Search field, status chips, family chips and the sort menu for the Miners tab. */
@Composable
fun MinersFilterBar(
    query: String,
    statusFilter: MinerStatus?,
    familyFilter: MinerFamily?,
    families: List<MinerFamily>,
    sort: MinerSort,
    onQuery: (String) -> Unit,
    onToggleStatus: (MinerStatus) -> Unit,
    onToggleFamily: (MinerFamily) -> Unit,
    onSort: (MinerSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                label = { Text("Search miners") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("miners_search")
                    .semantics { contentDescription = "Search miners" },
            )
            SortMenu(sort = sort, onSort = onSort)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            STATUS_CHIPS.forEach { status ->
                FilterChip(
                    selected = statusFilter == status,
                    onClick = { onToggleStatus(status) },
                    label = { Text(status.label()) },
                    modifier = Modifier
                        .testTag("miners_filter_${status.tagToken()}")
                        .semantics { contentDescription = "Filter ${status.label()}" },
                )
            }
        }
        if (families.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                families.forEach { family ->
                    FilterChip(
                        selected = familyFilter == family,
                        onClick = { onToggleFamily(family) },
                        label = { Text(family.label) },
                        modifier = Modifier
                            .testTag("miners_filter_${family.name}")
                            .semantics { contentDescription = "Filter ${family.label}" },
                    )
                }
            }
        }
    }
}

@Composable
private fun SortMenu(sort: MinerSort, onSort: (MinerSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.testTag("miners_sort").semantics { contentDescription = "Sort by ${sort.label}" },
        ) {
            Icon(Icons.Filled.Sort, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MinerSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option == sort) "• ${option.label}" else option.label) },
                    onClick = {
                        open = false
                        onSort(option)
                    },
                    modifier = Modifier.testTag("miners_sort_${option.name.lowercase()}"),
                )
            }
        }
    }
    Spacer(Modifier.padding(2.dp))
}
