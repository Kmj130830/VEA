package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class S5MegpoidNativeRandomTest {
    @Test
    fun seed42MatchesRecoveredMt19937StreamAcrossTwistBoundary() {
        val random = S5MegpoidNativeMersenneTwister(42)
        val checkpoints = mapOf(
            0 to 1_608_637_542L,
            1 to 3_421_126_067L,
            623 to 1_077_437_785L,
            624 to 108_880_612L,
            625 to 791_707_097L,
            1247 to 1_014_112_781L,
            1248 to 2_655_384_025L,
            1249 to 3_263_983_643L
        )

        for (index in 0..1249) {
            val actual = random.nextUInt32().toLong() and 0xffff_ffffL
            checkpoints[index]?.let { expected ->
                assertEquals("MT output at index $index", expected, actual)
            }
        }
    }

    @Test
    fun zeroSeedUsesTheNativeDefaultSeed() {
        val defaultSeed = S5MegpoidNativeMersenneTwister()
        val zeroSeed = S5MegpoidNativeMersenneTwister(0)

        repeat(16) {
            assertEquals(defaultSeed.nextUInt32(), zeroSeed.nextUInt32())
        }
    }

    @Test
    fun uniformConversionUsesTheUpper24Bits() {
        val random = S5MegpoidNativeMersenneTwister(42)

        assertEquals(-0.25091982f, random.nextUniformMinusOneToOne(), 1e-7f)
        assertEquals(0.5930859f, random.nextUniformMinusOneToOne(), 1e-7f)
    }

    @Test
    fun recoveredGaussianFactoryUsesDeterministicCachedPair() {
        val firstSampler = S5MegpoidGaussianNoise.fromNativeSeed()
        val secondSampler = S5MegpoidGaussianNoise.fromNativeSeed()

        val firstX = firstSampler.nextSample()
        val firstY = firstSampler.nextSample()
        val secondX = secondSampler.nextSample()
        val secondY = secondSampler.nextSample()

        assertEquals(-0.5169643f, firstX, 1e-5f)
        assertEquals(1.2219212f, firstY, 1e-5f)
        assertEquals(firstX, secondX, 0f)
        assertEquals(firstY, secondY, 0f)
        assertTrue(firstX.isFinite() && firstY.isFinite())
    }

    @Test
    fun excitationFactoryCanUseRecoveredNativeNoiseStream() {
        fun makeGenerator() = S5MegpoidExcitationGenerator.withRecoveredRandomSource(
            phaseScale = 48_000f,
            periodScale = 24_000f,
            sineDenominatorThreshold = 1e-6f,
            wrapPhase = true
        )

        val first = makeGenerator().generate(32, 220f, 0.1f, 0.9f)
        val second = makeGenerator().generate(32, 220f, 0.1f, 0.9f)

        assertTrue(first.all { it.isFinite() })
        assertTrue(second.all { it.isFinite() })
        assertTrue(first.contentEquals(second))
    }
}
