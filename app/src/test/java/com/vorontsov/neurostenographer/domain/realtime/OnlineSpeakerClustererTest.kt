package com.vorontsov.neurostenographer.domain.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSpeakerClustererTest {

    private fun vector(vararg values: Float) = floatArrayOf(*values)

    @Test
    fun `identical embeddings share one cluster`() {
        val clusterer = OnlineSpeakerClusterer()
        val a = vector(1f, 0f, 0f)
        assertEquals(0, clusterer.add(a))
        assertEquals(0, clusterer.add(a.copyOf()))
        assertEquals(0, clusterer.add(vector(0.999f, 0.001f, 0f)))
        assertEquals(1, clusterer.clusterCount)
    }

    @Test
    fun `orthogonal embeddings open separate clusters`() {
        val clusterer = OnlineSpeakerClusterer()
        assertEquals(0, clusterer.add(vector(1f, 0f, 0f)))
        assertEquals(1, clusterer.add(vector(0f, 1f, 0f)))
        assertEquals(2, clusterer.add(vector(0f, 0f, 1f)))
        assertEquals(3, clusterer.clusterCount)
    }

    @Test
    fun `noisy variants of one speaker stay together`() {
        val clusterer = OnlineSpeakerClusterer(threshold = 0.9f)
        val base = vector(1f, 0f, 0f)
        assertEquals(0, clusterer.add(base))
        // Small perturbations remain highly similar to the centroid.
        repeat(5) { i ->
            val noisy = vector(1f, 0.05f * (i + 1), 0f)
            assertEquals(0, clusterer.add(noisy))
        }
        assertEquals(1, clusterer.clusterCount)
    }

    @Test
    fun `cluster indices are stable in creation order`() {
        val clusterer = OnlineSpeakerClusterer()
        val speakerA = vector(1f, 0f)
        val speakerB = vector(0f, 1f)
        assertEquals(0, clusterer.add(speakerA)) // A → 0
        assertEquals(1, clusterer.add(speakerB)) // B → 1
        assertEquals(0, clusterer.add(speakerA.copyOf()))
        assertEquals(1, clusterer.add(speakerB.copyOf()))
        assertEquals(2, clusterer.clusterCount)
    }

    @Test
    fun `centroid adapts gradually towards new members`() {
        val clusterer = OnlineSpeakerClusterer(threshold = 0.95f)
        assertEquals(0, clusterer.add(vector(1f, 0f)))
        // Close to the first vector → same cluster.
        assertEquals(0, clusterer.add(vector(0.98f, 0.02f)))
        // Far from the adapted centroid but near original? With threshold 0.95
        // this still belongs: cosine to centroid (~0.99,0.01) ≈ 0.999.
        assertEquals(0, clusterer.add(vector(0.96f, 0.04f)))
        assertEquals(1, clusterer.clusterCount)
    }

    @Test
    fun `reset clears all clusters`() {
        val clusterer = OnlineSpeakerClusterer()
        clusterer.add(vector(1f, 0f))
        clusterer.add(vector(0f, 1f))
        clusterer.reset()
        assertEquals(0, clusterer.clusterCount)
        assertEquals(0, clusterer.add(vector(0f, 0f, 1f)))
    }

    @Test
    fun `zero norm embedding is rejected loudly`() {
        var thrown: Throwable? = null
        try {
            OnlineSpeakerClusterer().add(vector(0f, 0f, 0f))
        } catch (throwable: Throwable) {
            thrown = throwable
        }
        assertTrue(thrown is IllegalArgumentException)
    }
}
