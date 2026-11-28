package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidForwardExcitationSessionTest {
    private fun generator() = S5MegpoidExcitationGenerator(
        phaseScale = 32_000f,
        periodScale = 32_000f,
        sineDenominatorThreshold = 1.0e-6f,
        wrapPhase = true,
        gaussianNoise = { 0f }
    )

    @Test
    fun appendsHopAndEmitsChronologicalWindowedFrameSpectrum() {
        val initialHistory = FloatArray(8) { it.toFloat() }
        val session = S5MegpoidForwardExcitationSession(
            excitationGenerator = generator(),
            fftSize = 8,
            hopSamples = 3,
            frameWindow = FloatArray(8) { 1f },
            initialHistory = initialHistory
        )

        val spectrum = session.processFrame(
            targetF0 = 0f,
            targetNoiseGain = 0f,
            targetHarmonicGain = 0f
        )

        val expectedFrame = floatArrayOf(3f, 4f, 5f, 6f, 7f, 0f, 0f, 0f)
        assertArrayEquals(expectedFrame, session.copyHistoryOldestToNewest(), 0f)
        assertArrayEquals(S5MegpoidForwardRealFft(8).transform(expectedFrame), spectrum, 0f)
        assertEquals(1L, session.processedFrameCount)
    }

    @Test
    fun keepsFrameHistoryAcrossSequentialExcitationChunks() {
        val session = S5MegpoidForwardExcitationSession(
            excitationGenerator = generator(),
            fftSize = 8,
            hopSamples = 3,
            frameWindow = FloatArray(8) { 1f }
        )

        repeat(3) {
            session.processFrame(
                targetF0 = 0f,
                targetNoiseGain = 0f,
                targetHarmonicGain = 0f
            )
        }

        assertArrayEquals(FloatArray(8), session.copyHistoryOldestToNewest(), 0f)
        assertEquals(3L, session.processedFrameCount)
    }

    @Test
    fun appliesTheCallerSuppliedWindowBeforeTheForwardTransform() {
        val initialHistory = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)
        val window = floatArrayOf(1f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f)
        val session = S5MegpoidForwardExcitationSession(
            excitationGenerator = generator(),
            fftSize = 8,
            hopSamples = 1,
            frameWindow = window,
            initialHistory = initialHistory
        )

        val spectrum = session.processFrame(0f, 0f, 0f)
        // The first old sample is overwritten by the zero-valued excitation;
        // chronological history then starts at original sample 2.
        val frame = floatArrayOf(2f, 3f, 4f, 5f, 6f, 7f, 8f, 0f)
        val windowed = FloatArray(8) { frame[it] * window[it] }
        assertArrayEquals(
            S5MegpoidForwardRealFft(8).transform(windowed),
            spectrum,
            0f
        )
    }

    @Test
    fun resetClearsFrameCounterAndRestoresSuppliedHistory() {
        val session = S5MegpoidForwardExcitationSession(
            excitationGenerator = generator(),
            fftSize = 8,
            hopSamples = 2,
            frameWindow = FloatArray(8) { 1f }
        )
        session.processFrame(0f, 0f, 0f)
        assertEquals(1L, session.processedFrameCount)

        val resetHistory = FloatArray(8) { (it + 10).toFloat() }
        session.reset(initialPhase = 0.25f, initialHistory = resetHistory)

        assertEquals(0L, session.processedFrameCount)
        assertArrayEquals(resetHistory, session.copyHistoryOldestToNewest(), 0f)
    }

    @Test
    fun rejectsInvalidHopWindowHistoryAndFrameControls() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidForwardExcitationSession(
                excitationGenerator = generator(),
                fftSize = 8,
                hopSamples = 9,
                frameWindow = FloatArray(8) { 1f }
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidForwardExcitationSession(
                excitationGenerator = generator(),
                fftSize = 8,
                hopSamples = 2,
                frameWindow = FloatArray(7)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidForwardExcitationSession(
                excitationGenerator = generator(),
                fftSize = 8,
                hopSamples = 2,
                frameWindow = FloatArray(8),
                initialHistory = FloatArray(7)
            )
        }
        val session = S5MegpoidForwardExcitationSession(
            excitationGenerator = generator(),
            fftSize = 8,
            hopSamples = 2,
            frameWindow = FloatArray(8) { 1f }
        )
        assertThrows(IllegalArgumentException::class.java) {
            session.processFrame(Float.NaN, 0f, 0f)
        }
        assertEquals(0L, session.processedFrameCount)
    }
}
