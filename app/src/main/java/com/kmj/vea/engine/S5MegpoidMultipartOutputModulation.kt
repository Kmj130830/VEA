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
 * accumulator. The arrays are position-major: [position * partCount + part].
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
     * real += (x * D46 + y * D4C) * D40
     * imag += (y * D46 - x * D4C) * D40
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
            val tableIndex = position * partCount + partIndex
            val x = firstFeaturePlane[position]
            val y = secondFeaturePlane[position]
            val d46 = rotationReal[tableIndex]
            val d4c = nativeRotationSine[tableIndex]
            val gain = gains[tableIndex]

            val realContribution = (x * d46 + y * d4c) * gain
            val imaginaryContribution = (y * d46 - x * d4c) * gain
            val outputIndex = position * 2
            outputInterleaved[outputIndex] += realContribution
            outputInterleaved[outputIndex + 1] += imaginaryContribution
        }
        require(outputInterleaved.all { it.isFinite() }) {
            "Multipart modulation produced a non-finite complex output."
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

/** Exact scalar coefficient preparation recovered from FUN_1800c7ad0. */
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
        thresholdModeEnabledAtRoot3411: Boolean
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
                if (frequencyBound <= extended) frequencyBound else extended
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

            for (part in 0 until partCount) {
                val baseLocation = (part + 1).toFloat()
                val cycleCount = roundHalfAwayFromZero(
                    (frequencyCoordinate - baseLocation) / partCount.toFloat()
                )
                val candidateLocation =
                    cycleCount * partCount.toFloat() + baseLocation
                val phaseLocation = if (baseLocation <= candidateLocation) {
                    candidateLocation
                } else {
                    baseLocation
                }
                val distance = abs(frequencyCoordinate - phaseLocation) * frequency

                val index = position * partCount + part
                locations[index] = phaseLocation
                distances[index] = distance

                if (nearestPartMaskEnabledAtRoot3405 && voiced) {
                    var nearestOtherDistance = Float.POSITIVE_INFINITY
                    for (otherPart in 0 until partCount) {
                        if (otherPart == part) continue
                        nearestOtherDistance = minOf(
                            nearestOtherDistance,
                            distancesForPart(
                                frequencyCoordinate = frequencyCoordinate,
                                frequency = frequency,
                                partCount = partCount,
                                partIndex = otherPart
                            )
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
                    val phase = (thetaCenterRadians * phaseLocation) % TWO_PI
                    rotationReal[index] = cos(phase - HALF_PI)
                    rotationSine[index] = sin(phase - HALF_PI)
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

    private fun distancesForPart(
        frequencyCoordinate: Float,
        frequency: Float,
        partCount: Int,
        partIndex: Int
    ): Float {
        val baseLocation = (partIndex + 1).toFloat()
        val cycleCount = roundHalfAwayFromZero(
            (frequencyCoordinate - baseLocation) / partCount.toFloat()
        )
        val candidateLocation = cycleCount * partCount.toFloat() + baseLocation
        val phaseLocation = if (baseLocation <= candidateLocation) {
            candidateLocation
        } else {
            baseLocation
        }
        return abs(frequencyCoordinate - phaseLocation) * frequency
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
