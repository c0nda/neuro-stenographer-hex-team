package com.vorontsov.neurostenographer.presentation.common

import com.vorontsov.neurostenographer.domain.model.AlignmentSource
import com.vorontsov.neurostenographer.domain.model.RecordingStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun formatClock(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

fun formatTimestamp(timestampMs: Long): String {
    val safe = timestampMs.coerceAtLeast(0)
    val hours = safe / 3_600_000
    val minutes = (safe % 3_600_000) / 60_000
    val seconds = (safe % 60_000) / 1000
    val millis = safe % 1000
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d.%03d", minutes, seconds, millis)
    }
}

fun formatDate(epochMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())
    return Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}

fun formatDurationShort(durationMs: Long): String = formatClock(durationMs)

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
}

fun RecordingStatus.labelKey(): String = when (this) {
    RecordingStatus.RECORDING -> "status_recording"
    RecordingStatus.RECORDED -> "status_recorded"
    RecordingStatus.PROCESSING -> "status_processing"
    RecordingStatus.READY -> "status_ready"
    RecordingStatus.FAILED -> "status_failed"
}

fun AlignmentSource.isVerified(): Boolean = this == AlignmentSource.FORCED_ALIGNER
