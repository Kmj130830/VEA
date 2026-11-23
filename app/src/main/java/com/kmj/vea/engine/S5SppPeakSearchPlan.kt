package com.kmj.vea.engine

/**
 * Reusable peak-search settings passed to FUN_180135b90 / FUN_180135670.
 *
 * valueStride=1 represents the lazy-phase magnitude buffer.
 * valueStride=2 represents the normal-phase [magnitude,phase] records.
 * The config array is copied so caller mutation cannot change a running plan.
 */
class S5SppPeakSearchPlan(
    weightConfig: FloatArray,
    val binSpacing: Float,
    val denominator: Float,
    private val evaluateWeight: (FloatArray, Float) -> Float =
        NativeS5TensorBridge::sppPeakWeight,
    private val findPeak: (FloatArray, Int, Int, Int, Float, Float, FloatArray) -> Int =
        NativeS5TensorBridge::findSppWeightedPeak
) {
    private val config = weightConfig.copyOf()

    init {
        require(config.size == WEIGHT_CONFIG_SIZE)
        require(config.all { it.isFinite() })
        require(config[0] > 0.0f && config[1] > 0.0f)
        require(config[2] >= config[1] && config[3] >= config[2])
        require(binSpacing.isFinite() && binSpacing >= 0.0f)
        require(denominator.isFinite() && denominator > 0.0f)
    }

    fun weightAt(coordinate: Float): Float {
        require(coordinate.isFinite())
        return evaluateWeight(config, coordinate)
    }

    /** Weighted peak from a single-lane magnitude buffer (FUN_180135b90). */
    fun findMagnitudePeak(
        magnitudes: FloatArray,
        startIndex: Int,
        endIndex: Int
    ): Int = findPeakInBuffer(magnitudes, valueStride = 1, startIndex, endIndex)

    /**
     * Weighted peak from interleaved [magnitude,phase] records
     * (FUN_180135670); only each pair's first element is ranked.
     */
    fun findInterleavedPeak(
        magnitudePhase: FloatArray,
        startIndex: Int,
        endIndex: Int
    ): Int = findPeakInBuffer(magnitudePhase, valueStride = 2, startIndex, endIndex)

    private fun findPeakInBuffer(
        values: FloatArray,
        valueStride: Int,
        startIndex: Int,
        endIndex: Int
    ): Int {
        require(values.isNotEmpty() && values.size % valueStride == 0)
        require(values.all { it.isFinite() })
        val valueCount = values.size / valueStride
        require(startIndex >= 0 && startIndex <= endIndex && endIndex < valueCount)
        return findPeak(
            values, valueStride, startIndex, endIndex,
            binSpacing, denominator, config
        )
    }

    fun snapshotWeightConfig(): FloatArray = config.copyOf()

    companion object {
        const val WEIGHT_CONFIG_SIZE = 5
    }
}
