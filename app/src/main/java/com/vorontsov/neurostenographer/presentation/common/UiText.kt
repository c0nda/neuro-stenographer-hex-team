package com.vorontsov.neurostenographer.presentation.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.vorontsov.neurostenographer.R
import com.vorontsov.neurostenographer.domain.model.RecordingStatus

@Composable
fun RecordingStatus.label(): String = stringResource(
    when (this) {
        RecordingStatus.RECORDING -> R.string.status_recording
        RecordingStatus.RECORDED -> R.string.status_recorded
        RecordingStatus.PROCESSING -> R.string.status_processing
        RecordingStatus.READY -> R.string.status_ready
        RecordingStatus.FAILED -> R.string.status_failed
    },
)

@Composable
fun RecordingStatus.statusColor(): Color = when (this) {
    RecordingStatus.RECORDING -> MaterialTheme.colorScheme.error
    RecordingStatus.RECORDED -> MaterialTheme.colorScheme.primary
    RecordingStatus.PROCESSING -> MaterialTheme.colorScheme.tertiary
    RecordingStatus.READY -> MaterialTheme.colorScheme.secondary
    RecordingStatus.FAILED -> MaterialTheme.colorScheme.error
}

private val speakerPalette = listOf(
    Color(0xFF2563EB),
    Color(0xFF0F9D58),
    Color(0xFFB45309),
    Color(0xFF7C3AED),
    Color(0xFFBE123C),
    Color(0xFF0E7490),
    Color(0xFF4D7C0F),
    Color(0xFF9333EA),
)

fun speakerColor(speakerKey: String?): Color {
    if (speakerKey.isNullOrBlank() || speakerKey == "unknown") return Color(0xFF6B7280)
    val index = (speakerKey.hashCode() and Int.MAX_VALUE) % speakerPalette.size
    return speakerPalette[index]
}
