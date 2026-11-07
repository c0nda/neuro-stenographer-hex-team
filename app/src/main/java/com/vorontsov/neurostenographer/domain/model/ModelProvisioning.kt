package com.vorontsov.neurostenographer.domain.model

import java.io.InputStream
import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle of a manually provisioned ML model file (imported by the user via
 * the system file picker; the app never downloads models, so the runtime stays
 * fully offline). Shared by every model the app can install (ASR, VAD, and
 * future diarization models).
 */
enum class ModelState {
    NOT_INSTALLED,
    VERIFYING,
    IMPORTING,
    READY,
    INVALID,
    ERROR,
}

data class ModelStatus(
    val state: ModelState = ModelState.NOT_INSTALLED,
    val progress: Float = 0f,
    val sizeBytes: Long? = null,
    val message: String? = null,
) {
    val isReady: Boolean get() = state == ModelState.READY
    val isBusy: Boolean get() = state == ModelState.VERIFYING || state == ModelState.IMPORTING
}

/**
 * Manages one user-imported model file: streaming import with progress,
 * integrity verification, refresh, and deletion. Implementations must verify
 * the exact artifact (size and digest) before reporting [ModelState.READY].
 */
interface ModelRepository {
    val status: StateFlow<ModelStatus>

    /** Re-checks the installed file against the expected artifact. */
    suspend fun refresh()

    /**
     * Streams a user-selected model into private storage. [open] is invoked on
     * a background thread and must return a fresh stream.
     */
    suspend fun importModel(open: () -> InputStream): Result<Unit>

    suspend fun deleteModel()
}
