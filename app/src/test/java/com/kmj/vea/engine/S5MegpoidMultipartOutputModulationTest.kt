package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidMultipartOutputModulationTest {
    private fun build(
        f0: Float = 100f,
        theta: Float = 0.5f,
        mask: Boolean = true,
        thresholdMode: Boolean = true
    ) = S5MegpoidMultipartOutputModulationBuilder.build(
        partCount = 4,
        positionCount = 3,
        frequencyStep = 31.25f,
        fundamentalFrequencyHz = f0,
        thetaCenterRadians = theta,
        maximumDistanceParameter = 133.34f,
        distanceExtensionParameter = 25f,
        extendedThresholdMode = true,
        nearestPartMaskEnabledAtRoot3405 = mask,
        thresholdModeEnabledAtRoot3411 = thresholdMode
    )

    @Test
    fun derivesCycleLocationsDistancesAndNearestPartGainFromTheScalarPath() {
        val table = build()
        assertEquals(4, table.partCount)
        assertEquals(3, table.positionCount)

        assertEquals(
            listOf(1f, 2f, 3f, 4f),
            table.copyPhaseLocations().copyOfRange(0, 4).toList()
        )
        assertEquals(
            listOf(100f, 200f, 300f, 400f),
            table.copyDistances().copyOfRange(0, 4).toList()
        )
        assertEquals(
            listOf(1f, 0f, 0f, 0f),
            table.copyGains().copyOfRange(0, 4).toList()
        )

        assertEquals(
            listOf(68.75f, 168.75f, 268.75f, 368.75f),
            table.copyDistances().copyOfRange(4, 8).toList()
        )
        assertEquals(
            listOf(1f, 0f, 0f, 0f),
            table.copyGains().copyOfRange(4, 8).toList()
        )

        // At position 2, both 37.5 and 137.5 are within the 158 threshold.
        // The native rule is within-threshold OR nearest-part, not AND.
        assertEquals(
            listOf(1f, 1f, 0f, 0f),
            table.copyGains().copyOfRange(8, 12).toList()
        )
    }

    @Test
    fun clearThresholdModeFlagUsesTheMaximumOfTheTwoNativeThresholdCandidates() {
        val table = build(thresholdMode = false)
        val gains = table.copyGains()

        // threshold = max(F0 - 2*31.25, truncate(133.34) + 25)
        //             = max(37.5, 158) = 158
        assertEquals(listOf(1f, 0f, 0f, 0f), gains.copyOfRange(0, 4).toList())
        assertEquals(listOf(1f, 0f, 0f, 0f), gains.copyOfRange(4, 8).toList())
        assertEquals(listOf(1f, 1f, 0f, 0f), gains.copyOfRange(8, 12).toList())
    }

    @Test
    fun clearThresholdModeFlagCanExtendTheGateBeyondTheConfiguredDistanceSum() {
        val table = S5MegpoidMultipartOutputModulationBuilder.build(
            partCount = 4,
            positionCount = 35,
            frequencyStep = 31.25f,
            fundamentalFrequencyHz = 500f,
            thetaCenterRadians = 0.5f,
            maximumDistanceParameter = 133.34f,
            distanceExtensionParameter = 25f,
            extendedThresholdMode = true,
            nearestPartMaskEnabledAtRoot3405 = true,
            thresholdModeEnabledAtRoot3411 = false
        )
        val gains = table.copyGains()

        // At position 34 the four distances are 562.5, 62.5, 437.5, 937.5.
        // Native threshold is max(500 - 62.5, 133 + 25) = 437.5:
        // part 2 is nearest, and part 3 is also active exactly on the threshold.
        assertEquals(
            listOf(0f, 1f, 1f, 0f),
            gains.copyOfRange(34 * 4, 35 * 4).toList()
        )
    }

    @Test
    fun defaultNativeTablePathUsesTruncationAndScalarFallbackUsesRoundf() {
        fun build(kernel: S5MegpoidMultipartCoordinateKernel) =
            S5MegpoidMultipartOutputModulationBuilder.build(
                partCount = 4,
                positionCount = 2,
                frequencyStep = 31.25f,
                fundamentalFrequencyHz = 1f,
                thetaCenterRadians = 0.5f,
                maximumDistanceParameter = 133f,
                distanceExtensionParameter = 25f,
                extendedThresholdMode = true,
                nearestPartMaskEnabledAtRoot3405 = false,
                thresholdModeEnabledAtRoot3411 = true,
                coordinateKernel = kernel
            )

        val nativeDefault = S5MegpoidMultipartOutputModulationBuilder.build(
            partCount = 4,
            positionCount = 2,
            frequencyStep = 31.25f,
            fundamentalFrequencyHz = 1f,
            thetaCenterRadians = 0.5f,
            maximumDistanceParameter = 133f,
            distanceExtensionParameter = 25f,
            extendedThresholdMode = true,
            nearestPartMaskEnabledAtRoot3405 = false,
            thresholdModeEnabledAtRoot3411 = true
        )
        val scalarFallback = build(
            S5MegpoidMultipartCoordinateKernel.INLINE_SCALAR_ROUNDF
        )

        assertEquals(
            listOf(29f, 30f, 31f, 28f),
            nativeDefault.copyPhaseLocations().copyOfRange(4, 8).toList()
        )
        assertEquals(
            listOf(33f, 30f, 31f, 32f),
            scalarFallback.copyPhaseLocations().copyOfRange(4, 8).toList()
        )
        assertEquals(
            listOf(2.25f, 1.25f, 0.25f, 3.25f),
            nativeDefault.copyDistances().copyOfRange(4, 8).toList()
        )
    }

    @Test
    fun avxTablePathUsesMxcsrNearestEvenForCoordinatesAndQuadrantReduction() {
        fun build(kernel: S5MegpoidMultipartCoordinateKernel) =
            S5MegpoidMultipartOutputModulationBuilder.build(
                partCount = 4,
                positionCount = 2,
                frequencyStep = 31.25f,
                fundamentalFrequencyHz = 1f,
                thetaCenterRadians = 0.5f,
                maximumDistanceParameter = 133f,
                distanceExtensionParameter = 25f,
                extendedThresholdMode = true,
                nearestPartMaskEnabledAtRoot3405 = false,
                thresholdModeEnabledAtRoot3411 = true,
                coordinateKernel = kernel
            )

        val avx = build(S5MegpoidMultipartCoordinateKernel.SIMD_AVX_TABLE_NEAREST_EVEN)
        val sse = build(S5MegpoidMultipartCoordinateKernel.SIMD_SSE_TABLE_TRUNCATION)

        // At the second output position, the first quotient is 7.5625,
        // so both AVX nearest and SSE truncation produce a cycle of 8 vs 7.
        assertEquals(
            listOf(33f, 30f, 31f, 32f),
            avx.copyPhaseLocations().copyOfRange(4, 8).toList()
        )
        assertEquals(
            listOf(29f, 30f, 31f, 28f),
            sse.copyPhaseLocations().copyOfRange(4, 8).toList()
        )
    }

    @Test
    fun defaultSimdTableUsesTheRecoveredNativeQuarterWavePolynomial() {
        val theta = (2.0 * Math.PI * 0.24).toFloat()
        val table = S5MegpoidMultipartOutputModulationBuilder.build(
            partCount = 4,
            positionCount = 1,
            frequencyStep = 31.25f,
            fundamentalFrequencyHz = 100f,
            thetaCenterRadians = theta,
            maximumDistanceParameter = 133f,
            distanceExtensionParameter = 25f,
            extendedThresholdMode = true,
            nearestPartMaskEnabledAtRoot3405 = false,
            thresholdModeEnabledAtRoot3411 = true
        )

        val phase = theta.toDouble()
        // FUN_1800c4080 uses a minimax polynomial rather than sinf/cosf.
        assertEquals(kotlin.math.sin(phase).toFloat(), table.rotationReal[0], 0.006f)
        assertEquals((-kotlin.math.cos(phase)).toFloat(), table.nativeRotationSine[0], 0.006f)
        // The polynomial's characteristic output is preserved rather than
        // replaced with a platform libm result.
        assertEquals(0.99802905f, table.rotationReal[0], 0.00002f)
        assertEquals(-0.0683494f, table.nativeRotationSine[0], 0.00002f)
    }

    @Test
    fun disablingNearestPartMaskLeavesAllPartsActiveButKeepsTheirRotations() {
        val masked = build(mask = true)
        val unmasked = build(mask = false)
        assertEquals(List(12) { 1f }, unmasked.copyGains().toList())
        assertEquals(masked.copyRotationReal().toList(), unmasked.copyRotationReal().toList())
        assertEquals(masked.copyNativeRotationSine().toList(), unmasked.copyNativeRotationSine().toList())
    }

    @Test
    fun unvoicedInputUsesIdentityRotationAndDoesNotApplyNearestPartMask() {
        val table = build(f0 = 0f, mask = true)
        assertEquals(List(12) { 1f }, table.copyGains().toList())
        assertEquals(List(12) { 1f }, table.copyRotationReal().toList())
        assertEquals(List(12) { 0f }, table.copyNativeRotationSine().toList())
    }

    @Test
    fun accumulatesOnePartUsingNativeComplexRotationSignsAndOrder() {
        val table = build(mask = false, theta = 1.0f)
        val real = floatArrayOf(2f, 2f, 2f)
        val imaginary = floatArrayOf(3f, 3f, 3f)
        val output = FloatArray(6)

        table.accumulatePartInto(
            outputInterleaved = output,
            firstFeaturePlane = real,
            secondFeaturePlane = imaginary,
            partIndex = 0
        )

        val nativeCos = table.copyRotationReal().filterIndexed { i, _ -> i % 4 == 0 }
        val nativeSin = table.copyNativeRotationSine().filterIndexed { i, _ -> i % 4 == 0 }
        for (position in 0 until 3) {
            assertEquals(
                real[position] * nativeCos[position] - imaginary[position] * nativeSin[position],
                output[position * 2],
                0.00001f
            )
            assertEquals(
                imaginary[position] * nativeCos[position] + real[position] * nativeSin[position],
                output[position * 2 + 1],
                0.00001f
            )
        }
    }

    @Test
    fun accumulatesAllFourPartPairsInNativePartOrder() {
        val table = build(f0 = 0f, mask = true)
        val first = listOf(
            floatArrayOf(1f, 2f, 3f),
            floatArrayOf(10f, 20f, 30f),
            floatArrayOf(100f, 200f, 300f),
            floatArrayOf(1000f, 2000f, 3000f)
        )
        val second = listOf(
            floatArrayOf(-1f, -2f, -3f),
            floatArrayOf(-10f, -20f, -30f),
            floatArrayOf(-100f, -200f, -300f),
            floatArrayOf(-1000f, -2000f, -3000f)
        )
        val output = FloatArray(6)

        table.accumulateAllPartsInto(output, first, second)

        assertEquals(
            listOf(1111f, 2222f, 3333f, -1111f, -2222f, -3333f),
            output.toList()
        )
    }

    @Test
    fun rejectsMissingPartPlanesBeforeMutatingTheOutput() {
        val table = build(f0 = 0f)
        val output = FloatArray(6) { 7f }
        assertThrows(IllegalArgumentException::class.java) {
            table.accumulateAllPartsInto(
                outputInterleaved = output,
                firstFeaturePlaneByPart = listOf(FloatArray(3)),
                secondFeaturePlaneByPart = List(4) { FloatArray(3) }
            )
        }
        assertEquals(List(6) { 7f }, output.toList())

        assertThrows(IllegalArgumentException::class.java) {
            table.accumulateAllPartsInto(
                outputInterleaved = output,
                firstFeaturePlaneByPart = List(3) { FloatArray(3) } + listOf(floatArrayOf(1f)),
                secondFeaturePlaneByPart = List(4) { FloatArray(3) }
            )
        }
        assertEquals(List(6) { 7f }, output.toList())
    }

    @Test
    fun rejectsInvalidGeometryAndMismatchedFeaturePlaneLengths() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidMultipartOutputModulationBuilder.build(
                partCount = 1, positionCount = 3, frequencyStep = 31.25f,
                fundamentalFrequencyHz = 100f, thetaCenterRadians = 0.5f,
                maximumDistanceParameter = 133.34f, distanceExtensionParameter = 25f,
                extendedThresholdMode = true,
                nearestPartMaskEnabledAtRoot3405 = true,
                thresholdModeEnabledAtRoot3411 = true
            )
        }
        val table = build()
        assertThrows(IllegalArgumentException::class.java) {
            table.accumulatePartInto(FloatArray(6), FloatArray(2), FloatArray(3), 0)
        }
    }
}
