package com.vorontsov.neurostenographer.presentation.navigation

/**
 * String routes are used deliberately: they stay readable and work identically
 * with Navigation Compose's long-term supported API surface.
 */
object Destinations {
    const val RECORDING_ID_ARG = "recordingId"

    const val HOME = "home"
    const val RECORDING = "recording"
    const val HISTORY = "history"
    const val TRANSCRIPT = "transcript/{$RECORDING_ID_ARG}"
    const val SPEAKERS = "speakers/{$RECORDING_ID_ARG}"

    fun transcript(recordingId: Long): String = "transcript/$recordingId"

    fun speakers(recordingId: Long): String = "speakers/$recordingId"
}
