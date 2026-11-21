package com.kmj.vea.engine

import kotlin.math.PI
import kotlin.math.cos

/**
 * Reproduces the frame-window coefficient generation in S5API FUN_18012a9a0.
 *
 * The destination has totalLength samples. If coreLength is strictly between
 * zero and totalLength, a window of that length is placed in the center of a
 * zero-filled destination. Otherwise, a symmetric full-length Hann window
 * is generated. shapeMode == 1 selects a four-term Blackman-Harris window
 * only for the centered-window branch; all other values use periodic Hann.
 */
object S5MegpoidFrameWindowBuilder {
    fun build(
        totalLength: Int,
        coreLength: Int,
        shapeMode: Int
    ): FloatArray {
        require(totalLength >= 2) { "Frame window length must be at least two samples." }

        val output = FloatArray(totalLength)
        if (coreLength > 0 && coreLength < totalLength) {
            val leftPadding = (totalLength - coreLength) / 2
            for (index in 0 until coreLength) {
                val phase = TWO_PI * index.toDouble() / coreLength.toDouble()
                output[leftPadding + index] = when (shapeMode) {
                    BLACKMAN_HARRIS_MODE -> blackmanHarris(phase)
                    else -> periodicHann(phase)
                }
            }
        } else {
            val denominator = (totalLength - 1).toDouble()
            for (index in 0 until totalLength) {
                val phase = TWO_PI * index.toDouble() / denominator
                output[index] = symmetricHann(phase)
            }
        }
        return output
    }

    private fun periodicHann(phase: Double): Float =
        (0.5 - 0.5 * cos(phase)).toFloat()

    private fun symmetricHann(phase: Double): Float =
        (0.5 - 0.5 * cos(phase)).toFloat()

    private fun blackmanHarris(phase: Double): Float {
        val value = BLACKMAN_HARRIS_A0 -
            BLACKMAN_HARRIS_A1 * cos(phase) +
            BLACKMAN_HARRIS_A2 * cos(2.0 * phase) -
            BLACKMAN_HARRIS_A3 * cos(3.0 * phase)
        return value.toFloat()
    }

    const val BLACKMAN_HARRIS_MODE = 1
    private const val TWO_PI = 6.2831855
    private const val BLACKMAN_HARRIS_A0 = 0.35875
    private const val BLACKMAN_HARRIS_A1 = 0.48829
    private const val BLACKMAN_HARRIS_A2 = 0.14128
    private const val BLACKMAN_HARRIS_A3 = 0.01168
}
