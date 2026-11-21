package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedMegpoidSpectralOutputSessionTest {
    private fun createSession(): PreparedMegpoidSpectralOutputSession {
        val inputs = S5MegpoidOutputTransformInputs(
            outputSamplesAtRoot0C = 4,
            geometryAndFftSizeAtRoot04 = 8,
            floatParameterAtRoot10 = 0f,
            frameWindowCoreLengthAtRoot61C = 8,
            alternatingBinSignAtRoot620 = false,
            flagAtRoot1F74 = false,
            frameWindowShapeModeAtRoot3418 = 0,
            flagAtRoot1F75 = false
        )
        return PreparedMegpoidSpectralOutputSession(
            spectrumShape = S5MegpoidSpectrumOutputShape(fftSize = 8, complexBinCount = 5),
            outputSamples = 4,
            overlapAdd = inputs.createSpectralOverlapAdd()
        )
    }

    @Test
    fun consumesSequentialModelSpectrumFramesAndPreservesState() {
        val session = createSession()
        val firstSpectrum = FloatArray(10).also { it[0] = 16f }
        val secondSpectrum = FloatArray(10).also { it[0] = 8f }

        val first = session.processSpectrumFrame(firstSpectrum)
        val second = session.processSpectrumFrame(secondSpectrum)

        assertEquals(4, first.size)
        assertEquals(4, second.size)
        assertEquals(2L, session.processedFrameCount)
        assertTrue(first.all { it.isFinite() })
        assertTrue(second.all { it.isFinite() })
    }

    @Test
    fun rejectsWrongSpectrumLengthBeforeChangingSessionState() {
        val session = createSession()

        assertThrows(IllegalArgumentException::class.java) {
            session.processSpectrumFrame(FloatArray(8))
        }

        assertEquals(0L, session.processedFrameCount)
    }

    @Test
    fun rejectsNonFiniteSpectrumBeforeChangingSessionState() {
        val session = createSession()
        val invalid = FloatArray(10).also { it[3] = Float.NaN }

        assertThrows(IllegalArgumentException::class.java) {
            session.processSpectrumFrame(invalid)
        }

        assertEquals(0L, session.processedFrameCount)
    }

    @Test
    fun resetClearsOverlapHistoryAndFrameCounter() {
        val session = createSession()
        val spectrum = FloatArray(10).also { it[0] = 16f }
        session.processSpectrumFrame(spectrum)
        session.processSpectrumFrame(spectrum)
        assertEquals(2L, session.processedFrameCount)

        session.reset()

        assertEquals(0L, session.processedFrameCount)
        val afterReset = session.processSpectrumFrame(spectrum)
        assertEquals(4, afterReset.size)
        assertEquals(1L, session.processedFrameCount)
    }
}
