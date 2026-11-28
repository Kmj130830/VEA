package com.kmj.vea.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Native scalar reference for the four-part complex modulation setup inside
 * FUN_1800c7ad0.
 *
 * For each spectrum position, the S5API scalar path computes a cycle-aligned
 * coordinate for every part, a distance from that coordinate, an optional
 * nearest-part gate, and the complex rotation pair consumed by the output
 * accumulator. The native arrays are part-major: [part * positionCount + position].
 *
 * This class covers only that verified modulation/gating operation. It does
 * not split the 8-channel post-residual head into four part-feature pairs;
 * those planes must be supplied by the separate output-head stage.
 */
data class S5MegpoidMultipartOutputModulation(
    val partCount: Int,
    val positionCount: Int,
    val phaseLocations: FloatArray,
    val distances: FloatArray,
    val gains: FloatArray,
    val rotationReal: FloatArray,
    /** Native D4C coefficient: sin(theta - PI/2). */
    val nativeRotationSine: FloatArray
) {
    init {
        require(partCount >= 2 && positionCount > 0)
        val expected = checkedCount(partCount, positionCount)
        require(phaseLocations.size == expected)
        require(distances.size == expected)
        require(gains.size == expected)
        require(rotationReal.size == expected)
        require(nativeRotationSine.size == expected)
        require(phaseLocations.all { it.isFinite() })
        require(distances.all { it.isFinite() && it >= 0f })
        require(gains.all { it.isFinite() && (it == 0f || it == 1f) })
        require(rotationReal.all { it.isFinite() })
        require(nativeRotationSine.all { it.isFinite() })
    }

    private fun offset(partIndex: Int): Int {
        require(partIndex in 0 until partCount)
        return partIndex
    }

    /**
     * Applies one part's exact scalar accumulation to an interleaved complex
     * buffer. Input planes are [position], output is [real0,imag0,...].
     *
     * The arithmetic order follows the caller in FUN_1800c7ad0:
     * real += (x * D46 - y * D4C) * D40
     * imag += (y * D46 + x * D4C) * D40
     */
    fun accumulatePartInto(
        outputInterleaved: FloatArray,
        firstFeaturePlane: FloatArray,
        secondFeaturePlane: FloatArray,
        partIndex: Int
    ) {
        offset(partIndex)
        require(outputInterleaved.size.toLong() == positionCount.toLong() * 2L) {
            "Multipart modulation output must contain two interleaved values per position."
        }
        require(firstFeaturePlane.size == positionCount &&
            secondFeaturePlane.size == positionCount) {
            "Each output-head feature plane must contain exactly $positionCount positions."
        }
        require(outputInterleaved.all { it.isFinite() } &&
            firstFeaturePlane.all { it.isFinite() } &&
            secondFeaturePlane.all { it.isFinite() }) {
            "Multipart modulation input or accumulator contains a non-finite value."
        }

        for (position in 0 until positionCount) {
            val tableIndex = partIndex * positionCount + position
            val x = firstFeaturePlane[position]
            val y = secondFeaturePlane[position]
            val d46 = rotationReal[tableIndex]
            val d4c = nativeRotationSine[tableIndex]
            val gain = gains[tableIndex]

            // Native FUN_1800d52b0/FUN_1800d5610 scales both input
            // planes first, then rotates the complex pair.
            val scaledReal = x * gain
            val scaledImaginary = y * gain
            val realContribution = d46 * scaledReal - d4c * scaledImaginary
            val imaginaryContribution = d4c * scaledReal + d46 * scaledImaginary
            val outputIndex = position * 2
            outputInterleaved[outputIndex] += realContribution
            outputInterleaved[outputIndex + 1] += imaginaryContribution
        }
        require(outputInterleaved.all { it.isFinite() }) {
            "Multipart modulation produced a non-finite complex output."
        }
    }

    /**
     * Accumulates the per-part real/imaginary feature pairs in native part
     * order. The feature tensor is [part][plane][position], while the
     * coefficient tables are [part][position].
     */
    fun accumulateAllPartsInto(
        outputInterleaved: FloatArray,
        firstFeaturePlaneByPart: List<FloatArray>,
        secondFeaturePlaneByPart: List<FloatArray>
    ) {
        require(firstFeaturePlaneByPart.size == partCount &&
            secondFeaturePlaneByPart.size == partCount) {
            "The active multipart output requires exactly $partCount feature-plane pairs."
        }
        require(outputInterleaved.size.toLong() == positionCount.toLong() * 2L) {
            "Multipart complex output must contain two interleaved values per position."
        }
        require(outputInterleaved.all { it.isFinite() }) {
            "Multipart complex output accumulator contains a non-finite value."
        }
        for (partIndex in 0 until partCount) {
            val first = firstFeaturePlaneByPart[partIndex]
            val second = secondFeaturePlaneByPart[partIndex]
            require(first.size == positionCount && second.size == positionCount) {
                "Multipart feature pair $partIndex must contain exactly $positionCount positions."
            }
            require(first.all { it.isFinite() } && second.all { it.isFinite() }) {
                "Multipart feature pair $partIndex contains a non-finite value."
            }
        }

        // Match the native outer part loop: process each part's own pair across
        // all positions, then advance to the next part.
        for (partIndex in 0 until partCount) {
            accumulatePartInto(
                outputInterleaved = outputInterleaved,
                firstFeaturePlane = firstFeaturePlaneByPart[partIndex],
                secondFeaturePlane = secondFeaturePlaneByPart[partIndex],
                partIndex = partIndex
            )
        }
    }

    fun copyPhaseLocations(): FloatArray = phaseLocations.copyOf()
    fun copyDistances(): FloatArray = distances.copyOf()
    fun copyGains(): FloatArray = gains.copyOf()
    fun copyRotationReal(): FloatArray = rotationReal.copyOf()
    fun copyNativeRotationSine(): FloatArray = nativeRotationSine.copyOf()

    private fun checkedCount(parts: Int, positions: Int): Int {
        val count = parts.toLong() * positions.toLong()
        require(count in 1..Int.MAX_VALUE.toLong()) {
            "Multipart modulation table exceeds the supported FloatArray size."
        }
        return count.toInt()
    }
}

/**
 * Native part-location implementation selected by the renderer's CPU route.
 * The default S5API path uses the 4-lane SIMD table helper; setting the
 * MRW_MODROT_SIMD override to "0" or using its scalar fallback selects
 * inline roundf semantics.
 */
enum class S5MegpoidMultipartCoordinateKernel {
    /** FUN_1800c4080: int32 truncation for location and turn reduction. */
    SIMD_SSE_TABLE_TRUNCATION,
    /** FUN_1800c39f0: vroundps with the default MXCSR nearest-even mode. */
    SIMD_AVX_TABLE_NEAREST_EVEN,
    /** FUN_1800c7ad0 inline fallback: roundf, half away from zero, libm trig. */
    INLINE_SCALAR_ROUNDF
}

/** Portable coefficient preparation for both recovered native routes. */
object S5MegpoidMultipartOutputModulationBuilder {
    private const val TWO_PI = 6.2831855f
    private const val HALF_PI = 1.5707964f
    private const val MIN_FREQUENCY = 1.0e-5f

    /**
     * Build the part/position coefficient arrays.
     *
     * [frequencyStep] is the native geometry / FFT-size ratio
     * (render-root +0x10 divided by +0x04 for AI Megpoid V7).
     * [thetaCenterRadians] is the current theta_ctr value read by the native
     * render frame. [fundamentalFrequencyHz] is the current input F0.
     *
     * root +0x3405 enables nearest-part/distance gating.
     * root +0x3411 selects the alternate distance-threshold branch.
     * Both flags are explicit because they are populated by independent
     * optional model properties and must not be guessed by this builder.
     */
    fun build(
        partCount: Int,
        positionCount: Int,
        frequencyStep: Float,
        fundamentalFrequencyHz: Float,
        thetaCenterRadians: Float,
        maximumDistanceParameter: Float,
        distanceExtensionParameter: Float,
        extendedThresholdMode: Boolean,
        nearestPartMaskEnabledAtRoot3405: Boolean,
        thresholdModeEnabledAtRoot3411: Boolean,
        coordinateKernel: S5MegpoidMultipartCoordinateKernel =
            S5MegpoidMultipartCoordinateKernel.SIMD_SSE_TABLE_TRUNCATION
    ): S5MegpoidMultipartOutputModulation {
        require(partCount >= 2) { "The multipart modulation path requires at least two parts." }
        require(positionCount > 0)
        require(frequencyStep.isFinite() && frequencyStep > 0f)
        require(fundamentalFrequencyHz.isFinite())
        require(thetaCenterRadians.isFinite())
        require(maximumDistanceParameter.isFinite() && maximumDistanceParameter >= 0f)
        require(distanceExtensionParameter.isFinite() && distanceExtensionParameter >= 0f)

        val frequency = maxOf(fundamentalFrequencyHz, MIN_FREQUENCY)
        val voiced = fundamentalFrequencyHz > 0f

        // FUN_1800c7ad0 first narrows D02/D03 to int-backed render fields.
        val d02 = truncateNativeInteger(maximumDistanceParameter)
        val d03 = truncateNativeInteger(distanceExtensionParameter)
        val threshold = if (!extendedThresholdMode) {
            val lowerBound = maxOf(frequency - (frequencyStep + frequencyStep), frequencyStep)
            minOf(d02, lowerBound)
        } else {
            val extended = d02 + d03
            if (!thresholdModeEnabledAtRoot3411) {
                val frequencyBound = frequency - (frequencyStep + frequencyStep)
                maxOf(frequencyBound, extended)
            } else {
                extended
            }
        }

        val tableSize = checkedCount(partCount, positionCount)
        val locations = FloatArray(tableSize)
        val distances = FloatArray(tableSize)
        val gains = FloatArray(tableSize) { 1f }
        val rotationReal = FloatArray(tableSize)
        val rotationSine = FloatArray(tableSize)

        for (position in 0 until positionCount) {
            // fVar58 = (position * (param[4] / param[1])) / max(F0, 1e-5f)
            val frequencyCoordinate =
                (position.toFloat() * frequencyStep) / frequency

            // Native first pass: fill every part's location and distance arrays.
            for (part in 0 until partCount) {
                val baseLocation = (part + 1).toFloat()
                val cycleValue =
                    (frequencyCoordinate - baseLocation) / partCount.toFloat()
                val cycleCount = when (coordinateKernel) {
                    S5MegpoidMultipartCoordinateKernel.SIMD_SSE_TABLE_TRUNCATION ->
                        truncateCoordinateTowardsZero(cycleValue)
                    S5MegpoidMultipartCoordinateKernel.SIMD_AVX_TABLE_NEAREST_EVEN ->
                        roundToNearestEven(cycleValue)
                    S5MegpoidMultipartCoordinateKernel.INLINE_SCALAR_ROUNDF ->
                        roundHalfAwayFromZero(cycleValue)
                }
                val candidateLocation =
                    cycleCount * partCount.toFloat() + baseLocation
                val phaseLocation = maxOf(baseLocation, candidateLocation)
                val index = part * positionCount + position

                locations[index] = phaseLocation
                distances[index] =
                    abs(frequencyCoordinate - phaseLocation) * frequency
            }

            // Native second pass: compare the completed distance row, then
            // prepare the complex rotation used by the accumulator.
            for (part in 0 until partCount) {
                val index = part * positionCount + position
                val phaseLocation = locations[index]
                val distance = distances[index]

                if (nearestPartMaskEnabledAtRoot3405 && voiced) {
                    var nearestOtherDistance = Float.POSITIVE_INFINITY
                    for (otherPart in 0 until partCount) {
                        if (otherPart == part) continue
                        nearestOtherDistance = minOf(
                            nearestOtherDistance,
                            distances[otherPart * positionCount + position]
                        )
                    }

                    // Native branch: under the threshold every part remains
                    // active; outside it, only a nearest part remains active.
                    // Ties are retained because the native comparison is strict.
                    val isNearestPart = nearestOtherDistance >= distance
                    val isWithinThreshold = distance <= threshold
                    gains[index] = if (isNearestPart || isWithinThreshold) 1f else 0f
                }

                if (voiced) {
                    when (coordinateKernel) {
                        S5MegpoidMultipartCoordinateKernel.SIMD_SSE_TABLE_TRUNCATION -> {
                            val turns = thetaCenterRadians * 0.15915494f * phaseLocation
                            rotationReal[index] = nativeTableSine(
                                turns, S5MegpoidMultipartCoordinateKernel.SIMD_SSE_TABLE_TRUNCATION
                            )
                            rotationSine[index] = nativeTableNegativeCosine(
                                turns, S5MegpoidMultipartCoordinateKernel.SIMD_SSE_TABLE_TRUNCATION
                            )
                        }
                        S5MegpoidMultipartCoordinateKernel.SIMD_AVX_TABLE_NEAREST_EVEN -> {
                            val turns = thetaCenterRadians * 0.15915494f * phaseLocation
                            rotationReal[index] = nativeTableSine(
                                turns, S5MegpoidMultipartCoordinateKernel.SIMD_AVX_TABLE_NEAREST_EVEN
                            )
                            rotationSine[index] = nativeTableNegativeCosine(
                                turns, S5MegpoidMultipartCoordinateKernel.SIMD_AVX_TABLE_NEAREST_EVEN
                            )
                        }
                        S5MegpoidMultipartCoordinateKernel.INLINE_SCALAR_ROUNDF -> {
                            val phase = (thetaCenterRadians * phaseLocation) % TWO_PI
                            rotationReal[index] = cos(phase - HALF_PI)
                            rotationSine[index] = sin(phase - HALF_PI)
                        }
                    }
                } else {
                    // The native unvoiced branch uses identity rotation.
                    rotationReal[index] = 1f
                    rotationSine[index] = 0f
                }
            }
        }

        return S5MegpoidMultipartOutputModulation(
            partCount = partCount,
            positionCount = positionCount,
            phaseLocations = locations,
            distances = distances,
            gains = gains,
            rotationReal = rotationReal,
            nativeRotationSine = rotationSine
        )
    }

    /**
     * FUN_1800c4080 / FUN_1800c39f0 approximate sine with the same minimax
     * polynomial and quadrant reduction. Input is turns, not radians.
     */
    private fun nativeTableSine(
        turns: Float,
        kernel: S5MegpoidMultipartCoordinateKernel
    ): Float {
        val fractionalTurn = turns - reduceToInteger(turns, kernel)
        val doubled = fractionalTurn + fractionalTurn
        val quadrant = reduceToInteger(doubled, kernel)
        val x = (doubled - quadrant) * 2.0f
        val square = x * x
        val magnitude = nativeQuarterWavePolynomial(square) * x
        return if ((quadrant.toInt() and 1) == 0) magnitude else -magnitude
    }

    /** Native D4C is the negative cosine of the same phase. */
    private fun nativeTableNegativeCosine(
        turns: Float,
        kernel: S5MegpoidMultipartCoordinateKernel
    ): Float {
        val shifted = turns + 0.25f
        val fractionalTurn = shifted - reduceToInteger(shifted, kernel)
        val doubled = fractionalTurn + fractionalTurn
        val quadrant = reduceToInteger(doubled, kernel)
        val x = (doubled - quadrant) * 2.0f
        val square = x * x
        val magnitude = nativeQuarterWavePolynomial(square) * x
        val cosine = if ((quadrant.toInt() and 1) == 0) magnitude else -magnitude
        return -cosine
    }

    private fun reduceToInteger(
        value: Float,
        kernel: S5MegpoidMultipartCoordinateKernel
    ): Float = when (kernel) {
        S5MegpoidMultipartCoordinateKernel.SIMD_SSE_TABLE_TRUNCATION ->
            truncateCoordinateTowardsZero(value)
        S5MegpoidMultipartCoordinateKernel.SIMD_AVX_TABLE_NEAREST_EVEN ->
            roundToNearestEven(value)
        S5MegpoidMultipartCoordinateKernel.INLINE_SCALAR_ROUNDF ->
            roundHalfAwayFromZero(value)
    }

    private fun nativeQuarterWavePolynomial(square: Float): Float {
        var value = square * 0.00016044118f + -0.004681754f
        value = value * square + 0.079692625f
        value = value * square + -0.6459641f
        value = value * square + 1.5707964f
        return value
    }

    /** The native 4-lane table path explicitly converts the quotient to int32. */
    private fun truncateCoordinateTowardsZero(value: Float): Float {
        require(value.isFinite() && value >= Int.MIN_VALUE.toFloat() &&
            value <= Int.MAX_VALUE.toFloat()) {
            "Multipart cycle coordinate cannot be narrowed to the native int32 field."
        }
        return value.toInt().toFloat()
    }

    /** MXCSR default rounding: nearest integer, halfway cases to even. */
    private fun roundToNearestEven(value: Float): Float {
        require(value.isFinite() && value >= Int.MIN_VALUE.toFloat() &&
            value <= Int.MAX_VALUE.toFloat()) {
            "Multipart coordinate cannot be rounded to the native int32 range."
        }
        return Math.rint(value.toDouble()).toFloat()
    }

    /** C/C++ roundf semantics: nearest integer, half values away from zero. */
    private fun roundHalfAwayFromZero(value: Float): Float =
        if (value >= 0f) {
            floor(value.toDouble() + 0.5).toFloat()
        } else {
            ceil(value.toDouble() - 0.5).toFloat()
        }

    private fun truncateNativeInteger(value: Float): Float {
        require(value.isFinite() && value >= Int.MIN_VALUE.toFloat() &&
            value <= Int.MAX_VALUE.toFloat()) {
            "Multipart distance parameter cannot be narrowed to the native int32 field."
        }
        return value.toInt().toFloat()
    }

    private fun checkedCount(parts: Int, positions: Int): Int {
        val count = parts.toLong() * positions.toLong()
        require(parts > 0 && positions > 0 && count <= Int.MAX_VALUE) {
            "Multipart modulation table size is invalid or exceeds the FloatArray limit."
        }
        return count.toInt()
    }
}
