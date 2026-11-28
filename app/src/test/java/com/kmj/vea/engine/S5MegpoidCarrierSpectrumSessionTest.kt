package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidCarrierSpectrumSessionTest {
    private fun session(
        invertOddBins: Boolean = false,
        seed: Int = S5MegpoidCarrierSpectrumSession.NATIVE_RANDOM_SEED
    ) = S5MegpoidCarrierSpectrumSession(
        fftSize = 8,
        hopSamples = 4,
        windowCoreLength = 0,
        windowShapeMode = 0,
        invertOddComplexBins = invertOddBins,
        randomSeed = seed
    )

    @Test
    fun derivesNativeRingDelayAndPowerOfTwoCapacity() {
        val session = session()

        // N=8, hop=4:
        // lagBlocks=ceil(8/4)+1=3
        // leftPadding=(8-4)/2=2
        // required=(3+1)*4+2+8-1+4=29 -> capacity 32, mask 31.
        assertEquals(3, session.nativeLagBlockCount)
        assertEquals(2, session.nativeLeftPadding)
        assertEquals(32, session.ringCapacity)
    }

    @Test
    fun delaysInitialFramesUntilTheNativeHistoryWindowIsAvailable() {
        val session = session()

        repeat(3) { index ->
            val spectrum = session.processFrame(
                FloatArray(4) { index * 4f + it }
            )
            assertArrayEquals(FloatArray(10), spectrum, 0f)
        }

        // Frame 4 has a partially valid source window starting at logical -2.
        val partial = session.processFrame(floatArrayOf(12f, 13f, 14f, 15f))
        val partialFrame = floatArrayOf(0f, 0f, 0f, 1f, 2f, 3f, 4f, 5f)
        val partialWindow = S5MegpoidFrameWindowBuilder.build(8, 0, 0)
        val partialExpected = S5MegpoidForwardRealFft(8).transform(
            FloatArray(8) { partialFrame[it] * partialWindow[it] }
        )
        assertArrayEquals(partialExpected, partial, 1.0e-6f)

        assertEquals(4L, session.processedFrameCount)
        assertEquals(16L, session.logicalSampleCursor)
    }

    @Test
    fun selectsDelayedLogicalSamplesAppliesFrameWindowAndRunsForwardFft() {
        val session = session()
        repeat(4) { frame ->
            session.processFrame(FloatArray(4) { frame * 4f + it })
        }

        // On frame 5 the native source start is:
        // (cursor/hop - lagBlocks - 1)*hop - leftPadding
        // = (5 - 3 - 1)*4 - 2 = 2.
        val fifthSpectrum = session.processFrame(floatArrayOf(16f, 17f, 18f, 19f))
        val rawFrame = floatArrayOf(2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        val window = S5MegpoidFrameWindowBuilder.build(
            totalLength = 8,
            coreLength = 0,
            shapeMode = 0
        )
        val expectedWindowed = FloatArray(8) { rawFrame[it] * window[it] }
        val expected = S5MegpoidForwardRealFft(8).transform(expectedWindowed)

        assertArrayEquals(expected, fifthSpectrum, 1.0e-6f)
        assertEquals(5L, session.processedFrameCount)
    }

    @Test
    fun oddBinFlagFlipsBothRealAndImaginaryPlanesButLeavesEvenBinsAlone() {
        val normal = session(invertOddBins = false)
        val inverted = session(invertOddBins = true)

        repeat(5) { frame ->
            val samples = FloatArray(4) { frame * 4f + it }
            normal.processFrame(samples)
            inverted.processFrame(samples)
        }
        val samples = floatArrayOf(20f, 21f, 22f, 23f)
        val plain = normal.processFrame(samples)
        val flipped = inverted.processFrame(samples)

        for (bin in 0..4) {
            val sign = if (bin == 1 || bin == 3) -1f else 1f
            assertEquals(plain[bin * 2] * sign, flipped[bin * 2], 0f)
            assertEquals(plain[bin * 2 + 1] * sign, flipped[bin * 2 + 1], 0f)
        }
    }

    @Test
    fun delayedFrameRemainsCorrectAfterTheCircularRingWraps() {
        val session = session()
        var finalSpectrum = FloatArray(10)

        repeat(12) { frame ->
            finalSpectrum = session.processFrame(
                FloatArray(4) { frame * 4f + it }
            )
        }

        // After 12 hops the logical cursor is 48, while the 32-sample ring
        // retains logical samples 16..47. The native delayed source begins at
        // (12 - 3 - 1)*4 - 2 = 30, so this frame reads samples 30..37.
        val rawFrame = FloatArray(8) { 30f + it }
        val window = S5MegpoidFrameWindowBuilder.build(8, 0, 0)
        val expected = S5MegpoidForwardRealFft(8).transform(
            FloatArray(8) { rawFrame[it] * window[it] }
        )
        assertArrayEquals(expected, finalSpectrum, 1.0e-6f)
        assertEquals(48L, session.logicalSampleCursor)

        val history = session.copyHistoryOldestToNewest()
        assertEquals(32, history.size)
        assertEquals(16f, history.first(), 0f)
        assertEquals(47f, history.last(), 0f)
    }

    @Test
    fun nativeGaussianSourceIsDeterministicAndResetRestoresItsSequence() {
        val first = session(seed = 12_345)
        val second = session(seed = 12_345)

        repeat(6) {
            val a = first.processFrame()
            val b = second.processFrame()
            assertArrayEquals(a, b, 0f)
        }
        val firstOutput = first.processFrame()

        first.reset(seed = 12_345)
        repeat(6) { first.processFrame() }
        assertArrayEquals(firstOutput, first.processFrame(), 0f)

        val otherSeed = session(seed = 54321)
        repeat(6) { otherSeed.processFrame() }
        assertNotEquals(firstOutput.toList(), otherSeed.processFrame().toList())
    }

    @Test
    fun copiesRingHistoryChronologicallyAndResetsTheBuffer() {
        val session = session()
        session.processFrame(floatArrayOf(0f, 1f, 2f, 3f))
        session.processFrame(floatArrayOf(4f, 5f, 6f, 7f))

        val history = session.copyHistoryOldestToNewest()
        assertEquals(32, history.size)
        assertArrayEquals(
            floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f),
            history.copyOfRange(24, 32),
            0f
        )

        session.reset()
        assertEquals(0L, session.logicalSampleCursor)
        assertEquals(0L, session.processedFrameCount)
        assertArrayEquals(FloatArray(32), session.copyHistoryOldestToNewest(), 0f)
    }

    @Test
    fun rejectsInvalidFftHopAndInputDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierSpectrumSession(fftSize = 12, hopSamples = 4, windowCoreLength = 0, windowShapeMode = 0, invertOddComplexBins = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierSpectrumSession(fftSize = 8, hopSamples = 9, windowCoreLength = 0, windowShapeMode = 0, invertOddComplexBins = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierSpectrumSession(fftSize = 8, hopSamples = 4, windowCoreLength = 9, windowShapeMode = 0, invertOddComplexBins = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            session().processFrame(floatArrayOf(1f, 2f, 3f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            session().processFrame(floatArrayOf(1f, 2f, Float.NaN, 4f))
        }
    }
}
