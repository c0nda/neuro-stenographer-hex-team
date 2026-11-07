package com.vorontsov.neurostenographer.presentation.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vorontsov.neurostenographer.R
import com.vorontsov.neurostenographer.domain.model.CaptureStatus
import com.vorontsov.neurostenographer.domain.model.ModelState
import com.vorontsov.neurostenographer.domain.model.ModelStatus
import com.vorontsov.neurostenographer.presentation.common.EmptyState
import com.vorontsov.neurostenographer.presentation.common.RecordingRow
import com.vorontsov.neurostenographer.presentation.common.formatBytes
import com.vorontsov.neurostenographer.presentation.common.formatClock
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartRecording: () -> Unit,
    onOpenRecording: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val vadModelStatus by viewModel.vadModelStatus.collectAsStateWithLifecycle()
    val diarizationModelStatus by viewModel.diarizationModelStatus.collectAsStateWithLifecycle()

    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) viewModel.import(uri)
    }

    val modelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }

    val vadModelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importVadModel(uri)
    }

    val segmentationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importDiarizationSegmentationModel(uri)
    }

    val embeddingLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importDiarizationEmbeddingModel(uri)
    }

    val importing = importState is ImportUiState.Importing
    val recordingActive = session.status == CaptureStatus.RECORDING ||
        session.status == CaptureStatus.PAUSED ||
        session.status == CaptureStatus.STOPPING

    LaunchedEffect(Unit) {
        viewModel.modelEvents.collect { event ->
            val message = when (event) {
                ModelEvent.Imported -> resources.getString(R.string.home_model_imported)
                is ModelEvent.ImportFailed -> resources.getString(
                    R.string.home_model_import_failed,
                    event.message,
                )

                ModelEvent.VadImported -> resources.getString(R.string.home_vad_model_imported)
                is ModelEvent.VadImportFailed -> resources.getString(
                    R.string.home_vad_model_import_failed,
                    event.message,
                )

                ModelEvent.DiarizationSegmentationImported -> resources.getString(
                    R.string.home_diarization_segmentation_imported,
                )

                ModelEvent.DiarizationEmbeddingImported -> resources.getString(
                    R.string.home_diarization_embedding_imported,
                )

                is ModelEvent.DiarizationImportFailed -> resources.getString(
                    R.string.home_diarization_import_failed,
                    event.message,
                )
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(importState) {
        when (val state = importState) {
            is ImportUiState.Success -> {
                viewModel.consumeImportState()
                onOpenRecording(state.recordingId)
            }

            is ImportUiState.Error -> {
                viewModel.consumeImportState()
                snackbarHostState.showSnackbar(
                    resources.getString(R.string.home_import_failed, state.message),
                )
            }

            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.home_history),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.home_offline_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onStartRecording,
                        modifier = Modifier.weight(1f),
                        enabled = !importing,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_start_recording))
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch("audio/*") },
                        modifier = Modifier.weight(1f),
                        enabled = !importing,
                    ) {
                        Text(stringResource(R.string.home_import_audio))
                    }
                }
            }

            item {
                ModelCard(
                    title = stringResource(R.string.home_model_title),
                    status = modelStatus,
                    verificationNote = stringResource(R.string.home_model_verification_note),
                    notInstalledNote = stringResource(R.string.home_model_not_installed),
                    onImport = { modelLauncher.launch(arrayOf("*/*")) },
                )
            }

            item {
                ModelCard(
                    title = stringResource(R.string.home_vad_model_title),
                    status = vadModelStatus,
                    verificationNote = stringResource(R.string.home_vad_model_verification_note),
                    notInstalledNote = stringResource(R.string.home_vad_model_not_installed),
                    onImport = { vadModelLauncher.launch(arrayOf("*/*")) },
                )
            }

            item {
                DiarizationModelCard(
                    status = diarizationModelStatus,
                    onImportSegmentation = { segmentationLauncher.launch(arrayOf("*/*")) },
                    onImportEmbedding = { embeddingLauncher.launch(arrayOf("*/*")) },
                )
            }

            if (importing) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.home_importing),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            if (recordingActive) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.home_active_recording),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = formatClock(session.elapsedMs),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            TextButton(onClick = onStartRecording) {
                                Text(stringResource(R.string.home_open_recording))
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.home_recent),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenHistory) {
                        Text(stringResource(R.string.home_history))
                    }
                }
            }

            if (recordings.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.home_empty_title),
                        body = stringResource(R.string.home_empty_body),
                    )
                }
            } else {
                items(
                    items = recordings.take(RECENT_LIMIT),
                    key = { recording -> recording.id },
                ) { recording ->
                    RecordingRow(
                        recording = recording,
                        onClick = { onOpenRecording(recording.id) },
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

private const val RECENT_LIMIT = 5

@Composable
private fun DiarizationModelCard(
    status: ModelStatus,
    onImportSegmentation: () -> Unit,
    onImportEmbedding: () -> Unit,
) {
    val hasError = status.state == ModelState.INVALID || status.state == ModelState.ERROR
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.home_diarization_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = when (status.state) {
                    ModelState.NOT_INSTALLED -> status.message
                        ?: stringResource(R.string.home_diarization_not_installed)

                    ModelState.VERIFYING -> stringResource(R.string.home_model_verifying)
                    ModelState.IMPORTING -> stringResource(
                        R.string.home_model_importing,
                        (status.progress * 100).roundToInt(),
                    )

                    ModelState.READY -> stringResource(
                        R.string.home_model_ready,
                        formatBytes(status.sizeBytes ?: 0L),
                    )

                    ModelState.INVALID -> status.message
                        ?: stringResource(R.string.home_model_invalid)

                    ModelState.ERROR -> status.message
                        ?: stringResource(R.string.home_model_error)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (hasError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (status.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onImportSegmentation) {
                        Text(
                            stringResource(
                                if (status.isReady) {
                                    R.string.home_model_replace
                                } else {
                                    R.string.home_diarization_import_segmentation
                                },
                            ),
                        )
                    }
                    OutlinedButton(onClick = onImportEmbedding) {
                        Text(
                            stringResource(
                                if (status.isReady) {
                                    R.string.home_model_replace
                                } else {
                                    R.string.home_diarization_import_embedding
                                },
                            ),
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.home_diarization_verification_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelCard(
    title: String,
    status: ModelStatus,
    verificationNote: String,
    notInstalledNote: String,
    onImport: () -> Unit,
) {
    val hasError = status.state == ModelState.INVALID || status.state == ModelState.ERROR
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = when (status.state) {
                    ModelState.NOT_INSTALLED -> notInstalledNote
                    ModelState.VERIFYING -> stringResource(R.string.home_model_verifying)
                    ModelState.IMPORTING -> stringResource(
                        R.string.home_model_importing,
                        (status.progress * 100).roundToInt(),
                    )

                    ModelState.READY -> stringResource(
                        R.string.home_model_ready,
                        formatBytes(status.sizeBytes ?: 0L),
                    )

                    ModelState.INVALID -> status.message
                        ?: stringResource(R.string.home_model_invalid)

                    ModelState.ERROR -> status.message
                        ?: stringResource(R.string.home_model_error)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (hasError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (status.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedButton(onClick = onImport) {
                    Text(
                        stringResource(
                            if (status.isReady) {
                                R.string.home_model_replace
                            } else {
                                R.string.home_model_import
                            },
                        ),
                    )
                }
            }
            Text(
                text = verificationNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
