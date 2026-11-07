package com.vorontsov.neurostenographer.presentation.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vorontsov.neurostenographer.R
import com.vorontsov.neurostenographer.domain.model.CaptureStatus
import com.vorontsov.neurostenographer.presentation.common.formatClock
import com.vorontsov.neurostenographer.presentation.common.formatTimestamp
import kotlinx.coroutines.launch
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    onBack: () -> Unit,
    onRecordingFinished: (Long) -> Unit,
    viewModel: RecordingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startError by viewModel.startError.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val liveSegments by viewModel.liveSegments.collectAsStateWithLifecycle()
    val streamingState by viewModel.streamingState.collectAsStateWithLifecycle()
    val currentSpeaker by viewModel.currentSpeaker.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val resources = LocalResources.current
    val view = LocalView.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var hasPermission by remember { mutableStateOf(context.hasMicrophonePermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    resources.getString(R.string.recording_permission_title),
                )
            }
        }
    }

    val isActive = state.status == CaptureStatus.RECORDING || state.status == CaptureStatus.PAUSED
    DisposableEffect(keepScreenOn, isActive) {
        if (keepScreenOn && isActive) view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    LaunchedEffect(startError) {
        val message = startError
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearStartError()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.finishedRecording.collect(onRecordingFinished)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recording_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TimerCard(
                status = state.status,
                elapsedMs = state.elapsedMs,
                amplitudes = state.amplitudes,
            )

            if (!hasPermission) {
                PermissionCard(
                    onGrant = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                )
            }

            Controls(
                status = state.status,
                hasPermission = hasPermission,
                onStart = {
                    if (hasPermission) {
                        viewModel.start()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onStop = viewModel::stop,
                onDismissError = viewModel::dismissError,
                errorMessage = state.errorMessage,
            )
        }
    }
}

@Composable
private fun TimerCard(
    status: CaptureStatus,
    elapsedMs: Long,
    amplitudes: List<Float>,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val statusColor = when (status) {
                    CaptureStatus.RECORDING -> MaterialTheme.colorScheme.error
                    CaptureStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
                    CaptureStatus.STOPPING -> MaterialTheme.colorScheme.primary
                    CaptureStatus.ERROR -> MaterialTheme.colorScheme.error
                    CaptureStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    modifier = Modifier.size(8.dp),
                    shape = CircleShape,
                    color = statusColor,
                ) {}
                Text(
                    text = status.label(),
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor,
                )
            }

            Text(
                text = formatClock(elapsedMs),
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFeatureSettings = "tnum",
                ),
                textAlign = TextAlign.Center,
            )

            Waveform(
                amplitudes = amplitudes,
                active = status == CaptureStatus.RECORDING,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            )
        }
    }
}

@Composable
private fun Waveform(
    amplitudes: List<Float>,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val barColor = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    val minimumHeight = 2.dp
    Canvas(modifier = modifier) {
        val barCount = 48
        val slot = size.width / barCount
        val gap = slot * 0.35f
        val barWidth = max(1f, slot - gap)
        val centerY = size.height / 2f
        val minHeightPx = minimumHeight.toPx()
        for (index in 0 until barCount) {
            val sourceIndex = amplitudes.size - barCount + index
            val amplitude = if (sourceIndex >= 0) {
                amplitudes[sourceIndex].coerceIn(0f, 1f)
            } else {
                0f
            }
            val barHeight = max(minHeightPx, amplitude * size.height)
            drawRoundRect(
                color = barColor,
                topLeft = Offset(index * slot + gap / 2f, centerY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.recording_permission_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.recording_permission_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onGrant) {
                Text(stringResource(R.string.recording_grant_permission))
            }
        }
    }
}

@Composable
private fun Controls(
    status: CaptureStatus,
    hasPermission: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDismissError: () -> Unit,
    errorMessage: String?,
) {
    when (status) {
        CaptureStatus.IDLE -> {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                enabled = hasPermission,
            ) {
                Text(stringResource(R.string.recording_start))
            }
        }

        CaptureStatus.RECORDING -> {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.recording_pause))
                }
                Button(onClick = onStop, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.recording_stop))
                }
            }
        }

        CaptureStatus.PAUSED -> {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onResume, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.recording_resume))
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.recording_stop))
                }
            }
        }

        CaptureStatus.STOPPING -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.recording_finishing))
            }
        }

        CaptureStatus.ERROR -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.recording_error_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = errorMessage ?: stringResource(R.string.error_generic),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onDismissError) {
                        Text(stringResource(R.string.recording_dismiss))
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureStatus.label(): String = when (this) {
    CaptureStatus.IDLE -> stringResource(R.string.recording_ready)
    CaptureStatus.RECORDING -> stringResource(R.string.recording_active)
    CaptureStatus.PAUSED -> stringResource(R.string.recording_paused)
    CaptureStatus.STOPPING -> stringResource(R.string.recording_finishing)
    CaptureStatus.ERROR -> stringResource(R.string.recording_error_title)
}

private fun Context.hasMicrophonePermission(): Boolean =
    ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
