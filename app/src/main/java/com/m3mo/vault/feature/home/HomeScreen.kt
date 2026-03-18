package com.m3mo.vault.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.MemoryType
import com.m3mo.vault.ui.component.EmptyState
import com.m3mo.vault.ui.component.LoadingIndicator
import com.m3mo.vault.ui.component.MemoryCard
import com.m3mo.vault.util.UiState


/**
 * HomeScreen - Main screen showing all memories.
 *
 * Features:
 * - Memory list sorted by weight (highest first)
 * - Type filter chips (All / Fact / Episode / Task)
 * - Stats summary in header
 * - FAB to add new memory
 * - Swipe-to-delete with snackbar feedback
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToAdd: () -> Unit,
    onNavigateToEdit: (Long) -> Unit,
    onNavigateToSearch: () -> Unit
) {

    val uiState by viewModel.uiState.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val deleteResult by viewModel.deleteResult.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // Handle delete feedback
    LaunchedEffect(deleteResult) {
        when (val result = deleteResult) {
            is DeleteResult.Success -> {
                snackbarHostState.showSnackbar("Memory deleted")
                viewModel.clearDeleteResult()
            }
            is DeleteResult.Error -> {
                snackbarHostState.showSnackbar("Delete failed: ${result.message}")
                viewModel.clearDeleteResult()
            }
            null -> { /* no-op */ }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "M3mo Vault",
                            fontWeight = FontWeight.Bold
                        )
                        if (stats.total > 0) {
                            Text(
                                "${stats.total} memories | avg W:${"%.1f".format(stats.avgWeight)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add Memory")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Filter chips
            FilterChipRow(
                selectedFilter = selectedFilter,
                stats = stats,
                onFilterSelected = { viewModel.setFilter(it) }
            )

            // Content
            when (val state = uiState) {
                is UiState.Loading -> LoadingIndicator()
                is UiState.Empty -> EmptyState()
                is UiState.Error -> EmptyState(
                    title = "Something went wrong",
                    subtitle = state.message
                )
                is UiState.Success -> MemoryList(
                    memories = state.data,
                    onItemClick = { onNavigateToEdit(it.id) },
                    onItemDelete = { viewModel.deleteMemory(it.id) }
                )
            }
        }
    }
}

@Composable
private fun FilterChipRow(
    selectedFilter: MemoryType?,
    stats: HomeStats,
    onFilterSelected: (MemoryType?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // All filter
        FilterChip(
            selected = selectedFilter == null,
            onClick = { onFilterSelected(null) },
            label = { Text("All (${stats.total})") }
        )

        // Type filters
        FilterChip(
            selected = selectedFilter == MemoryType.FACT,
            onClick = { onFilterSelected(MemoryType.FACT) },
            label = { Text("${MemoryType.FACT.emoji} Facts (${stats.facts})") }
        )
        FilterChip(
            selected = selectedFilter == MemoryType.EPISODE,
            onClick = { onFilterSelected(MemoryType.EPISODE) },
            label = { Text("${MemoryType.EPISODE.emoji} Episodes (${stats.episodes})") }
        )
        FilterChip(
            selected = selectedFilter == MemoryType.TASK,
            onClick = { onFilterSelected(MemoryType.TASK) },
            label = { Text("${MemoryType.TASK.emoji} Tasks (${stats.tasks})") }
        )
    }
}

@Composable
private fun MemoryList(
    memories: List<MemoryItem>,
    onItemClick: (MemoryItem) -> Unit,
    onItemDelete: (MemoryItem) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = memories,
            key = { it.id }
        ) { memory ->
            MemoryCard(
                memory = memory,
                onClick = { onItemClick(memory) },
                onDelete = { onItemDelete(memory) }
            )
        }
        // Bottom padding for FAB clearance
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}
