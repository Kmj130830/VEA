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
    fun alternateThresholdModeUsesTheFrequencyWindowWhenItsFlagIsClear() {
        val table = build(thresholdMode = false)
        val gains = table.copyGains()

        // threshold = min(truncate(133.34) + 25, max(F0, 1e-5) - 2*31.25)
        //             = min(158, 37.5) = 37.5
        assertEquals(listOf(0f, 0f, 0f, 0f), gains.copyOfRange(0, 4).toList())
        assertEquals(listOf(0f, 0f, 0f, 0f), gains.copyOfRange(4, 8).toList())
        assertEquals(listOf(1f, 0f, 0f, 0f), gains.copyOfRange(8, 12).toList())
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
                real[position] * nativeCos[position] + imaginary[position] * nativeSin[position],
                output[position * 2],
                0.00001f
            )
            assertEquals(
                imaginary[position] * nativeCos[position] - real[position] * nativeSin[position],
                output[position * 2 + 1],
                0.00001f
            )
        }
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
