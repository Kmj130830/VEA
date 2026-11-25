package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun rejectsProcessorShapeMismatchAtSessionCreation() {
        val inputs = S5MegpoidOutputTransformInputs(
            outputSamplesAtRoot0C = 4,
            geometryAndFftSizeAtRoot04 = 16,
            floatParameterAtRoot10 = 0f,
            frameWindowCoreLengthAtRoot61C = 16,
            alternatingBinSignAtRoot620 = false,
            flagAtRoot1F74 = false,
            frameWindowShapeModeAtRoot3418 = 0,
            flagAtRoot1F75 = false
        )

        assertThrows(IllegalArgumentException::class.java) {
            PreparedMegpoidSpectralOutputSession(
                spectrumShape = S5MegpoidSpectrumOutputShape(fftSize = 8, complexBinCount = 5),
                outputSamples = 4,
                overlapAdd = inputs.createSpectralOverlapAdd()
            )
        }
    }

    @Test
    fun rejectsOutputBlockLengthMismatchAtSessionCreation() {
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

        assertThrows(IllegalArgumentException::class.java) {
            PreparedMegpoidSpectralOutputSession(
                spectrumShape = S5MegpoidSpectrumOutputShape(fftSize = 8, complexBinCount = 5),
                outputSamples = 8,
                overlapAdd = inputs.createSpectralOverlapAdd()
            )
        }
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
    fun consumesTwoModelOutputFeaturePlanesThroughTheExplicitDirectComplexMode() {
        val session = createSession()
        val real = floatArrayOf(16f, 0f, 0f, 0f, 0f)
        val imaginary = FloatArray(5)

        val output = session.processModelOutputPlanes(
            firstFeaturePlane = real,
            secondFeaturePlane = imaginary,
            encoding = S5MegpoidSpectrumFrameEncoding.DIRECT_COMPLEX
        )

        assertEquals(4, output.size)
        assertEquals(1L, session.processedFrameCount)
        assertTrue(output.all { it.isFinite() })
    }

    @Test
    fun invalidModelOutputPlanesDoNotAdvanceTheSession() {
        val session = createSession()
        assertThrows(IllegalArgumentException::class.java) {
            session.processModelOutputPlanes(
                firstFeaturePlane = FloatArray(4),
                secondFeaturePlane = FloatArray(5),
                encoding = S5MegpoidSpectrumFrameEncoding.LOG_MAGNITUDE_PHASE
            )
        }
        assertEquals(0L, session.processedFrameCount)
    }

    private fun runtimeOptions(
        logMagnitudePhase: Boolean
    ) = S5MegpoidRuntimeOptionsDescriptor(
        param0 = 217,
        param1 = 8,
        param2 = 5,
        param3 = 4,
        param4 = 8,
        param5 = 4,
        param6 = 2,
        param13 = false,
        param14 = 0,
        param15 = 1,
        paramD00 = 4,
        paramD01 = true,
        paramD02 = 133.34,
        paramD03 = 25.0,
        paramD04 = true,
        paramD05 = 0.01,
        terminalSpectrumLogMagnitudePhase = logMagnitudePhase,
        carrierFusionEnabled = true,
        alternateTransitionEnabled = false,
        multipartThresholdModeEnabled = false,
        nearestPartMaskEnabled = false,
        partModulationIdentifier = null
    )

    @Test
    fun readsTerminalRepresentationFromTheLoadedRuntimeOptions() {
        val logPolarSession = createSession()
        val first = floatArrayOf(kotlin.math.ln(2f), 0f, 0f, 0f, 0f)
        val phase = FloatArray(5)

        val logPolarOutput = logPolarSession.processModelOutputPlanes(
            firstFeaturePlane = first,
            secondFeaturePlane = phase,
            runtimeOptions = runtimeOptions(logMagnitudePhase = true)
        )
        assertEquals(1L, logPolarSession.processedFrameCount)
        assertTrue(logPolarOutput.all { it.isFinite() })

        val directSession = createSession()
        val directOutput = directSession.processModelOutputPlanes(
            firstFeaturePlane = first,
            secondFeaturePlane = phase,
            runtimeOptions = runtimeOptions(logMagnitudePhase = false)
        )
        assertEquals(1L, directSession.processedFrameCount)
        assertTrue(directOutput.all { it.isFinite() })
        assertFalse(logPolarOutput.contentEquals(directOutput))
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
