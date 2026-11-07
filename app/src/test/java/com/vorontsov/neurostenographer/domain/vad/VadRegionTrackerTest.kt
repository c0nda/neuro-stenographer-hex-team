package com.vorontsov.neurostenographer.domain.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VadRegionTrackerTest {

    private companion object {
        const val FRAME = 512
        const val RATE = 16_000
        // 300 ms of silence = 4800 samples ≈ 9.375 frames; with 32 ms frames the
        // tracker closes a region after 10 consecutive non-speech frames.
        const val SILENCE_FRAMES_TO_CLOSE = 11
    }

    private fun frames(seconds: Float): Int = (seconds * RATE / FRAME).toInt()

    private fun tracker() = VadRegionTracker()

    private fun speech(tracker: VadRegionTracker, seconds: Float) =
        repeat(frames(seconds)) { tracker.accept(0.9f) }

    private fun silence(tracker: VadRegionTracker, seconds: Float) =
        repeat(frames(seconds)) { tracker.accept(0.1f) }

    @Test
    fun `single utterance opens on onset and ends at the last speech frame`() {
        val tracker = tracker()
        silence(tracker, 1f)
        speech(tracker, 1f)

        // flush() closes the trailing open region.
        val regions = tracker.flush()
        assertEquals(1, regions.size)
        val region = regions.single()
        // Speech started after 1 s of silence.
        assertEquals(1f * RATE, region.startSample.toFloat(), FRAME.toFloat())
        // Region end is the last speech frame end (1 s + 1 s), not the stream end.
        assertEquals(2f * RATE, region.endSampleExclusive.toFloat(), FRAME.toFloat())
    }

    @Test
    fun `short click below min speech duration is discarded`() {
        val tracker = tracker()
        speech(tracker, 0.1f) // 100 ms < 250 ms min speech
        silence(tracker, 1f)
        assertTrue(tracker.flush().isEmpty())
    }

    @Test
    fun `brief dropout inside speech does not split the region`() {
        val tracker = tracker()
        speech(tracker, 0.5f)
        silence(tracker, 0.1f) // 100 ms < 300 ms min silence
        speech(tracker, 0.5f)
        silence(tracker, SILENCE_FRAMES_TO_CLOSE * FRAME / 1000f)

        val regions = tracker.flush()
        assertEquals(1, regions.size)
        assertEquals(1f * RATE, regions.single().sampleCount.toFloat(), 2f * FRAME)
    }

    @Test
    fun `two utterances separated by long silence produce two regions`() {
        val tracker = tracker()
        speech(tracker, 0.5f)
        silence(tracker, 1f)
        speech(tracker, 0.5f)
        silence(tracker, SILENCE_FRAMES_TO_CLOSE * FRAME / 1000f)

        val regions = tracker.flush()
        assertEquals(2, regions.size)
        assertTrue(regions[0].endSampleExclusive <= regions[1].startSample)
    }

    @Test
    fun `flush closes a trailing open region`() {
        val tracker = tracker()
        speech(tracker, 0.5f)
        val regions = tracker.flush()
        assertEquals(1, regions.size)
        // Flushing twice must not duplicate the region.
        assertTrue(tracker.flush().isEmpty())
    }

    @Test
    fun `reset clears state and region numbering`() {
        val tracker = tracker()
        speech(tracker, 0.5f)
        tracker.flush()
        tracker.reset()
        speech(tracker, 0.5f)
        silence(tracker, 1f)

        val regions = tracker.flush()
        assertEquals(1, regions.size)
        // After reset the new region starts at sample 0 again.
        assertEquals(0L, regions.single().startSample)
    }

    @Test
    fun `drainCompleted returns closed regions but not the open one`() {
        val tracker = tracker()
        speech(tracker, 0.5f)
        silence(tracker, 1f) // min silence reached → region closes
        speech(tracker, 0.4f) // new region, still open

        val drained = tracker.drainCompleted()
        assertEquals(1, drained.size)
        assertEquals(0L, drained.single().startSample)
        // The open region must not leak through drain…
        assertTrue(tracker.drainCompleted().isEmpty())
        // …and flush still closes it at the end.
        val flushed = tracker.flush()
        assertEquals(1, flushed.size)
        assertTrue(flushed.single().startSample > drained.single().endSampleExclusive)
    }

    @Test
    fun `drainCompleted is idempotent`() {
        val tracker = tracker()
        speech(tracker, 0.5f)
        silence(tracker, 1f)
        assertEquals(1, tracker.drainCompleted().size)
        assertTrue(tracker.drainCompleted().isEmpty())
        assertTrue(tracker.flush().isEmpty())
    }

    @Test
    fun `hysteresis keeps region open between offset and onset thresholds`() {
        val tracker = tracker()
        tracker.accept(0.9f) // open (>= 0.5)
        repeat(frames(0.4f)) { tracker.accept(0.4f) } // below 0.5, above 0.35: continue
        val during = tracker.flush()
        assertEquals(1, during.size)
    }
}
