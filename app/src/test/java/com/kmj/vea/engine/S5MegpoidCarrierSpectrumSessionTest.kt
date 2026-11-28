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
        // lagBlocks=ceil(8/4)+2=4
        // leftPadding=(8-4)/2=2
        // required=(4+2)*4+2+8-1+4=37 -> capacity 64, mask 63.
        assertEquals(4, session.nativeLagBlockCount)
        assertEquals(2, session.nativeLeftPadding)
        assertEquals(64, session.ringCapacity)
    }

    @Test
    fun delaysInitialFramesUntilTheNativeHistoryWindowIsAvailable() {
        val session = session()

        repeat(5) { index ->
            val spectrum = session.processFrame(
                floatArrayOf(
                    index * 4f,
                    index * 4f + 1f,
                    index * 4f + 2f,
                    index * 4f + 3f
                )
            )
            assertArrayEquals(FloatArray(10), spectrum, 0f)
        }

        assertEquals(5L, session.processedFrameCount)
        assertEquals(20L, session.logicalSampleCursor)
    }

    @Test
    fun selectsDelayedLogicalSamplesAppliesFrameWindowAndRunsForwardFft() {
        val session = session()
        repeat(5) { frame ->
            session.processFrame(FloatArray(4) { frame * 4f + it })
        }

        // On frame 6 the native source start is:
        // (cursor/hop - lagBlocks - 1)*hop - leftPadding
        // = (6 - 4 - 1)*4 - 2 = 2.
        val sixthSpectrum = session.processFrame(floatArrayOf(20f, 21f, 22f, 23f))
        val rawFrame = floatArrayOf(2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        val window = S5MegpoidFrameWindowBuilder.build(
            totalLength = 8,
            coreLength = 0,
            shapeMode = 0
        )
        val expectedWindowed = FloatArray(8) { rawFrame[it] * window[it] }
        val expected = S5MegpoidForwardRealFft(8).transform(expectedWindowed)

        assertArrayEquals(expected, sixthSpectrum, 1.0e-6f)
        assertEquals(6L, session.processedFrameCount)
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
        assertEquals(64, history.size)
        assertArrayEquals(
            floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f),
            history.copyOfRange(56, 64),
            0f
        )

        session.reset()
        assertEquals(0L, session.logicalSampleCursor)
        assertEquals(0L, session.processedFrameCount)
        assertArrayEquals(FloatArray(64), session.copyHistoryOldestToNewest(), 0f)
    }

    @Test
    fun rejectsInvalidFftHopAndInputDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierSpectrumSession(fftSize = 12, hopSamples = 4, 0, 0, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierSpectrumSession(fftSize = 8, hopSamples = 9, 0, 0, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierSpectrumSession(fftSize = 8, hopSamples = 4, 9, 0, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            session().processFrame(floatArrayOf(1f, 2f, 3f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            session().processFrame(floatArrayOf(1f, 2f, Float.NaN, 4f))
        }
    }
}
