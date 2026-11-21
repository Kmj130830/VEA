package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class S5MegpoidExcitationGeneratorTest {
    @Test
    fun zeroF0WithZeroNoiseProducesSilenceAndRetainsFrameState() {
        val generator = S5MegpoidExcitationGenerator(
            phaseScale = 32_000f,
            periodScale = 32_000f,
            sineDenominatorThreshold = 0.1f,
            wrapPhase = true,
            gaussianNoise = { 0f }
        )

        val output = generator.generate(16, 0f, 0f, 0.1f)

        assertTrue(output.all { it == 0f })
        assertEquals(0f, generator.phaseForTesting, 0f)
    }

    @Test
    fun noiseGainIsInterpolatedAcrossTheFrame() {
        val generator = S5MegpoidExcitationGenerator(
            phaseScale = 32_000f,
            periodScale = 32_000f,
            sineDenominatorThreshold = 0.1f,
            wrapPhase = true,
            gaussianNoise = { 1f }
        )

        val output = generator.generate(4, 0f, 0.8f, 0f)

        assertEquals(0f, output[0], 0f)
        assertEquals(0.2f, output[1], 1e-6f)
        assertEquals(0.4f, output[2], 1e-6f)
        assertEquals(0.6f, output[3], 1e-6f)
    }

    @Test
    fun phaseAccumulatesContinuouslyAcrossFrames() {
        val generator = S5MegpoidExcitationGenerator(
            phaseScale = 32_000f,
            periodScale = 32_000f,
            sineDenominatorThreshold = 0.1f,
            wrapPhase = false,
            gaussianNoise = { 0f }
        )

        generator.generate(4, 1_000f, 0f, 0f)
        generator.generate(4, 1_000f, 0f, 0f)

        val expectedPhase = 5_500f * (S5MegpoidExcitationGenerator.TWO_PI / 32_000f)
        assertEquals(expectedPhase, generator.phaseForTesting, 1e-5f)
    }

    @Test
    fun gaussianSamplerCachesTheSecondPolarSample() {
        val values = ArrayDeque(listOf(0.5f, 0.25f))
        var calls = 0
        val sampler = S5MegpoidGaussianNoise {
            calls++
            values.removeFirst()
        }

        val first = sampler.nextSample()
        val second = sampler.nextSample()

        assertEquals(2, calls)
        assertTrue(first.isFinite())
        assertTrue(second.isFinite())
        assertTrue(first != second)
    }
}
