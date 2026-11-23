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
        window: S5SppWindowLookupPlan,
        execute: (
            FloatArray, FloatArray, Int, Int, Int, DoubleArray, Int, Double, Double
        ) -> FloatArray = NativeS5TensorBridge::accumulateSppFrameSamples
    ): FloatArray {
        require(sourceFrame.size == sampleCount)
        require(sourceFrame.all { it.isFinite() })
        require(partialWindowBuffer.all { it.isFinite() })

        val output = execute(
            sourceFrame,
            partialWindowBuffer,
            baseIndex,
            offset,
            windowSpan,
            window.table,
            window.segmentCount,
            window.scale,
            window.fallbackValue
        )
        require(output.size == sampleCount) {
            "SPP frame sample accumulator returned an invalid output length."
        }
        require(output.all { it.isFinite() })
        return output
    }

}
