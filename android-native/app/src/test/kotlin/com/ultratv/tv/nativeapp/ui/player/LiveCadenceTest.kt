package com.ultratv.tv.nativeapp.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveCadenceTest {

    @Test
    fun estimatorWaitsForEnoughSamples() {
        val estimator = LiveCadenceEstimator()
        var ptsUs = 0L
        var result: Float? = null

        repeat(20) {
            result = estimator.sample(ptsUs)
            ptsUs += 16_683L
        }

        assertNull(result)
    }

    @Test
    fun estimatorRecognizes5994BroadcastCadence() {
        val estimator = LiveCadenceEstimator()
        var ptsUs = 0L
        var result: Float? = null

        repeat(32) {
            result = estimator.sample(ptsUs)
            ptsUs += 16_683L
        }

        assertEquals(59.94f, result ?: 0f, 0.02f)
    }

    @Test
    fun estimatorRecognizes25FpsBroadcastCadence() {
        val estimator = LiveCadenceEstimator()
        var ptsUs = 0L
        var result: Float? = null

        repeat(32) {
            result = estimator.sample(ptsUs)
            ptsUs += 40_000L
        }

        assertEquals(25f, result ?: 0f, 0.01f)
    }

    @Test
    fun estimatorRecognizes23976FilmCadence() {
        val estimator = LiveCadenceEstimator()
        var ptsUs = 0L
        var result: Float? = null

        repeat(32) {
            result = estimator.sample(ptsUs)
            ptsUs += 41_708L
        }

        assertEquals(23.976f, result ?: 0f, 0.02f)
    }

    @Test
    fun estimatorIgnoresTimestampDiscontinuity() {
        val estimator = LiveCadenceEstimator()
        var ptsUs = 0L
        var result: Float? = null

        repeat(12) {
            result = estimator.sample(ptsUs)
            ptsUs += 20_000L
        }

        // Simulate a transport-stream discontinuity/seek. The huge jump must
        // not become a frame-duration sample.
        ptsUs += 2_000_000L
        result = estimator.sample(ptsUs)
        assertNull(result)

        repeat(20) {
            ptsUs += 20_000L
            result = estimator.sample(ptsUs)
        }

        assertEquals(50f, result ?: 0f, 0.01f)
    }

    @Test
    fun normalizeBroadcastRateSnapsCommonRates() {
        assertEquals(23.976f, normalizeBroadcastRate(23.98f), 0.001f)
        assertEquals(29.97f, normalizeBroadcastRate(29.95f), 0.001f)
        assertEquals(59.94f, normalizeBroadcastRate(59.92f), 0.001f)
        assertEquals(50f, normalizeBroadcastRate(50.1f), 0.001f)
    }
}
