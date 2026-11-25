package com.kmj.vea.engine

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Terminal S5API feature-pair representation at the FUN_1800c7ad0 output
 * boundary. The active descriptor selects which representation is used;
 * this enum intentionally has no implicit default.
 */
enum class S5MegpoidSpectrumFrameEncoding {
    /** Feature plane 0 is real and feature plane 1 is imaginary. */
    DIRECT_COMPLEX,

    /** Plane 0 is log magnitude; plane 1 is phase in radians. */
    LOG_MAGNITUDE_PHASE
}

/**
 * Reconstructs the one-sided complex spectrum written by FUN_1800c7ad0
 * immediately before FUN_18012c740.
 *
 * Static call-site evidence:
 * - The model output buffer contains two contiguous float planes, each with
 *   complexBinCount values.
 * - The direct branch copies these planes to interleaved real/imaginary pairs.
 * - The alternate branch computes exp(plane0), caps it at 100, and multiplies
 *   by cos(plane1)/sin(plane1) to form the complex pair.
 *
 * The branch selector's active Megpoid value has not yet been mapped to the
 * Android options object. Callers must therefore specify the encoding.
 */
object S5MegpoidSpectrumFrameEncoder {
    const val LOG_MAGNITUDE_EXP_UPPER_LIMIT = 100.0f

    fun encode(
        firstFeaturePlane: FloatArray,
        secondFeaturePlane: FloatArray,
        shape: S5MegpoidSpectrumOutputShape,
        encoding: S5MegpoidSpectrumFrameEncoding
    ): FloatArray {
        val bins = shape.complexBinCount
        require(firstFeaturePlane.size == bins) {
            "First model-output feature plane must contain exactly $bins values."
        }
        require(secondFeaturePlane.size == bins) {
            "Second model-output feature plane must contain exactly $bins values."
        }
        require(firstFeaturePlane.all { it.isFinite() } &&
            secondFeaturePlane.all { it.isFinite() }) {
            "Model-output feature planes contain a non-finite value."
        }

        val output = FloatArray(shape.interleavedFloatCount)
        when (encoding) {
            S5MegpoidSpectrumFrameEncoding.DIRECT_COMPLEX -> {
                for (bin in 0 until bins) {
                    val target = bin * 2
                    output[target] = firstFeaturePlane[bin]
                    output[target + 1] = secondFeaturePlane[bin]
                }
            }

            S5MegpoidSpectrumFrameEncoding.LOG_MAGNITUDE_PHASE -> {
                for (bin in 0 until bins) {
                    val logMagnitude = firstFeaturePlane[bin]
                    val phase = secondFeaturePlane[bin]
                    val magnitude = exp(logMagnitude).coerceAtMost(LOG_MAGNITUDE_EXP_UPPER_LIMIT)
                    val target = bin * 2
                    output[target] = magnitude * cos(phase)
                    output[target + 1] = magnitude * sin(phase)
                }
            }
        }
        shape.requireInterleavedFrame(output)
        return output
    }
}
