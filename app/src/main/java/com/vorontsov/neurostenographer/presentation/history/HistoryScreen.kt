package com.vorontsov.neurostenographer.presentation.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vorontsov.neurostenographer.R
import com.vorontsov.neurostenographer.domain.model.Recording
import com.vorontsov.neurostenographer.presentation.common.EmptyState
import com.vorontsov.neurostenographer.presentation.common.RecordingRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenRecording: (Long) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    var menuForRecordingId by remember { mutableStateOf<Long?>(null) }
    var renamingRecording by remember { mutableStateOf<Recording?>(null) }
    var deletingRecording by remember { mutableStateOf<Recording?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                HistoryEvent.Renamed -> R.string.history_renamed
                HistoryEvent.RenameFailed -> R.string.history_rename_failed
                HistoryEvent.Deleted -> R.string.history_deleted
                HistoryEvent.DeleteFailed -> R.string.history_delete_failed
                HistoryEvent.ReprocessStarted -> R.string.history_reprocess_started
                HistoryEvent.ReprocessBusy -> R.string.history_reprocess_busy
                HistoryEvent.ReprocessUnavailable -> R.string.history_reprocess_unavailable
            }
            snackbarHostState.showSnackbar(resources.getString(message))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.history_search_hint)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_clear),
                                )
                            }
                        }
                    },
                )
            }

            item(key = "count") {
                Text(
                    text = if (query.isBlank()) {
                        stringResource(R.string.history_all_recordings)
                    } else {
                        stringResource(R.string.history_results)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (recordings.isEmpty()) {
                item(key = "empty") {
                    if (query.isBlank()) {
                        EmptyState(
                            title = stringResource(R.string.history_empty_title),
                            body = stringResource(R.string.history_empty_body),
                        )
                    } else {
                        EmptyState(
                            title = stringResource(R.string.history_empty_search),
                            body = stringResource(R.string.history_empty_body),
                        )
                    }
                }
            } else {
                items(items = recordings, key = { recording -> recording.id }) { recording ->
                    RecordingRow(
                        recording = recording,
                        onClick = { onOpenRecording(recording.id) },
                        trailing = {
                            Box {
                                IconButton(onClick = { menuForRecordingId = recording.id }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.action_more),
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuForRecordingId == recording.id,
                                    onDismissRequest = { menuForRecordingId = null },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.history_rename)) },
                                        onClick = {
                                            menuForRecordingId = null
                                            renamingRecording = recording
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.history_reprocess)) },
                                        onClick = {
                                            menuForRecordingId = null
                                            viewModel.reprocess(recording.id)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.history_delete)) },
                                        onClick = {
                                            menuForRecordingId = null
                                            deletingRecording = recording
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }

            item(key = "bottom_spacer") {
                Box(modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }

    val recordingToRename = renamingRecording
    if (recordingToRename != null) {
        RenameRecordingDialog(
            recording = recordingToRename,
            onDismiss = { renamingRecording = null },
            onSave = { title ->
                viewModel.rename(recordingToRename.id, title)
                renamingRecording = null
            },
        )
    }

    val recordingToDelete = deletingRecording
    if (recordingToDelete != null) {
        AlertDialog(
            onDismissRequest = { deletingRecording = null },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(recordingToDelete.id)
                        deletingRecording = null
                    },
                ) {
                    Text(stringResource(R.string.history_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingRecording = null }) {
                    Text(stringResource(R.string.history_cancel))
                }
            },
        )
    }
}

@Composable
private fun RenameRecordingDialog(
    recording: Recording,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var title by remember(recording.id) { mutableStateOf(recording.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_rename)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text(stringResource(R.string.history_rename_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title) },
                enabled = title.isNotBlank(),
            ) {
                Text(stringResource(R.string.speakers_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.history_cancel))
            }
        },
    )
}
