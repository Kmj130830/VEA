package com.kmj.vea.engine

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
                val phase = (index.toFloat() * TWO_PI) / coreLength.toFloat()
                output[leftPadding + index] = when (shapeMode) {
                    BLACKMAN_HARRIS_MODE -> blackmanHarris(phase)
                    else -> periodicHann(phase)
                }
            }
        } else {
            val denominator = (totalLength - 1).toFloat()
            for (index in 0 until totalLength) {
                val phase = (index.toFloat() * TWO_PI) / denominator
                output[index] = hann(phase)
            }
        }
        return output
    }

    private fun periodicHann(phase: Float): Float = hann(phase)

    private fun hann(phase: Float): Float =
        0.5f - 0.5f * cos(phase.toDouble()).toFloat()

    private fun blackmanHarris(phase: Float): Float {
        val cos1 = cos(phase.toDouble()).toFloat()
        val cos2 = cos((phase * 2f).toDouble()).toFloat()
        val cos3 = cos((phase * 3f).toDouble()).toFloat()
        var value = BLACKMAN_HARRIS_A2 * cos2
        value += BLACKMAN_HARRIS_A0 - BLACKMAN_HARRIS_A1 * cos1
        value -= BLACKMAN_HARRIS_A3 * cos3
        return value
    }

    const val BLACKMAN_HARRIS_MODE = 1
    private const val TWO_PI = 6.2831855f
    private const val BLACKMAN_HARRIS_A0 = 0.35875f
    private const val BLACKMAN_HARRIS_A1 = 0.48829f
    private const val BLACKMAN_HARRIS_A2 = 0.14128f
    private const val BLACKMAN_HARRIS_A3 = 0.01168f
}
