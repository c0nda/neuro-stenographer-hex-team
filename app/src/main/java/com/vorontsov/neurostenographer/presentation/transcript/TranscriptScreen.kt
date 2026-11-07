package com.vorontsov.neurostenographer.presentation.transcript

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vorontsov.neurostenographer.R
import com.vorontsov.neurostenographer.data.transcription.TranscriptionState
import com.vorontsov.neurostenographer.domain.model.AlignmentSource
import com.vorontsov.neurostenographer.domain.model.ModelState
import com.vorontsov.neurostenographer.domain.model.ModelStatus
import com.vorontsov.neurostenographer.domain.model.PlaybackState
import com.vorontsov.neurostenographer.domain.model.Speaker
import com.vorontsov.neurostenographer.domain.model.TranscriptSegment
import com.vorontsov.neurostenographer.domain.model.WordTimestamp
import com.vorontsov.neurostenographer.domain.transcription.TranscriptionStage
import com.vorontsov.neurostenographer.presentation.common.EmptyState
import com.vorontsov.neurostenographer.presentation.common.formatClock
import com.vorontsov.neurostenographer.presentation.common.formatTimestamp
import com.vorontsov.neurostenographer.presentation.common.isVerified
import com.vorontsov.neurostenographer.presentation.common.speakerColor
import kotlin.math.roundToInt

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptScreen(
    recordingId: Long,
    onBack: () -> Unit,
    onOpenSpeakers: () -> Unit,
    viewModel: TranscriptViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val transcription by viewModel.transcription.collectAsStateWithLifecycle()
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    var expandedSegmentKey by remember { mutableStateOf<Long?>(null) }
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    val exportName = remember(uiState.recording?.title) {
        val base = uiState.recording?.title
            ?.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
            ?.trim('_')
            ?.take(48)
            ?.ifBlank { null }
            ?: "transcript"
        "$base.json"
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) viewModel.export(uri)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                TranscriptEvent.Exported -> R.string.transcript_exported
                TranscriptEvent.NothingToExport -> R.string.transcript_export_empty
                TranscriptEvent.ExportFailed -> R.string.transcript_export_failed
                TranscriptEvent.TranscriptionBusy -> R.string.transcript_transcription_busy
                TranscriptEvent.ModelRequired -> R.string.transcript_model_required_short
            }
            snackbarHostState.showSnackbar(resources.getString(message))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.recording?.title ?: stringResource(R.string.transcript_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenSpeakers,
                        enabled = uiState.recording != null,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = stringResource(R.string.transcript_speakers),
                        )
                    }
                    IconButton(
                        onClick = { exportLauncher.launch(exportName) },
                        enabled = uiState.segments.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.transcript_export),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            uiState.recording == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                EmptyState(
                    title = stringResource(R.string.transcript_missing_title),
                    body = stringResource(R.string.transcript_missing_body),
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "playback") {
                    PlaybackCard(
                        playback = uiState.playback,
                        onTogglePlayback = viewModel::togglePlayback,
                        onStopPlayback = viewModel::stopPlayback,
                        onSeek = viewModel::seekTo,
                    )
                }

                item(key = "transcription") {
                    TranscriptionCard(
                        modelStatus = modelStatus,
                        state = transcription,
                        hasSegments = uiState.segments.isNotEmpty(),
                        onTranscribe = viewModel::transcribe,
                        onCancel = viewModel::cancelTranscription,
                        onDismiss = viewModel::dismissTranscription,
                    )
                }

                if (uiState.segments.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = stringResource(R.string.transcript_empty_title),
                            body = ""
                        )
                    }
                } else {
                    val provisional = uiState.segments.any { it.isProvisional }
                    item(key = "header") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.transcript_segment_count,
                                    uiState.segments.size,
                                    uiState.segments.size,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = if (provisional) {
                                    stringResource(R.string.transcript_provisional_hint)
                                } else {
                                    stringResource(R.string.transcript_playback_hint)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(
                        items = uiState.segments,
                        key = { segment -> segment.id ?: "${segment.startMs}-${segment.endMs}" },
                    ) { segment ->
                        val segmentKey = segment.id ?: -1L
                        SegmentCard(
                            segment = segment,
                            speakers = uiState.speakers,
                            expanded = expandedSegmentKey == segmentKey,
                            playbackPositionMs = uiState.playback.positionMs,
                            onClick = { viewModel.playFromSegment(segment) },
                            onToggleWords = {
                                expandedSegmentKey =
                                    if (expandedSegmentKey == segmentKey) null else segmentKey
                            },
                            onSeekToWord = { wordStartMs ->
                                viewModel.seekTo(wordStartMs, play = true)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptionCard(
    modelStatus: ModelStatus,
    state: TranscriptionState,
    hasSegments: Boolean,
    onTranscribe: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val modelFailed = modelStatus.state == ModelState.INVALID ||
        modelStatus.state == ModelState.ERROR ||
        modelStatus.state == ModelState.NOT_INSTALLED

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                modelFailed -> {
                    val message = modelStatus.message
                    if (!message.isNullOrBlank()) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                modelStatus.isBusy -> {
                    Text(
                        text = stringResource(R.string.transcript_model_checking),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                state is TranscriptionState.Running -> {
                    Text(
                        text = stageLabel(state.stage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.transcript_transcription_progress,
                                (state.progress * 100).roundToInt(),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.transcript_transcription_cancel))
                        }
                    }
                }

                state is TranscriptionState.Queued -> {
                    Text(
                        text = stringResource(R.string.transcript_stage_queued),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.transcript_transcription_cancel))
                    }
                }

                state is TranscriptionState.Failed -> {
                    Text(
                        text = stringResource(
                            R.string.transcript_transcription_failed,
                            state.message,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.transcript_transcription_dismiss))
                    }
                }

                state is TranscriptionState.Cancelled -> {
                    Text(
                        text = stringResource(R.string.transcript_transcription_cancelled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.transcript_transcription_dismiss))
                    }
                }

                else -> {
                    if (state is TranscriptionState.Completed) {
                        Text(
                            text = stringResource(
                                R.string.transcript_transcription_completed,
                                state.segmentCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = onTranscribe) {
                        Text(
                            stringResource(
                                if (hasSegments) {
                                    R.string.transcript_retranscribe
                                } else {
                                    R.string.transcript_transcribe
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun stageLabel(stage: TranscriptionStage): String = when (stage) {
    TranscriptionStage.PREPARING -> stringResource(R.string.transcript_stage_preparing)
    TranscriptionStage.DETECTING_SPEECH -> stringResource(R.string.transcript_stage_detecting_speech)
    TranscriptionStage.RECOGNIZING -> stringResource(R.string.transcript_stage_recognizing)
    TranscriptionStage.DIARIZING -> stringResource(R.string.transcript_stage_diarizing)
    TranscriptionStage.SAVING -> stringResource(R.string.transcript_stage_saving)
}

@Composable
private fun PlaybackCard(
    playback: PlaybackState,
    onTogglePlayback: () -> Unit,
    onStopPlayback: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs
    val progress = if (duration > 0) {
        (playback.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

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
                text = stringResource(R.string.transcript_playback),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatClock(playback.positionMs),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = formatClock(duration),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Slider(
                value = dragFraction ?: progress,
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    val fraction = dragFraction
                    dragFraction = null
                    if (fraction != null && duration > 0) {
                        onSeek((fraction * duration).toLong())
                    }
                },
                enabled = duration > 0,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onTogglePlayback,
                    modifier = Modifier.weight(1f),
                    enabled = playback.isPrepared,
                ) {
                    Text(
                        stringResource(
                            if (playback.isPlaying) R.string.action_pause else R.string.action_play,
                        ),
                    )
                }
                OutlinedButton(
                    onClick = onStopPlayback,
                    enabled = playback.isPrepared,
                ) {
                    Text(stringResource(R.string.action_stop))
                }
            }
            if (playback.errorMessage != null) {
                Text(
                    text = stringResource(R.string.transcript_playback_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SegmentCard(
    segment: TranscriptSegment,
    speakers: List<Speaker>,
    expanded: Boolean,
    playbackPositionMs: Long,
    onClick: () -> Unit,
    onToggleWords: () -> Unit,
    onSeekToWord: (Long) -> Unit,
) {
    val accent = speakerColor(segment.speakerId)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent, RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = speakerName(segment.speakerId, speakers),
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.transcript_segment_time,
                            formatTimestamp(segment.startMs),
                            formatTimestamp(segment.endMs),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (segment.isProvisional) {
                        Tag(
                            text = stringResource(R.string.transcript_provisional),
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (segment.alignmentSource != AlignmentSource.NONE) {
                        Tag(
                            text = stringResource(
                                if (segment.alignmentSource.isVerified()) {
                                    R.string.transcript_alignment_verified
                                } else {
                                    R.string.transcript_alignment_estimated
                                },
                            ),
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                if (segment.words.isNotEmpty()) {
                    TextButton(onClick = onToggleWords) {
                        Text(
                            text = stringResource(
                                if (expanded) {
                                    R.string.transcript_hide_words
                                } else {
                                    R.string.transcript_show_words
                                },
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Icon(
                            imageVector = if (expanded) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = null,
                        )
                    }
                }
                if (expanded && segment.words.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        segment.words.forEach { word ->
                            WordChip(
                                word = word,
                                active = playbackPositionMs in word.startMs until word.endMs,
                                accent = accent,
                                onClick = { onSeekToWord(word.startMs) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WordChip(
    word: WordTimestamp,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = word.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) accent else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (active) FontWeight.Bold else null,
        )
        Text(
            text = formatTimestamp(word.startMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.14f),
        contentColor = color,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun speakerName(speakerId: String?, speakers: List<Speaker>): String {
    val key = speakerId?.takeIf { it.isNotBlank() } ?: "unknown"
    if (key == "unknown") {
        return stringResource(R.string.transcript_unknown_speaker)
    }
    val displayName = speakers.firstOrNull { it.speakerKey == key }?.displayName
    if (!displayName.isNullOrBlank()) return displayName
    val suffix = key.removePrefix("speaker_")
    return if (suffix != key && suffix.all { it.isDigit() }) {
        stringResource(R.string.speakers_numbered, suffix)
    } else {
        key
    }
}
