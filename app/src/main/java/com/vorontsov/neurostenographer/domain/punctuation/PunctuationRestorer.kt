package com.vorontsov.neurostenographer.domain.punctuation

import com.vorontsov.neurostenographer.domain.asr.AsrWord

/**
 * Offline punctuation restoration for recognized words.
 *
 * Implementations append punctuation marks to the END of word texts; they
 * must never rewrite, translate or "correct" ASR output, never change word
 * timestamps or confidence, and never change the number or order of words.
 * Words returned are new copies; the input list is not modified.
 */
interface PunctuationRestorer : AutoCloseable {

    /** True when the bundled model and tokenizer assets are present. */
    val isAvailable: Boolean

    /**
     * Restores punctuation for a list of words (typically one speaker turn).
     * [PunctuationPostProcessor.capitalizeSentenceStarts] is the separate
     * capitalization step — this interface only deals with punctuation.
     */
    suspend fun restore(words: List<AsrWord>): List<AsrWord>

    /** Releases the native session; the next [restore] recreates it. */
    override fun close()
}

class PunctuationModelUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
