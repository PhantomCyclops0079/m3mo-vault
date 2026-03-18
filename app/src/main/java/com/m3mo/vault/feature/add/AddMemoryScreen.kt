package com.m3mo.vault.feature.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.m3mo.vault.domain.model.MemoryType

/**
 * AddMemoryScreen - UI for creating new memories or editing existing ones.
 *
 * Layout:
 * - Top bar with back + save buttons
 * - Type selector (FACT / EPISODE / TASK)
 * - Content text field (auto-focused)
 *
 * @param editMemoryId If non-null, enters edit mode and loads existing memory
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMemoryScreen(
    viewModel: AddMemoryViewModel,
    editMemoryId: Long?,
    onNavigateBack: () -> Unit
) {
    val content by viewModel.content.collectAsState()
    val selectedType by viewModel.selectedType.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val saveResult by viewModel.saveResult.collectAsState()
    val isEditMode by viewModel.isEditMode.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }

    // Load existing memory if in edit mode
    LaunchedEffect(editMemoryId) {
        if (editMemoryId != null) {
            viewModel.loadForEdit(editMemoryId)
        }
    }

    // Auto-focus content field
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Handle save result
    LaunchedEffect(saveResult) {
        when (val result = saveResult) {
            is SaveResult.Success -> {
                onNavigateBack()
                viewModel.clearSaveResult()
            }
            is SaveResult.Error -> {
                snackbarHostState.showSnackbar(result.message)
                viewModel.clearSaveResult()
            }
            null -> { /* no-op */ }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (isEditMode) "Edit Memory" else "New Memory")
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save() },
                        enabled = content.isNotBlank() && !isSaving
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Text(
                            text = if (isSaving) "Saving..." else "Save",
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Type selector
            Text(
                text = "Type",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            TypeSelector(
                selectedType = selectedType,
                onTypeSelected = { viewModel.selectType(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Content input
            OutlinedTextField(
                value = content,
                onValueChange = { viewModel.updateContent(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .focusRequester(focusRequester),
                label = { Text("What do you want to remember?") },
                placeholder = {
                    Text(
                        when (selectedType) {
                            MemoryType.FACT -> "e.g., Mom's birthday is January 5th"
                            MemoryType.EPISODE -> "e.g., Had dinner with Alex at the new Italian place"
                            MemoryType.TASK -> "e.g., Call dentist tomorrow at 10am"
                        }
                    )
                },
                maxLines = 10,
                supportingText = {
                    Text("${content.length} characters")
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Type description
            Text(
                text = when (selectedType) {
                    MemoryType.FACT -> "Facts are things you know — names, dates, preferences. They tend to stay stable."
                    MemoryType.EPISODE -> "Episodes are things that happened — events, conversations, experiences."
                    MemoryType.TASK -> "Tasks are things to do — reminders, action items, follow-ups."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun TypeSelector(
    selectedType: MemoryType,
    onTypeSelected: (MemoryType) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MemoryType.entries.forEach { type ->
            FilterChip(
                selected = selectedType == type,
                onClick = { onTypeSelected(type) },
                label = { Text("${type.emoji} ${type.displayName}") },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
