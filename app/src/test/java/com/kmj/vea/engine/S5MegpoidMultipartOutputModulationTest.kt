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

        // Tables are part-major: three output positions are contiguous per part.
        assertEquals(
            listOf(1f, 1f, 1f),
            table.copyPhaseLocations().copyOfRange(0, 3).toList()
        )
        assertEquals(
            listOf(2f, 2f, 2f),
            table.copyPhaseLocations().copyOfRange(3, 6).toList()
        )
        assertEquals(
            listOf(100f, 68.75f, 37.5f),
            table.copyDistances().copyOfRange(0, 3).toList()
        )
        assertEquals(
            listOf(200f, 168.75f, 137.5f),
            table.copyDistances().copyOfRange(3, 6).toList()
        )
        assertEquals(
            listOf(1f, 1f, 1f),
            table.copyGains().copyOfRange(0, 3).toList()
        )
        assertEquals(
            listOf(0f, 0f, 1f),
            table.copyGains().copyOfRange(3, 6).toList()
        )
        // At position 2, parts 0 and 1 are within the 158 threshold.
        assertEquals(0f, table.copyGains()[6 + 2], 0f)
        assertEquals(0f, table.copyGains()[9 + 2], 0f)
    }

    @Test
    fun clearThresholdModeFlagUsesTheMaximumOfTheTwoNativeThresholdCandidates() {
        val table = build(thresholdMode = false)
        val gains = table.copyGains()

        // threshold = max(F0 - 2*31.25, truncate(133.34) + 25)
        //             = max(37.5, 158) = 158; inspect part-major columns.
        assertEquals(listOf(1f, 1f, 1f), gains.copyOfRange(0, 3).toList())
        assertEquals(listOf(0f, 0f, 1f), gains.copyOfRange(3, 6).toList())
        assertEquals(0f, gains[6 + 2], 0f)
        assertEquals(0f, gains[9 + 2], 0f)
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
            List(4) { part -> gains[part * 35 + 34] }
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
            List(4) { part -> nativeDefault.copyPhaseLocations()[part * 2 + 1] }
        )
        assertEquals(
            listOf(33f, 30f, 31f, 32f),
            List(4) { part -> scalarFallback.copyPhaseLocations()[part * 2 + 1] }
        )
        assertEquals(
            listOf(2.25f, 1.25f, 0.25f, 3.25f),
            List(4) { part -> nativeDefault.copyDistances()[part * 2 + 1] }
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
            List(4) { part -> avx.copyPhaseLocations()[part * 2 + 1] }
        )
        assertEquals(
            listOf(29f, 30f, 31f, 28f),
            List(4) { part -> sse.copyPhaseLocations()[part * 2 + 1] }
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

        val nativeCos = table.copyRotationReal().copyOfRange(0, 3)
        val nativeSin = table.copyNativeRotationSine().copyOfRange(0, 3)
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
    fun accumulatesTheSharedComplexPairInNativePartOrder() {
        val table = build(f0 = 0f, mask = true)
        val first = floatArrayOf(1f, 2f, 3f)
        val second = floatArrayOf(-1f, -2f, -3f)
        val output = FloatArray(6)

        table.accumulateAllPartsInto(output, first, second)

        assertEquals(
            listOf(4f, -4f, 8f, -8f, 12f, -12f),
            output.toList()
        )
    }

    @Test
    fun rejectsMismatchedSharedFeaturePlanesBeforeMutatingTheOutput() {
        val table = build(f0 = 0f)
        val output = FloatArray(6) { 7f }
        assertThrows(IllegalArgumentException::class.java) {
            table.accumulateAllPartsInto(
                outputInterleaved = output,
                firstFeaturePlane = FloatArray(2),
                secondFeaturePlane = FloatArray(3)
            )
        }
        assertEquals(List(6) { 7f }, output.toList())

        assertThrows(IllegalArgumentException::class.java) {
            table.accumulateAllPartsInto(
                outputInterleaved = output,
                firstFeaturePlane = FloatArray(3),
                secondFeaturePlane = FloatArray(4)
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
