package com.vorontsov.neurostenographer.presentation.speakers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vorontsov.neurostenographer.R
import com.vorontsov.neurostenographer.domain.model.Speaker
import com.vorontsov.neurostenographer.presentation.common.EmptyState
import com.vorontsov.neurostenographer.presentation.common.speakerColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeakersScreen(
    onBack: () -> Unit,
    viewModel: SpeakersViewModel = hiltViewModel(),
) {
    val speakers by viewModel.speakers.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val currentRecording = recording
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    var editingSpeaker by remember { mutableStateOf<Speaker?>(null) }
    var mergingSpeaker by remember { mutableStateOf<Speaker?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                SpeakerEvent.Renamed -> R.string.speakers_renamed
                SpeakerEvent.RenameFailed -> R.string.speakers_rename_failed
                SpeakerEvent.Merged -> R.string.speakers_merged
                SpeakerEvent.MergeFailed -> R.string.speakers_merge_failed
            }
            snackbarHostState.showSnackbar(resources.getString(message))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.speakers_title)) },
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
            if (currentRecording != null) {
                item(key = "recording") {
                    Text(
                        text = currentRecording.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (speakers.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = stringResource(R.string.speakers_empty_title),
                        body = stringResource(R.string.speakers_empty_body),
                    )
                }
            } else {
                items(items = speakers, key = { speaker -> speaker.id }) { speaker ->
                    SpeakerRow(
                        speaker = speaker,
                        onRename = { editingSpeaker = speaker },
                        onMerge = { mergingSpeaker = speaker },
                    )
                }
            }

            item(key = "merge_note") {
                Text(
                    text = stringResource(R.string.speakers_merge_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    val speakerBeingEdited = editingSpeaker
    if (speakerBeingEdited != null) {
        RenameSpeakerDialog(
            speaker = speakerBeingEdited,
            onDismiss = { editingSpeaker = null },
            onSave = { name ->
                viewModel.rename(speakerBeingEdited.id, name)
                editingSpeaker = null
            },
        )
    }

    val speakerBeingMerged = mergingSpeaker
    if (speakerBeingMerged != null) {
        MergeSpeakerDialog(
            source = speakerBeingMerged,
            targets = speakers.filter { it.id != speakerBeingMerged.id },
            onDismiss = { mergingSpeaker = null },
            onMergeInto = { target ->
                viewModel.merge(speakerBeingMerged.id, target.id)
                mergingSpeaker = null
            },
        )
    }
}

@Composable
private fun MergeSpeakerDialog(
    source: Speaker,
    targets: List<Speaker>,
    onDismiss: () -> Unit,
    onMergeInto: (Speaker) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.speakers_merge_title, source.speakerKey))
        },
        text = {
            if (targets.isEmpty()) {
                Text(stringResource(R.string.speakers_merge))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    targets.forEach { target ->
                        TextButton(onClick = { onMergeInto(target) }) {
                            Text(
                                target.displayName?.takeIf { it.isNotBlank() }
                                    ?: target.speakerKey,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.speakers_cancel))
            }
        },
    )
}

@Composable
private fun SpeakerRow(
    speaker: Speaker,
    onRename: () -> Unit,
    onMerge: () -> Unit,
) {
    val accent = speakerColor(speaker.speakerKey)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(14.dp),
                shape = CircleShape,
                color = accent,
            ) {}
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = speaker.displayName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.speakers_unnamed),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = speaker.speakerKey,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onMerge) {
                Text(
                    text = stringResource(R.string.speakers_merge),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            IconButton(onClick = onRename) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.speakers_rename),
                )
            }
        }
    }
}

@Composable
private fun RenameSpeakerDialog(
    speaker: Speaker,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var name by remember(speaker.id) { mutableStateOf(speaker.displayName.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speakers_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.speakers_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim().ifBlank { null }) }) {
                Text(stringResource(R.string.speakers_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.speakers_cancel))
            }
        },
    )
}
