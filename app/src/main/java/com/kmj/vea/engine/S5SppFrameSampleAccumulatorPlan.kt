package com.kmj.vea.engine

/**
 * Typed port of the final per-sample accumulation loop in FUN_1801334b0.
 *
 * For output sample j, the recovered frame index is:
 *   i = baseIndex - offset + j
 * The window coordinate is:
 *   x = (i - (windowSpan >> 1)) / windowSpan
 * And the output is:
 *   (sourceFrame[j] / windowCoefficient(x)) * partialWindowBuffer[i]
 *
 * `partialWindowBuffer` is populated by FUN_1801347a0 in the native path;
 * its renderer descriptor construction is intentionally kept separate.
 */
data class S5SppFrameSampleAccumulatorPlan(
    val baseIndex: Int,
    val offset: Int,
    val windowSpan: Int,
    val sampleCount: Int
) {
    init {
        require(windowSpan > 0)
        require(sampleCount > 0)
        Math.subtractExact(baseIndex, offset)
        Math.addExact(Math.subtractExact(baseIndex, offset), sampleCount - 1)
    }

    fun accumulate(
        sourceFrame: FloatArray,
        partialWindowBuffer: FloatArray,
        window: S5SppWindowLookupPlan
    ): FloatArray {
        require(sourceFrame.size == sampleCount)
        require(sourceFrame.all { it.isFinite() })
        require(partialWindowBuffer.all { it.isFinite() })

        val frameStart = Math.subtractExact(baseIndex, offset)
        val halfWindow = windowSpan ushr 1
        val output = FloatArray(sampleCount)
        for (sample in 0 until sampleCount) {
            val frameIndex = Math.addExact(frameStart, sample)
            require(frameIndex in partialWindowBuffer.indices) {
                "SPP partial/window buffer does not cover recovered frame index $frameIndex."
            }
            val numerator = frameIndex - halfWindow
            val coordinate = numerator.toFloat() / windowSpan.toFloat()
            val coefficient = window.coefficient(coordinate.toDouble()).toFloat()
            require(coefficient.isFinite() && coefficient != 0.0f) {
                "SPP window coefficient must be finite and non-zero."
            }
            val value = (sourceFrame[sample] / coefficient) * partialWindowBuffer[frameIndex]
            require(value.isFinite()) {
                "SPP frame accumulation produced a non-finite sample."
            }
            output[sample] = value
        }
        return output
    }
}
