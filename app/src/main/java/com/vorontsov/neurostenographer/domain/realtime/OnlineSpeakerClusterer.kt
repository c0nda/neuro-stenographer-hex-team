package com.vorontsov.neurostenographer.domain.realtime

import kotlin.math.sqrt

/**
 * Provisional online speaker clustering for the real-time pipeline (PROMT
 * §3.4/§5): each completed speech region yields an embedding; the clusterer
 * assigns it to an existing cluster when cosine similarity to the cluster
 * centroid reaches [threshold], otherwise opens a new cluster.
 *
 * Cluster indices are stable in creation order, which maps directly to the
 * provisional `speaker_N` keys shown during recording. Labels are REVISED by
 * the full offline diarization after the recording stops — this is a
 * short-term provisional assignment, not streaming diarization in the
 * algorithmic sense, and it is never presented as final (PROMT §3.4).
 *
 * [threshold] is a starting value (0.5 cosine), NOT tuned: DER evaluation on
 * labeled Russian data is a Phase 7 item.
 */
class OnlineSpeakerClusterer(
    private val threshold: Float = DEFAULT_THRESHOLD,
) {

    private data class Cluster(
        var count: Int,
        val centroid: FloatArray,
    )

    private val clusters = mutableListOf<Cluster>()

    val clusterCount: Int get() = clusters.size

    /**
     * Adds an embedding and returns its cluster index (0-based, creation
     * order). Embeddings must be non-zero; the CAM++ extractor provides
     * unit-normed vectors, but we normalize defensively anyway.
     */
    fun add(embedding: FloatArray): Int {
        require(embedding.isNotEmpty()) { "Empty embedding" }
        val normalized = embedding.normalize()

        var bestIndex = -1
        var bestSimilarity = threshold
        for ((index, cluster) in clusters.withIndex()) {
            val similarity = cosine(normalized, cluster.centroid)
            if (similarity >= bestSimilarity) {
                bestSimilarity = similarity
                bestIndex = index
            }
        }
        if (bestIndex < 0) {
            clusters += Cluster(count = 1, centroid = normalized.copyOf())
            return clusters.lastIndex
        }
        val cluster = clusters[bestIndex]
        // Incremental centroid update (running mean), renormalized to unit
        // length so the dot-product comparison stays a true cosine.
        val n = cluster.count
        for (i in cluster.centroid.indices) {
            cluster.centroid[i] = (cluster.centroid[i] * n + normalized[i]) / (n + 1)
        }
        val updated = cluster.centroid.normalize()
        updated.copyInto(cluster.centroid)
        cluster.count = n + 1
        return bestIndex
    }

    fun reset() {
        clusters.clear()
    }

    private fun FloatArray.normalize(): FloatArray {
        var sum = 0.0
        for (value in this) sum += value * value
        val norm = sqrt(sum)
        require(norm > 1e-8) { "Zero-norm embedding" }
        return FloatArray(size) { (this[it] / norm).toFloat() }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        for (i in a.indices) dot += a[i] * b[i]
        return dot.toFloat()
    }

    private companion object {
        const val DEFAULT_THRESHOLD = 0.5f
    }
}
